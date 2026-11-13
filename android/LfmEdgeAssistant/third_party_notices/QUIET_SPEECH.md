# Quiet speech components

- Vosk Android 0.3.75: Alpha Cephei Inc.; Apache License 2.0. Source: https://github.com/alphacep/vosk-api
- vosk-model-small-en-us-0.15: Copyright 2020 Alpha Cephei Inc.; Apache License 2.0 as published at https://alphacephei.com/vosk/models. The archive SHA-256 and official download URL are pinned in `scripts/prepare_speech.py`. Its README is packaged with the model.
- Java Native Access 5.18.1: JNA project contributors; dual Apache License 2.0 / LGPL 2.1-or-later. Used here under Apache License 2.0. Source and notices: https://github.com/java-native-access/jna/tree/5.18.1
- Speech fixture WAVs are generated test utterances with FFmpeg's Flite filter using the AWB, RMS and SLT English voices. They contain no user recordings and are packaged only in the instrumentation test APK. Flite/CMU voice project: http://www.festvox.org/flite/

Runtime libraries come from pinned Maven coordinates; model weights are reproducibly fetched and ignored by Git. See VOSK_LICENSE and JNA_LICENSE for upstream license text/notices.
