import tempfile
import unittest
from pathlib import Path

from brain.storage import atomic_write


class AtomicWriteTest(unittest.TestCase):
    def test_writes_the_file_and_leaves_no_temp_file_behind(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "sub" / "file.bin"
            atomic_write(target, b"hello")
            self.assertEqual(target.read_bytes(), b"hello")
            leftovers = [p for p in target.parent.iterdir() if p != target]
            self.assertEqual(leftovers, [])

    def test_sets_restrictive_permissions_by_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "file.bin"
            atomic_write(target, b"secret")
            mode = target.stat().st_mode & 0o777
            self.assertEqual(mode, 0o600)
