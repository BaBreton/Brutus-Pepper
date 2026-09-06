#!/usr/bin/env bash
# Maintainer tool: current source (including new files) + explicit client launchers.
set -euo pipefail
export LC_ALL=C TZ=UTC
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { printf 'Erreur : %s\n' "$*" >&2; exit 1; }
[[ $# == 1 || ( $# == 2 && $2 == --with-app ) ]] || fail 'Usage : bash install/package-client.sh /chemin/nouveau-pepper-client.zip [--with-app]'
WITH_APP="${2:-}"
for tool in git zip unzip mktemp; do
    command -v "$tool" >/dev/null || fail "Outil manquant : $tool"
done
[[ $1 == *.zip ]] || fail 'Le fichier de sortie doit finir par .zip.'
OUTPUT_DIR="$(cd -- "$(dirname -- "$1")" && pwd)" || fail 'Le dossier de sortie doit déjà exister.'
OUTPUT="$OUTPUT_DIR/$(basename -- "$1")"
[[ ! -e $OUTPUT && ! -L $OUTPUT ]] || fail 'Le ZIP existe déjà. Choisissez un autre nom ; aucun écrasement.'
git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail 'Ce script se lance depuis le dépôt Git de développement.'
STAGE=$(mktemp -d "${TMPDIR:-/tmp}/pepper-package.XXXXXXXX")
cleanup() {
    # Only the exact private directory just allocated by mktemp, never client data.
    if [[ -n ${STAGE:-} && -d $STAGE && ${STAGE##*/} == pepper-package.* ]]; then
        rm -rf -- "$STAGE"
    fi
}
trap cleanup EXIT
mkdir "$STAGE/bundle"
FILES=()
add_file() {
    local relative=$1 source="$ROOT/$1" parent mode=644
    [[ $relative != *$'\n'* && $relative != *$'\r'* ]] || fail 'Nom de fichier non pris en charge (retour à la ligne).'
    [[ -f $source && ! -L $source ]] || fail "Source manquante ou lien symbolique refusé : $relative"
    parent=$(dirname -- "$source")
    while [[ $parent != "$ROOT" && $parent != / ]]; do
        [[ ! -L $parent ]] || fail "Dossier symbolique refusé : $relative"
        parent=$(dirname -- "$parent")
    done
    mkdir -p -- "$STAGE/bundle/$(dirname -- "$relative")"
    cp -- "$source" "$STAGE/bundle/$relative"
    case "$relative" in install/*.sh|install/*.command) mode=755 ;; esac
    chmod "$mode" "$STAGE/bundle/$relative"
    touch -t 200001010000 "$STAGE/bundle/$relative"
    FILES+=("$relative")
}
git -C "$ROOT" ls-files --cached --others --exclude-standard -z -- server/brain > "$STAGE/tracked"
while IFS= read -r -d '' relative; do
    # Deny private/runtime/temp paths even if accidentally tracked. Only source
    # types below can enter the ZIP; .env, keys, tokens and venvs cannot qualify.
    case "/$relative/" in
        */.venv/*|*/venv/*|*/env/*|*/.git/*|*/__pycache__/*|*/data/*|*/models/*|*/keys/*|*/secrets/*|*/tmp/*|*/temp/*|*/.pytest_cache/*) continue ;;
    esac
    case "$relative" in
        server/brain/Dockerfile|server/brain/docker-compose.yml|server/brain/.dockerignore|server/brain/requirements.txt|server/brain/README.md|server/brain/*.py|server/brain/static/*.html|server/brain/static/*.css|server/brain/static/*.js|server/brain/static/*.svg|server/brain/static/*.png|server/brain/static/*.jpg|server/brain/static/*.webp|server/brain/static/*.woff2)
            add_file "$relative" ;;
    esac
done < "$STAGE/tracked"
for required in Dockerfile docker-compose.yml requirements.txt main.py __init__.py static/index.html; do
    [[ -f $STAGE/bundle/server/brain/$required ]] || fail "Source suivie manquante : server/brain/$required"
done
for relative in install/README.md install/compose.env install/network.sh install/pepper.sh install/Pepper.command install/Pepper.ps1 install/Pepper.cmd; do
    add_file "$relative"
done
if [[ -f "$ROOT/docs/PEPPER_CLIENT_GUIDE.md" ]]; then
    add_file "docs/PEPPER_CLIENT_GUIDE.md"
fi
if [[ -f "$ROOT/docs/PEPPER_CLIENT_GUIDE.html" ]]; then
    add_file "docs/PEPPER_CLIENT_GUIDE.html"
fi
if [[ $WITH_APP == --with-app ]]; then
    # Explicit release assets only; never collect build directories or private reports.
    add_file "app/build/outputs/apk/debug/app-debug.apk"
    add_file "docs/PEPPER_CLIENT_GUIDE.pdf"
    add_file "install/INSTALLER_APPLICATION.md"
    for notice in docs/third-party/robot-assets.md docs/third-party/softbank-qisdk-tutorials-BSD-3-Clause.txt; do
        if [[ -f "$ROOT/$notice" ]]; then add_file "$notice"; fi
    done
    mkdir -p "$STAGE/bundle/application"
    mv "$STAGE/bundle/app/build/outputs/apk/debug/app-debug.apk" "$STAGE/bundle/application/Pepper.apk"
    for i in "${!FILES[@]}"; do
        if [[ ${FILES[$i]} == app/build/outputs/apk/debug/app-debug.apk ]]; then
            FILES[$i]="application/Pepper.apk"
        fi
    done
fi
# Stable ordering, file timestamps and permissions; no directory entries or extra
# ZIP attributes. Byte reproducibility requires the same zip implementation.
printf '%s\n' "${FILES[@]}" | sort > "$STAGE/manifest"
(cd "$STAGE/bundle" && zip -X -q "$STAGE/client.zip" -@ < "$STAGE/manifest")
unzip -tq "$STAGE/client.zip" >/dev/null || fail 'Archive invalide.'
# Noclobber also protects a file created after the initial existence check.
(set -C; umask 077; : > "$OUTPUT") || fail 'Le fichier de sortie existe désormais ; aucun écrasement.'
cp -- "$STAGE/client.zip" "$OUTPUT"
printf 'ZIP client créé : %s\n' "$OUTPUT"
printf 'Sources du disque, fichiers nouveaux inclus. Relisez les sources avant livraison : aucun filtre ne détecte tous les secrets intégrés au code.\n'
