# Keyword-spotting experiment — not shipped

Investigated sherpa-onnx v1.12.17 with the English GigaSpeech 3.3M model for the proposed Mira name, then Cue. The shipped app instead uses Android's explicitly on-device SpeechRecognizer, with partial-result wake matching, single-session restart scheduling, and microphone handoff. No native runtime or KWS weights are packaged in the production APK.

Observed in this development session:

- The mobile KWS model variant aborted on the phone with an ONNX reshape error. The standard model ran without that error.
- The user reported that the Mira keyword did not open the panel.
- Offline synthetic speech tests using host sherpa-onnx 1.12.26 and FFmpeg/libflite detected a control “Hey Siri” sample, but failed the tested Mira and Cue spellings. This is a rejected candidate, not a successful latency/accuracy benchmark.
- Cue's Android on-device recognition path still needs real speech, background, noise, and battery validation. It is marked experimental in Settings.

The checksum-pinned preparation script writes the standard model and runtime to this research directory only:

```sh
python3 scripts/prepare_wake.py
```

Kotlin API extracts are from https://github.com/k2-fsa/sherpa-onnx/tree/v1.12.17/sherpa-onnx/kotlin-api and retain the upstream package for JNI compatibility. See `../../third_party_notices/SHERPA_ONNX_LICENSE`. The model README identifies the model as Apache-2.0; runtime and model binaries stay ignored by Git.
