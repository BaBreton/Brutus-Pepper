"""Chemins et variables d'environnement. Tout est surchargeable pour l'installation client."""
import os
from pathlib import Path

DATA_ROOT = Path(os.environ.get("BRAIN_DATA", "/data"))
MODELS_ROOT = Path(os.environ.get("BRAIN_MODELS", "/models"))
HOST = os.environ.get("BRAIN_HOST", "0.0.0.0")
PORT = int(os.environ.get("BRAIN_PORT", "8770"))
LANGUAGE = os.environ.get("BRAIN_LANGUAGE", "fr")

# Charge le modèle Whisper au démarrage, en tâche de fond. Sans cela, le
# téléchargement du modèle (environ 150 Mo pour `base`, davantage pour `small`)
# tombe sur le premier visiteur qui parle au robot. Mettre à "0" pour démarrer sans
# toucher au réseau.
PRELOAD_STT = os.environ.get("BRAIN_PRELOAD_STT", "1") != "0"

# Contact publié dans le User-Agent des recherches d'images. Wikimedia bride les
# clients non identifiables : sans cette valeur, les requêtes finissent en 429.
IMAGE_CONTACT = os.environ.get("BRAIN_IMAGE_CONTACT", "")
