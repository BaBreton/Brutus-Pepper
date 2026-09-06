import tempfile
import unittest
from pathlib import Path

from brain.media.library import MediaLibrary

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
JPEG = b"\xff\xd8\xff" + b"\x00" * 64


class MediaLibraryTest(unittest.TestCase):
    def test_upload_normalizes_the_name_and_resolves_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            library = MediaLibrary(Path(tmp))
            item = library.add_bytes("Accueil Été", "image/png", PNG)
            self.assertEqual(item["slug"], "accueil-ete")
            self.assertEqual(item["kind"], "image")
            self.assertEqual(library.resolve("accueil ete")["id"], item["id"])

    def test_duplicate_normalized_name_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            library = MediaLibrary(Path(tmp))
            library.add_bytes("Accueil Été", "image/png", PNG)
            with self.assertRaises(ValueError):
                library.add_bytes("accueil ete", "image/png", PNG)

    def test_invalid_png_leaves_no_partial_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            library = MediaLibrary(Path(tmp))
            with self.assertRaises(ValueError):
                library.add_bytes("Faux", "image/png", b"pas un png du tout")
            self.assertEqual(list((Path(tmp) / "media").iterdir()), [])
            self.assertEqual(library.list(), [])

    def test_unknown_query_raises_lookup_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(LookupError):
                MediaLibrary(Path(tmp)).resolve("inexistant")

    def test_unsupported_mime_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                MediaLibrary(Path(tmp)).add_bytes("Doc", "application/pdf", b"%PDF-1.4")

    def test_delete_removes_the_entry_and_its_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            library = MediaLibrary(Path(tmp))
            item = library.add_bytes("Logo", "image/jpeg", JPEG)
            library.delete(item["id"])
            self.assertEqual(library.list(), [])
            self.assertEqual(list((Path(tmp) / "media").iterdir()), [])
            with self.assertRaises(LookupError):
                library.delete(item["id"])

    def test_content_returns_the_item_and_a_path_inside_the_media_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            library = MediaLibrary(Path(tmp))
            item = library.add_bytes("Logo", "image/jpeg", JPEG)
            found, path = library.content(item["id"])
            self.assertEqual(found["id"], item["id"])
            self.assertTrue(path.is_file())
            # Le chemin doit rester confiné au répertoire des médias.
            self.assertIn((Path(tmp) / "media").resolve(), path.resolve().parents)

    def test_ambiguous_query_is_rejected_rather_than_guessed(self):
        with tempfile.TemporaryDirectory() as tmp:
            library = MediaLibrary(Path(tmp))
            library.add_bytes("Accueil Hiver", "image/png", PNG)
            library.add_bytes("Accueil Printemps", "image/png", PNG)
            # « accueil » correspond aux deux avec le même score : mieux vaut refuser
            # que d'afficher le mauvais média devant un visiteur.
            with self.assertRaises(LookupError):
                library.resolve("accueil")
