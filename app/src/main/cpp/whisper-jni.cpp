// JNI bridge to whisper.cpp.
//
// Exposes:
//   nativeInit(modelPath: String): Long          -> opaque ctx handle
//   nativeTranscribe(handle: Long,
//                    wavPath: String,
//                    languageHint: String?,
//                    nThreads: Int): String      -> JSON
//   nativeRelease(handle: Long)
//
// The transcribe call returns a JSON document of the form:
//   {
//     "language": "en",
//     "segments": [
//       {
//         "id": 0,
//         "start_ms": 1200,
//         "end_ms": 2050,
//         "text": "hello world",
//         "words": [
//           { "start_ms": 1200, "end_ms": 1480, "text": "hello", "p": 0.93 },
//           ...
//         ]
//       }
//     ]
//   }
//
// Word timing comes from whisper.cpp's per-token timestamps, with special /
// non-printable tokens skipped and consecutive sub-tokens merged into whole
// words on whitespace/punctuation boundaries.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "whisper.h"

#define LOG_TAG "captionburn-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// WAV loading: expects 16-bit PCM, mono, 16 kHz. AudioExtractor produces this.
// ---------------------------------------------------------------------------
struct WavData {
    std::vector<float> samples;
    bool ok = false;
    std::string error;
};

WavData load_wav_pcm16_mono_16k(const std::string& path) {
    WavData out;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) {
        out.error = "fopen failed: " + path;
        return out;
    }

    auto fail = [&](const char* m) {
        out.ok = false;
        out.error = m;
        std::fclose(f);
        return out;
    };

    // RIFF header
    char riff[4]; if (std::fread(riff, 1, 4, f) != 4 || std::memcmp(riff, "RIFF", 4) != 0)
        return fail("not RIFF");
    std::fseek(f, 4, SEEK_CUR); // chunk size
    char wave[4]; if (std::fread(wave, 1, 4, f) != 4 || std::memcmp(wave, "WAVE", 4) != 0)
        return fail("not WAVE");

    uint16_t channels = 0, bitsPerSample = 0, fmtTag = 0;
    uint32_t sampleRate = 0;
    uint32_t dataBytes = 0;
    long dataPos = -1;

    // Walk chunks until we find fmt and data.
    while (true) {
        char chunkId[4];
        uint32_t chunkSize;
        if (std::fread(chunkId, 1, 4, f) != 4) break;
        if (std::fread(&chunkSize, 4, 1, f) != 1) break;

        if (std::memcmp(chunkId, "fmt ", 4) == 0) {
            uint16_t f16; uint32_t f32;
            std::fread(&f16, 2, 1, f); fmtTag = f16;
            std::fread(&f16, 2, 1, f); channels = f16;
            std::fread(&f32, 4, 1, f); sampleRate = f32;
            std::fseek(f, 6, SEEK_CUR); // byteRate + blockAlign
            std::fread(&f16, 2, 1, f); bitsPerSample = f16;
            // skip any extra fmt bytes
            if (chunkSize > 16) std::fseek(f, chunkSize - 16, SEEK_CUR);
        } else if (std::memcmp(chunkId, "data", 4) == 0) {
            dataBytes = chunkSize;
            dataPos = std::ftell(f);
            std::fseek(f, chunkSize, SEEK_CUR);
        } else {
            std::fseek(f, chunkSize, SEEK_CUR);
        }
    }

    if (dataPos < 0)              return fail("no data chunk");
    if (fmtTag != 1)              return fail("non-PCM wav");
    if (channels != 1)            return fail("not mono");
    if (sampleRate != 16000)      return fail("not 16 kHz");
    if (bitsPerSample != 16)      return fail("not 16-bit");

    std::fseek(f, dataPos, SEEK_SET);
    const size_t n = dataBytes / sizeof(int16_t);
    std::vector<int16_t> pcm(n);
    if (std::fread(pcm.data(), sizeof(int16_t), n, f) != n)
        return fail("short read");
    std::fclose(f);

    out.samples.resize(n);
    constexpr float kInv = 1.0f / 32768.0f;
    for (size_t i = 0; i < n; ++i) out.samples[i] = float(pcm[i]) * kInv;
    out.ok = true;
    return out;
}

// ---------------------------------------------------------------------------
// JSON building helpers (tiny, no deps).
// ---------------------------------------------------------------------------
void json_escape_into(std::string& out, const std::string& s) {
    out.reserve(out.size() + s.size() + 2);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b";  break;
            case '\f': out += "\\f";  break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += char(c);
                }
        }
    }
}

