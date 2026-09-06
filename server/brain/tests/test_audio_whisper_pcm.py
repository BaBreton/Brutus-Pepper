import unittest
from unittest.mock import patch

import numpy as np

from brain.connectors.stt_whisper_local import WhisperLocalConnector


class AudioWhisperPcmTest(unittest.TestCase):
    def test_16khz_passes_normalized_native_array_without_a_tempfile(self):
        seen = []

        class Model:
            def transcribe(self, audio, **kwargs):
                seen.append(audio)
                return iter([]), None

        with patch("tempfile.mkstemp", side_effect=AssertionError("no temporary file needed")):
            WhisperLocalConnector().transcribe({}, b"\x00\x80\xff\x7f" * 2400, model=Model())
        self.assertIsInstance(seen[0], np.ndarray)
        self.assertEqual(seen[0].dtype, np.float32)
        self.assertEqual(seen[0][0], -1.0)
        self.assertEqual(seen[0][1], 32767 / 32768)


if __name__ == "__main__":
    unittest.main()
