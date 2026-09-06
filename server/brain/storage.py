"""Écriture atomique sur disque, partagée par settings.py, auth.py et la médiathèque.
Isolée ici pour éviter l'inversion de couches : settings.py et auth.py sont tous deux
des consommateurs de la persistance, ni l'un ni l'autre n'en est le propriétaire."""
import os
import tempfile
from pathlib import Path


def atomic_write(path: Path, data: bytes, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=str(path.parent))
    replaced = False
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
        replaced = True
        # Le renommage doit survivre à une coupure de courant : sans ce fsync du
        # répertoire, settings.key peut être régénéré alors que settings.enc a
        # survécu — les réglages redeviennent alors indéchiffrables.
        _fsync_directory(path.parent)
    finally:
        if not replaced and os.path.exists(temporary):
            os.unlink(temporary)


def _fsync_directory(directory: Path) -> None:
    try:
        descriptor = os.open(str(directory), os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(descriptor)
    except OSError:
        pass  # certains systèmes de fichiers refusent le fsync sur un répertoire
    finally:
        os.close(descriptor)
