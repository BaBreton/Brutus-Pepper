"""Préchargement du modèle de transcription au démarrage.

Le premier chargement d'un modèle Whisper le télécharge s'il est absent du volume —
environ 150 Mo pour `base`, davantage pour `small`. Sans préchargement, cette attente
tombe sur le premier visiteur qui parle au robot. On la déplace au démarrage du
conteneur, en tâche de fond pour ne pas retarder la disponibilité de la webapp.
"""
import logging
import threading

from brain.settings import SettingsStore

LOCAL_WHISPER = "whisper-local"


def warm_stt(settings: SettingsStore, loader=None) -> None:
    """Charge le modèle STT configuré. N'échoue jamais : un serveur client sans accès
    internet doit démarrer quand même, pour que la webapp reste joignable et qu'on
    puisse y choisir un modèle déjà présent."""
    stt_settings = settings.load()["stt"]
    if stt_settings["active"] != LOCAL_WHISPER:
        return  # les connecteurs cloud n'ont rien à précharger

    if loader is None:
        from brain.connectors.stt_whisper_local import WhisperLocalConnector

        loader = WhisperLocalConnector.load

    model = stt_settings["model"]
    try:
        logging.info("préchargement du modèle Whisper « %s »…", model)
        loader(model)
        logging.info("modèle Whisper « %s » prêt", model)
    except Exception:
        logging.exception(
            "préchargement du modèle Whisper « %s » impossible ; le serveur démarre "
            "quand même, la transcription échouera tant que le modèle est absent", model
        )


def warm_stt_in_background(settings: SettingsStore) -> threading.Thread:
    thread = threading.Thread(target=warm_stt, args=(settings,),
                              name="preload-stt", daemon=True)
    thread.start()
    return thread
