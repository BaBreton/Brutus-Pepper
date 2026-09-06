"""Réglages persistés, chiffrés au repos. Les secrets ne sortent jamais par l'API."""
import copy
import json
import threading
from pathlib import Path

from cryptography.fernet import Fernet

from brain.storage import atomic_write

DEFAULTS = {
    "llm": {"active": "", "model": "", "credentials": {}},
    # Base est le compromis de livraison : nettement plus réactif sur CPU. Les
    # noms propres peuvent justifier le passage à small dans la webapp.
    "stt": {"active": "whisper-local", "model": "base", "credentials": {}},
    "image_search": {"provider": "", "contact": "", "credentials": {}},
    # `greeting` n'est jamais saisi : il est rédigé à l'enregistrement (voir
    # brain/greeting.py) et rangé ici pour que la tablette l'obtienne sans attendre.
    "hospitality": {"active": False, "company": "", "visitors": [], "notes": "",
                    "mission": "", "places": [], "greeting": "", "active_visit": None},
}

# Clés considérées comme secrètes : masquées dans public(), préservées si envoyées vides,
# où qu'elles apparaissent dans l'arbre des réglages (pas seulement dans "credentials").
SECRET_KEYS = ("api_key", "secret_key", "access_key", "token")


class SettingsStore:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.key_path = self.root / "settings.key"
        self.settings_path = self.root / "settings.enc"
        self.root.mkdir(parents=True, exist_ok=True)
        if not self.key_path.exists():
            atomic_write(self.key_path, Fernet.generate_key())
        self.fernet = Fernet(self.key_path.read_bytes())
        # Sérialise load→fusion→save : FastAPI exécute les endpoints sync dans un
        # threadpool, deux sauvegardes simultanées sans verrou s'écrasent réellement.
        self._lock = threading.Lock()

    def load(self) -> dict:
        merged = copy.deepcopy(DEFAULTS)
        if not self.settings_path.exists():
            return merged
        payload = self.fernet.decrypt(self.settings_path.read_bytes())
        stored = json.loads(payload.decode("utf-8"))
        for section, values in stored.items():
            if section in merged and isinstance(values, dict):
                merged[section].update(values)
            else:
                # Section inconnue (ou de forme inattendue) : on la conserve telle
                # quelle plutôt que de la faire disparaître à la prochaine sauvegarde.
                merged[section] = values
        return merged

    def save(self, settings: dict) -> None:
        payload = json.dumps(settings, ensure_ascii=False, sort_keys=True).encode("utf-8")
        atomic_write(self.settings_path, self.fernet.encrypt(payload))

    def update_section(self, section: str, changes: dict) -> None:
        """Fusionne `changes` dans `section`. Une valeur secrète vide ou nulle préserve
        l'existante."""
        if section not in DEFAULTS:
            raise ValueError("section inconnue: %s" % section)
        with self._lock:
            settings = self.load()
            target = settings[section]
            for key, value in changes.items():
                if key == "credentials" and isinstance(value, dict):
                    existing = dict(target.get("credentials", {}))
                    for connector, fields in value.items():
                        merged_fields = dict(existing.get(connector, {}))
                        for field, field_value in fields.items():
                            if field in SECRET_KEYS:
                                text = "" if field_value is None else str(field_value)
                                if not text.strip():
                                    continue  # vide/nul → on garde le secret déjà enregistré
                                merged_fields[field] = text
                            else:
                                # Champs non secrets : conservés tels quels, sans coercion.
                                merged_fields[field] = field_value
                        existing[connector] = merged_fields
                    target["credentials"] = existing
                else:
                    target[key] = value
            self.save(settings)

    def delete_credential(self, section: str, connector: str) -> None:
        """Supprime tous les identifiants d'un connecteur (révocation, retrait produit)."""
        if section not in DEFAULTS:
            raise ValueError("section inconnue: %s" % section)
        with self._lock:
            settings = self.load()
            credentials = settings[section].get("credentials", {})
            if connector in credentials:
                del credentials[connector]
                self.save(settings)

    def public(self) -> dict:
        """Vue sans secret, destinée à la webapp. Masque récursivement TOUT champ dont
        le nom est dans SECRET_KEYS, où qu'il soit dans l'arbre : ajouter une section ou
        un champ plus tard ne peut donc pas silencieusement créer une fuite."""
        return self._mask(self.load())

    @classmethod
    def _mask(cls, node):
        if isinstance(node, dict):
            return {
                key: cls._describe_secret(value) if key in SECRET_KEYS else cls._mask(value)
                for key, value in node.items()
            }
        if isinstance(node, list):
            return [cls._mask(item) for item in node]
        return node

    @staticmethod
    def _describe_secret(value) -> dict:
        text = "" if value is None else str(value).strip()
        return {
            "configured": bool(text),
            "masked": ("…" + text[-4:]) if len(text) >= 4 else "",
        }
