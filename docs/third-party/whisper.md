# Whisper

- Runtime: `whisper.cpp` / `faster-whisper` on the antenna
- Model: provisioned separately on the antenna; no speech model is bundled in the APK
- Source: `https://github.com/ggerganov/whisper.cpp`
- License: MIT; see the upstream project and its model terms

The application sends audio to the configured antenna. The local model and the
language setting are selected on the server; cloud transcription remains optional.
