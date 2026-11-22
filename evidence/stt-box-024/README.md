# 0.2.4 built-in speech-to-text: box transcription evidence

2026-11-19 (America/Toronto, EST). Box only, no network at run time.

Engine: Vosk (Kaldi) + `vosk-model-small-en-us-0.15` (the same model files the
APK bundles in assets). Harness: `voskbox` (desktop Vosk 0.3.45 JNI) feeding
100 ms frames through **core's `UtteranceLoop` + `Endpointer`**, the same
endpointing code the phone uses. Each clip gets 500 ms of silence before it
(the first 300 ms are the lead-in the phone ignores) and 3 s after it.

Audio: `espeak-ng -v en-us` (robotic synthetic voice), resampled to 16 kHz
mono s16 with ffmpeg. s5 = s4 + 0.7 s pause + s2 (pause must not cut the
utterance). s6 = s1 mixed with pink noise. s7/s8 = espeak at 125 wpm.

| clip | said | transcript | endpoint |
|---|---|---|---|
| s1 | What is the weather like in Toronto today | what is the weather like in toronto the day | End 1.1 s after speech |
| s2 | Turn on the living room lights | turn on the living room lights | End 1.2 s after |
| s3 | Ask agent zero to summarize my unread email (150 wpm) | sk john to the ropes are summarized my on read the email | End 1.1 s after |
| s4 | Set a timer for ten minutes (150 wpm) | tibor for ten minutes | End 1.5 s after |
| s5 | s4, 0.7 s pause, s2 | tibor for ten minutes turn on the living room lights | one utterance, End 1.1 s after |
| s6 | s1 + pink noise | what is the weather like in toronto today | End 1.6 s after |
| s7 | s3 at 125 wpm | ask a joint zero to summarize my on read email | End 1.5 s after |
| s8 | s4 at 125 wpm | set a timer for ten minutes | End 0.6 s after* |

\* measured from the end of the WAV, which includes espeak's own trailing silence.

Model load on the box: ~0.3-0.4 s. Processing: ~0.7-1.2 s per clip on the box
CPU (faster than real time). Full output with partials: `box-transcripts.txt`.

Reproduce:
```
./gradlew :voskbox:installDist
voskbox/build/install/voskbox/bin/voskbox app/src/main/assets/vosk-model-small-en-us-0.15 evidence/stt-box-024/s2.wav
```

Not shown here: a real human voice through the Pixel 6a mic, on-device speed
on Tensor G1, and how the 6a's AEC/noise suppressor shape the audio.
