"""Médiathèque du client.

Portée depuis l'ancien control plane sans changement de logique — elle ne dépend
d'aucun framework web. Les images et vidéos déposées ici
sont celles que Pepper peut afficher sur sa tablette quand on le lui demande.
"""
import io
import json
import os
import re
import subprocess
import unicodedata
import uuid
from pathlib import Path

from brain.storage import atomic_write

MAX_UPLOAD_BYTES = 500 * 1024 * 1024
MAX_IMAGE_BYTES = 25 * 1024 * 1024
DEFAULT_LIBRARY_QUOTA = 4 * 1024 * 1024 * 1024
ALLOWED_IMAGE_MIMES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}
VIDEO_MIME = "video/mp4"


def normalize_name(value: str) -> str:
    ascii_value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]+", "-", ascii_value.lower()).strip("-")


class MediaLibrary:
    def __init__(self, root, quota_bytes: int = DEFAULT_LIBRARY_QUOTA):
        self.root = Path(root)
        self.media_dir = self.root / "media"
        self.catalog_path = self.root / "catalog.json"
        self.quota_bytes = quota_bytes
        self.media_dir.mkdir(parents=True, exist_ok=True)
        if not self.catalog_path.exists():
            atomic_write(self.catalog_path, b"[]")

    def list(self) -> list:
        try:
            value = json.loads(self.catalog_path.read_text("utf-8"))
            return value if isinstance(value, list) else []
        except (OSError, ValueError):
            return []

    def add_bytes(self, name: str, content_type: str, data: bytes) -> dict:
        return self.add_stream(name, content_type, io.BytesIO(data), len(data))

    def add_stream(self, name, content_type, stream, length: int) -> dict:
        display_name = str(name).strip()
        slug = normalize_name(display_name)
        if not slug or len(display_name) > 120:
            raise ValueError("nom média invalide")
        catalog = self.list()
        if any(item["slug"] == slug for item in catalog):
            raise ValueError("ce nom existe déjà")
        if length <= 0 or length > MAX_UPLOAD_BYTES:
            raise ValueError("taille upload invalide")
        used = sum(int(item.get("size", 0)) for item in catalog)
        if used + length > self.quota_bytes:
            raise ValueError("quota médiathèque dépassé")

        suffix, kind = self._type_details(content_type)
        if kind == "image" and length > MAX_IMAGE_BYTES:
            raise ValueError("une image est limitée à 25 Mo")
        media_id = uuid.uuid4().hex[:16]
        filename = "%s-%s%s" % (slug[:80], media_id, suffix)
        target = self.media_dir / filename
        temporary = target.with_suffix(target.suffix + ".upload")
        written = 0
        try:
            with temporary.open("wb") as output:
                while written < length:
                    chunk = stream.read(min(1024 * 1024, length - written))
                    if not chunk:
                        break
                    output.write(chunk)
                    written += len(chunk)
            if written != length:
                raise ValueError("upload incomplet")
            self._validate_file(temporary, content_type)
            os.replace(temporary, target)
            item = {
                "id": media_id,
                "name": display_name,
                "slug": slug,
                "kind": kind,
                "mime": content_type,
                "filename": filename,
                "size": length,
                "url": "/api/robot/media/%s/content" % media_id,
            }
            catalog.append(item)
            self._save_catalog(catalog)
            return item
        except Exception:
            temporary.unlink(missing_ok=True)
            target.unlink(missing_ok=True)
            raise

    def delete(self, media_id: str) -> None:
        catalog = self.list()
        item = next((entry for entry in catalog if entry["id"] == media_id), None)
        if not item:
            raise LookupError("média introuvable")
        (self.media_dir / item["filename"]).unlink(missing_ok=True)
        self._save_catalog([entry for entry in catalog if entry["id"] != media_id])

    def resolve(self, query: str) -> dict:
        """Résout un nom prononcé vers un média. Une correspondance ambiguë est
        refusée plutôt que devinée : mieux vaut ne rien afficher que le mauvais
        média devant un visiteur."""
        slug = normalize_name(query)
        if not slug:
            raise LookupError("média introuvable")
        catalog = self.list()
        exact = [entry for entry in catalog if entry["slug"] == slug]
        if len(exact) == 1:
            return exact[0]
        query_tokens = set(slug.split("-"))
        scored = []
        for entry in catalog:
            tokens = set(entry["slug"].split("-"))
            overlap = len(tokens & query_tokens)
            if overlap:
                scored.append((overlap / max(len(query_tokens), 1), overlap, entry))
        if not scored:
            raise LookupError("média introuvable")
        scored.sort(key=lambda value: (value[0], value[1]), reverse=True)
        best = scored[0]
        if len(scored) > 1 and scored[1][:2] == best[:2]:
            raise LookupError("nom média ambigu")
        return best[2]

    def content(self, media_id: str):
        item = next((entry for entry in self.list() if entry["id"] == media_id), None)
        if not item:
            raise LookupError("média introuvable")
        path = (self.media_dir / item["filename"]).resolve()
        if self.media_dir.resolve() not in path.parents:
            raise ValueError("chemin média invalide")
        return item, path

    def _save_catalog(self, catalog: list) -> None:
        atomic_write(
            self.catalog_path,
            json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8"),
        )

    def _type_details(self, content_type: str):
        if content_type in ALLOWED_IMAGE_MIMES:
            return ALLOWED_IMAGE_MIMES[content_type], "image"
        if content_type == VIDEO_MIME:
            return ".mp4", "video"
        raise ValueError("type média non supporté")

    def _validate_file(self, path: Path, content_type: str) -> None:
        """Vérifie que le contenu correspond au type annoncé : un client peut
        téléverser n'importe quoi en le déclarant image/png."""
        with path.open("rb") as stream:
            header = stream.read(32)
        if content_type == "image/png" and not header.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("PNG invalide")
        if content_type == "image/jpeg" and not header.startswith(b"\xff\xd8\xff"):
            raise ValueError("JPEG invalide")
        if content_type == "image/webp" and not (header.startswith(b"RIFF") and header[8:12] == b"WEBP"):
            raise ValueError("WebP invalide")
        if content_type == VIDEO_MIME:
            result = subprocess.run(
                ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                 "stream=codec_name", "-of", "json", str(path)],
                capture_output=True, text=True, timeout=20,
            )
            try:
                streams = json.loads(result.stdout).get("streams", [])
            except ValueError:
                streams = []
            if result.returncode != 0 or not streams or streams[0].get("codec_name") != "h264":
                raise ValueError("la vidéo doit être un MP4 H.264")
