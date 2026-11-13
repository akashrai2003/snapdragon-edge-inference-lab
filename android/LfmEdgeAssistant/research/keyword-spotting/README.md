# Keyword-spotting experiment — not shipped

Investigated sherpa-onnx v1.12.17 with the English GigaSpeech 3.3M model for the proposed Mira name, then Cue. Cue 3.2 used Android's on-device SpeechRecognizer as an interim path. That path was retired in 3.3 after repeated session restarts caused system tones. The current app uses continuous AudioRecord and a pinned Vosk English decoder. See the app README and `design/ANDROID_RELIABILITY_REVIEW.md`; this sherpa experiment is still not shipped.

Observed in this development session:

- The mobile KWS model variant aborted on the phone with an ONNX reshape error. The standard model ran without that error.
- The user reported that the Mira keyword did not open the panel.
- Offline synthetic speech tests using host sherpa-onnx 1.12.26 and FFmpeg/libflite detected a control “Hey Siri” sample, but failed the tested Mira and Cue spellings. This is a rejected candidate, not a successful latency/accuracy benchmark.
- The current quiet recognition path still needs broader real speech, background, noise, and battery validation. It is marked experimental in Settings.

The checksum-pinned preparation script writes the standard model and runtime to this research directory only:

```sh
python3 scripts/prepare_wake.py
```

Kotlin API extracts are from https://github.com/k2-fsa/sherpa-onnx/tree/v1.12.17/sherpa-onnx/kotlin-api and retain the upstream package for JNI compatibility. See `../../third_party_notices/SHERPA_ONNX_LICENSE`. The model README identifies the model as Apache-2.0; runtime and model binaries stay ignored by Git.