// ---------------------------------------------------------------------------
// Token-level word grouping.
//
// whisper.cpp emits sub-word tokens with timestamps. We group them into
// whitespace-separated words so the editor and the ASS karaoke renderer can
// highlight the spoken word, not BPE pieces.
// ---------------------------------------------------------------------------
struct WordSpan {
    int64_t t0_ms;
    int64_t t1_ms;
    std::string text;
    float p; // averaged token probability
};

// whisper timestamp units are 10ms; convert to ms.
inline int64_t to_ms(int64_t t) { return t * 10; }

std::vector<WordSpan> tokens_to_words(struct whisper_context* ctx, int seg) {
    std::vector<WordSpan> words;
    const int n = whisper_full_n_tokens(ctx, seg);

    WordSpan cur{ -1, -1, "", 0.f };
    int curCount = 0;

    auto flush = [&]() {
        if (curCount == 0) return;
        if (!cur.text.empty() && cur.text[0] == ' ') cur.text.erase(0, 1);
        if (!cur.text.empty()) {
            cur.p /= float(curCount);
            words.push_back(cur);
        }
        cur = { -1, -1, "", 0.f };
        curCount = 0;
    };

    for (int i = 0; i < n; ++i) {
        whisper_token_data td = whisper_full_get_token_data(ctx, seg, i);
        const char* tstr = whisper_full_get_token_text(ctx, seg, i);
        if (!tstr) continue;
        // Skip special tokens like [_BEG_], [_TT_*], <|...|>
        if (tstr[0] == '[' || tstr[0] == '<') continue;
        if (td.id >= whisper_token_eot(ctx)) continue;

        std::string piece(tstr);
        // A leading space (or newline) means "start of new word" in BPE.
        const bool starts_new_word = !piece.empty() && (piece.front() == ' ' || piece.front() == '\n');

        if (starts_new_word) {
            flush();
            cur.t0_ms = to_ms(td.t0);
        } else if (curCount == 0) {
            cur.t0_ms = to_ms(td.t0);
        }
        cur.t1_ms = to_ms(td.t1);
        cur.text  += piece;
        cur.p     += td.p;
        curCount  += 1;
    }
    flush();
    return words;
}

// ---------------------------------------------------------------------------
// Context wrapper. We allocate a small struct so the Java handle is opaque
// and we control destruction.
// ---------------------------------------------------------------------------
struct CtxHolder {
    whisper_context* ctx = nullptr;
};

inline CtxHolder* fromHandle(jlong h) {
    return reinterpret_cast<CtxHolder*>(static_cast<uintptr_t>(h));
}

inline jlong toHandle(CtxHolder* p) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(p));
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

