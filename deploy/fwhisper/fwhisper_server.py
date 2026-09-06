"""
fwhisper_server.py — Brutus Pepper faster-whisper HTTP service
Port: 8766
Matches the whisper.cpp HTTP contract so the Android client works unchanged.

Model mapping:
  "small"          -> "small"           (faster-whisper built-in)
  "medium"         -> "medium"          (faster-whisper built-in)
  "large-v3-turbo" -> "large-v3-turbo"  (faster-whisper 1.2.1 built-in — verified on this host)
  Fallback alias:    "turbo"            (also built-in in 1.2.1, alias for large-v3-turbo)

All models are loaded with device="cpu" and compute_type="int8" for a CPU-only installation.
"""

import os
import tempfile
import threading
import logging

from flask import Flask, request, jsonify
from faster_whisper import WhisperModel

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Model cache — lazy-loaded, thread-safe
# ---------------------------------------------------------------------------
MODELS: dict[str, WhisperModel] = {}
_model_lock = threading.Lock()

# Map request "model" param to the actual faster-whisper model id.
# large-v3-turbo is a first-class id in faster-whisper >= 1.1.0.
MODEL_MAP = {
    "small": "small",
    "medium": "medium",
    "large-v3-turbo": "large-v3-turbo",  # built-in in faster-whisper 1.2.1
}
DEFAULT_MODEL = "medium"
DEFAULT_LANGUAGE = "fr"
DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8766


def get_model(model_id: str) -> WhisperModel:
    """Return a cached WhisperModel, loading it on first access (thread-safe)."""
    with _model_lock:
        if model_id not in MODELS:
            log.info("Loading model '%s' (cpu, int8) — first time, may take a while …", model_id)
            MODELS[model_id] = WhisperModel(model_id, device="cpu", compute_type="int8")
            log.info("Model '%s' loaded and cached.", model_id)
        return MODELS[model_id]


# ---------------------------------------------------------------------------
# Flask app
# ---------------------------------------------------------------------------
app = Flask(__name__)


@app.get("/")
def health():
    return jsonify({"status": "ok"})


@app.post("/inference")
def inference():
    # --- resolve model name ---
    raw_model = request.form.get("model", DEFAULT_MODEL).strip()
    model_id = MODEL_MAP.get(raw_model, DEFAULT_MODEL)
    language = (request.form.get("language") or DEFAULT_LANGUAGE).strip() or DEFAULT_LANGUAGE

    # --- validate file ---
    audio_file = request.files.get("file")
    if audio_file is None:
        return jsonify({"error": "missing 'file' field"}), 400

    # Write to temp file (faster-whisper reads from path or bytes)
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_path = tmp.name
            audio_file.save(tmp_path)
    except Exception as exc:
        log.exception("Failed to save uploaded file")
        return jsonify({"error": f"file save error: {exc}"}), 500

    # --- transcribe ---
    try:
        model = get_model(model_id)
        log.info("Transcribing with model='%s' language='%s' beam_size=1", model_id, language)
        segments, _info = model.transcribe(tmp_path, language=language, beam_size=1)
        text = " ".join(seg.text for seg in segments).strip()
        log.info("Result: %r", text)
        return jsonify({"text": text})
    except Exception as exc:
        log.exception("Transcription error")
        return jsonify({"error": str(exc)}), 500
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    host = os.environ.get("FWHISPER_HOST", DEFAULT_HOST)
    port = int(os.environ.get("FWHISPER_PORT", str(DEFAULT_PORT)))
    log.info("Starting brutus-pepper-fwhisper on %s:%s", host, port)
    # threaded=True so concurrent requests don't serialize on the GIL-free C extension
    app.run(host=host, port=port, threaded=True)
