package com.charlesh.captionburn.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * Heuristic for whether the current phone is genuinely under-powered for the
 * on-device AI pipeline (CPU-based Whisper transcription + FFmpeg burn-in — there
 * is no GPU/NNAPI acceleration in this build).
 *
 * Deliberately conservative: it should only return true for clearly weak hardware
 * so the warning stays rare and meaningful.
 */
object DeviceCapability {

    fun isLowEndDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lowRam = am.isLowRamDevice
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalRamGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val cores = Runtime.getRuntime().availableProcessors()
        // A device with no 64-bit ABI is an old, slow 32-bit-only SoC.
        val has64Bit = Build.SUPPORTED_64_BIT_ABIS?.isNotEmpty() == true

        val lowEnd = when {
            lowRam -> true
            !has64Bit -> true
            totalRamGb < 3.2 -> true                 // ~3 GB RAM or less
            cores <= 4 && totalRamGb < 4.2 -> true    // few cores + modest RAM
            else -> false
        }

        Timber.d(
            "DeviceCapability: lowRam=%b has64Bit=%b ramGb=%.1f cores=%d -> lowEnd=%b",
            lowRam, has64Bit, totalRamGb, cores, lowEnd,
        )
        return lowEnd
    }
}
