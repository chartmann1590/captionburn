Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.Rate = 0
$synth.Volume = 100
$synth.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::Female)
$text = "Meet CaptionBurn. Import any video, generate on-device captions with Whisper, and translate subtitles in seconds. Customize style with size, outline, placement, and word highlights. Preview and edit transcript lines before export. Then burn captions directly into your video and share instantly. CaptionBurn keeps your workflow fast, private, and mobile."
$synth.SetOutputToWaveFile("marketing/play-store/captionburn-voiceover.wav")
$synth.Speak($text)
$synth.Dispose()
