# Cue voice: delivered behavior and next evaluation

## Delivered

Voice requests can use quick non-thinking responses, conversational prose, and sentence-by-sentence TTS. Read-only remains available. Continued conversation shows a visible End control, waits for speech playback to drain, and then offers a bounded follow-up window. Default interruption during an answer requires “Hey Cue”; open interruption is an opt-in. Foreground recognition ends when Cue is dismissed or paused.

This is still a recognizer → text model → speech-engine pipeline. Markdown cleanup fixes spoken symbols; natural phrasing and the installed offline voice improve delivery. These changes do not establish Siri/Google-level voice quality, acoustic echo cancellation, speaker identification, or noise robustness. Ordinary nearby speech can still be picked up during the follow-up window. In busy places, disable continued conversation and use one spoken turn at a time.

## Audio / omni candidates

An audio-native model is a reasonable next experiment for richer speech and an integrated audio/text stream. It must be compared on the actual phone before replacing the working text/vision path.

| Candidate | Published capability | Evaluation purpose |
| --- | --- | --- |
| Current VL model + offline Android TTS | Existing installed chat/vision and working playback | Reliability, latency, and voice-quality baseline |
| [LFM3-Audio-2B](https://huggingface.co/LiquidAI/LFM3-Audio-2B) | Streaming text/speech generation; audio input/output; local GGUF and ONNX variants | Voice-only candidate while retaining the existing vision route |
| [LFM3-Omni-2B](https://huggingface.co/LiquidAI/LFM3-Omni-2B) | About 2.2B parameters; image/video/text/audio input and text/speech output; GGUF and realtime speech-server support | Primary candidate for a combined photo-and-voice assistant |
| [MiniCPM-o 4.5](https://github.com/OpenBMB/MiniCPM-V/blob/main/docs/minicpm_o4dot5_en.md) | About 9B parameters with full-duplex streaming and speech synthesis | Heavier comparison; its published demos target substantially larger compute than this phone, so suitability is unproven |

These are publisher-described capabilities, not results from this device. A model's audio support alone does not establish simultaneous listening/speaking, echo suppression, or reliable interruption on Android. No audio/omni checkpoint has been installed or benchmarked as part of this update.

## Concrete next experiment

1. Pin one official LFM3-Omni GGUF release and its required vision/audio encoders and codec; verify the installed llama.cpp revision supports the realtime audio path. Keep this in a separate research directory/port with the current service available for rollback.
2. First validate incremental text + playable audio on the host and phone. Verify exact sample rates, codec buffering, cancellation, and read-only fallback before changing the app transport.
3. Compare to the current path using fixed benign questions, a photo question, and a follow-up. Separate warmup from samples and randomize/alternate model configurations. Record first audible response, final latency, realtime audio factor, memory, and audio underruns. Keep the phone's thermal abort at 40–42°C or thermal status >=3; do not change governors/protections.
4. Test quiet room, music/TV, another speaker, headphones, Bluetooth, screen off, calls, and repeated interrupted turns. Measure missed/false wakes and interruptions, clipped first words, self-echo loops, and microphone release. Add acoustic echo control and speaker enrollment only when supported and independently validated.
5. Select on measured quality, battery, and latency. Preserve typed chat, vision, Read-only, End, and immediate interruption controls with the existing pipeline as fallback.

See [CUE_VALIDATION.md](CUE_VALIDATION.md) for actual checks and clearly labeled single-event latency samples.
