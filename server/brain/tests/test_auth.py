import tempfile
import unittest
from pathlib import Path

from brain.auth import TokenStore


class TokenStoreTest(unittest.TestCase):
    def test_tokens_are_generated_once_and_persist(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = TokenStore(Path(tmp))
            admin, pairing = first.admin_token, first.pairing_token
            self.assertTrue(admin)
            self.assertNotEqual(admin, pairing)
            second = TokenStore(Path(tmp))
            self.assertEqual(second.admin_token, admin)
            self.assertEqual(second.pairing_token, pairing)

    def test_admin_header_must_match_exactly(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = TokenStore(Path(tmp))
            self.assertTrue(store.accepts_admin("Bearer " + store.admin_token))
            self.assertFalse(store.accepts_admin("Bearer " + store.admin_token + "x"))
            self.assertFalse(store.accepts_admin(store.admin_token))
            self.assertFalse(store.accepts_admin(None))

    def test_robot_accepts_only_the_pairing_token(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = TokenStore(Path(tmp))
            self.assertTrue(store.accepts_robot("Bearer " + store.pairing_token))
            # Le jeton admin ne doit jamais ouvrir l'appairage robot : une tablette
            # d'accueil, accessible au public, ne doit pas hériter des droits d'admin.
            self.assertFalse(store.accepts_robot("Bearer " + store.admin_token))
            self.assertFalse(store.accepts_robot("Bearer nope"))

    def test_rotating_pairing_invalidates_the_old_token_for_other_workers(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = TokenStore(Path(tmp))
            old = first.pairing_token
            new = first.rotate_pairing()
            self.assertNotEqual(old, new)
            # Un second worker (autre instance de TokenStore, même disque) doit voir
            # la rotation immédiatement : c'est ça que garantit la lecture depuis le
            # fichier plutôt qu'un cache en mémoire sur l'instance qui a fait tourner.
            second = TokenStore(Path(tmp))
            self.assertFalse(second.accepts_robot("Bearer " + old))
            self.assertTrue(second.accepts_robot("Bearer " + new))