// ===========================================================================
// JNI entry points
// ===========================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_charlesh_captionburn_data_transcription_WhisperJni_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring jModelPath) {
    const std::string path = jstr(env, jModelPath);
    LOGI("nativeInit: %s", path.c_str());

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU only on Android — predictable, no driver risk

    whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (!ctx) {
        LOGE("whisper_init_from_file failed");
        return 0;
    }
    auto* holder = new CtxHolder{ ctx };
    return toHandle(holder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_charlesh_captionburn_data_transcription_WhisperJni_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* holder = fromHandle(handle);
    if (!holder) return;
    if (holder->ctx) whisper_free(holder->ctx);
    delete holder;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_charlesh_captionburn_data_transcription_WhisperJni_nativeTranscribe(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jstring jWavPath, jstring jLanguageHint, jint jThreads) {
    auto* holder = fromHandle(handle);
    if (!holder || !holder->ctx) {
        return env->NewStringUTF(R"({"error":"not_initialized"})");
    }

    const std::string wav  = jstr(env, jWavPath);
    const std::string hint = jstr(env, jLanguageHint);

    auto pcm = load_wav_pcm16_mono_16k(wav);
    if (!pcm.ok) {
        std::string err = std::string(R"({"error":"wav_load_failed: )") + pcm.error + "\"}";
        return env->NewStringUTF(err.c_str());
    }
    float peak = 0.f;
    for (auto s : pcm.samples) { float a = s < 0 ? -s : s; if (a > peak) peak = a; }
    LOGI("wav loaded: %zu samples, peak=%.4f", pcm.samples.size(), peak);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime    = false;
    p.print_progress    = false;
    p.print_timestamps  = false;
    p.print_special     = false;
    p.translate         = false;
    p.token_timestamps  = true;
    p.split_on_word     = true;
    p.single_segment    = false;
    p.suppress_blank    = false;
    p.detect_language   = hint.empty();
    p.language          = hint.empty() ? nullptr : hint.c_str();

    int t = jThreads;
    if (t <= 0) t = (int)std::thread::hardware_concurrency();
    if (t <= 0) t = 4;
    if (t > 8)  t = 8; // diminishing returns past 8 on phones
    p.n_threads = t;

    LOGI("transcribe: %zu samples, threads=%d, hint='%s'",
         pcm.samples.size(), p.n_threads, hint.c_str());

    p.new_segment_callback = [](whisper_context * ctx, whisper_state * state, int n_new, void * user_data) {
        const int seg = whisper_full_n_segments(ctx) - 1;
        if (seg < 0) return;
        const char * text = whisper_full_get_segment_text(ctx, seg);
        LOGI("new_segment[%d]: '%s'", seg, text ? text : "(null)");
    };

    if (whisper_full(holder->ctx, p, pcm.samples.data(), (int)pcm.samples.size()) != 0) {
        LOGE("whisper_full returned non-zero");
        return env->NewStringUTF(R"({"error":"whisper_full_failed"})");
    }

    int n_seg = whisper_full_n_segments(holder->ctx);
    const char* lang = whisper_lang_str(whisper_full_lang_id(holder->ctx));
    LOGI("whisper_full done: n_segments=%d, lang=%s", n_seg, lang ? lang : "null");
    if (n_seg > 0) {
        const int n_tok_0 = whisper_full_n_tokens(holder->ctx, 0);
        LOGI("segment 0: %d tokens, text='%s'",
             n_tok_0,
             whisper_full_get_segment_text(holder->ctx, 0) ?: "(null)");
    } else {
        LOGW("whisper returned 0 segments — retrying with beam search");
        whisper_reset_timings(holder->ctx);
        whisper_full_params p2 = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
        p2.print_realtime    = false;
        p2.print_progress    = false;
        p2.print_timestamps  = false;
        p2.print_special     = false;
        p2.token_timestamps  = true;
        p2.split_on_word     = true;
        p2.single_segment    = false;
        p2.suppress_blank    = false;
        p2.detect_language   = false;
        p2.language          = "en";
        p2.n_threads         = t;
        p2.beam_search.beam_size = 5;
        p2.new_segment_callback = [](whisper_context * ctx2, whisper_state *, int, void *) {
            const int s = whisper_full_n_segments(ctx2) - 1;
            if (s < 0) return;
            LOGI("beam_segment[%d]: '%s'", s, whisper_full_get_segment_text(ctx2, s) ?: "(null)");
        };

        if (whisper_full(holder->ctx, p2, pcm.samples.data(), (int)pcm.samples.size()) != 0) {
            LOGW("beam search also failed");
        }
        n_seg = whisper_full_n_segments(holder->ctx);
        lang = whisper_lang_str(whisper_full_lang_id(holder->ctx));
        LOGI("beam search done: n_segments=%d, lang=%s", n_seg, lang ? lang : "null");
    }

    std::string out;
    out.reserve(8192);
    out += "{\"language\":\"";
    if (lang) out += lang;
    out += "\",\"segments\":[";

    for (int i = 0; i < n_seg; ++i) {
        if (i) out += ",";
        const int64_t t0 = to_ms(whisper_full_get_segment_t0(holder->ctx, i));
        const int64_t t1 = to_ms(whisper_full_get_segment_t1(holder->ctx, i));
        const char* segText = whisper_full_get_segment_text(holder->ctx, i);

        out += "{\"id\":";
        out += std::to_string(i);
        out += ",\"start_ms\":";
        out += std::to_string(t0);
        out += ",\"end_ms\":";
        out += std::to_string(t1);
        out += ",\"text\":\"";
        json_escape_into(out, segText ? std::string(segText) : std::string());
        out += "\",\"words\":[";

        const auto words = tokens_to_words(holder->ctx, i);
        for (size_t w = 0; w < words.size(); ++w) {
            if (w) out += ",";
            const auto& wd = words[w];
            char buf[64];
            out += "{\"start_ms\":";
            std::snprintf(buf, sizeof(buf), "%lld", (long long)wd.t0_ms); out += buf;
            out += ",\"end_ms\":";
            std::snprintf(buf, sizeof(buf), "%lld", (long long)wd.t1_ms); out += buf;
            out += ",\"text\":\"";
            json_escape_into(out, wd.text);
            out += "\",\"p\":";
            std::snprintf(buf, sizeof(buf), "%.4f", wd.p); out += buf;
            out += "}";
        }
        out += "]}";
    }
    out += "]}";

    return env->NewStringUTF(out.c_str());
}
