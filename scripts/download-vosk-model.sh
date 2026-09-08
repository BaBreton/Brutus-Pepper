#!/usr/bin/env bash
set -euo pipefail

MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
TARGET_DIR="app/src/main/assets/model-fr"

if [ -d "$TARGET_DIR" ]; then
    echo "Vosk model already downloaded at $TARGET_DIR"
    exit 0
fi

echo "Downloading Vosk French model (42 MB)..."
curl -L -o model.zip "$MODEL_URL"

echo "Extracting model..."
unzip -q model.zip

echo "Moving model to assets..."
mkdir -p app/src/main/assets
mv vosk-model-small-fr-0.22 "$TARGET_DIR"

# StorageService.sync() de Vosk lit « uuid » dans les assets pour savoir si le
# modele deja deballe sur la tablette est a jour. L'archive d'alphacephei n'en
# contient pas : sans ce fichier, le deballage echoue avec
# « FileNotFoundException: model-fr/uuid » et le mot d'eveil reste indisponible.
if [ ! -f "$TARGET_DIR/uuid" ]; then
    echo "Writing model uuid..."
    printf '%s' "vosk-model-small-fr-0.22" > "$TARGET_DIR/uuid"
fi

echo "Cleaning up..."
rm model.zip

echo "Vosk French model installed successfully at $TARGET_DIR"
