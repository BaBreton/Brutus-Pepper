"""Packaging checks use a disposable repository, never the real index."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


class PackageTest(unittest.TestCase):
    def test_new_source_included_private_files_excluded_and_no_overwrite(self):
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix='pepper-package-test-') as tmp:
            repo = Path(tmp) / 'source'
            shutil.copytree(root / 'install', repo / 'install')
            brain = repo / 'server/brain'
            (brain / 'static').mkdir(parents=True)
            for name in ('Dockerfile', 'docker-compose.yml', 'requirements.txt', 'main.py', '__init__.py'):
                (brain / name).write_text('# fixture\n')
            (brain / 'static/index.html').write_text('<title>Test</title>')
            (brain / 'audio_stream.py').write_text('# new source\n')
            (brain / 'static/admin.js').write_text('// new script\n')
            (brain / '.env').write_text('TEST_PRIVATE=sentinel')
            (brain / 'data').mkdir()
            (brain / 'data/private.py').write_text('sentinel')
            (brain / 'admin.token').write_text('sentinel')
            subprocess.run(['git', 'init', '-q', str(repo)], check=True)
            output = Path(tmp) / 'client.zip'
            command = ['bash', str(repo / 'install/package-client.sh'), str(output)]
            result = subprocess.run(command, text=True, capture_output=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            with zipfile.ZipFile(output) as archive:
                names = archive.namelist()
                self.assertIn('server/brain/audio_stream.py', names)
                self.assertIn('server/brain/static/admin.js', names)
                self.assertIn('install/Pepper.command', names)
                # Le poste Windows du client s'installe avec ces deux fichiers ;
                # les oublier livrerait un paquet sans chemin d'installation Windows.
                self.assertIn('install/Install-Windows.ps1', names)
                self.assertIn('install/Installer-Pepper.cmd', names)
                self.assertFalse(any('private' in name or name.endswith('/.env') or name.endswith('.token') for name in names))
                self.assertTrue(archive.getinfo('install/Pepper.command').external_attr >> 16 & 0o111)
            original = output.read_bytes()
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(output.read_bytes(), original)

            # Full delivery includes the application and guide, never our costing.
            apk = repo / 'app/build/outputs/apk/debug/app-debug.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b'fixture-apk')
            (repo / 'docs').mkdir()
            (repo / 'docs/PEPPER_CLIENT_GUIDE.pdf').write_bytes(b'%PDF-fixture')
            (repo / 'docs/PEPPER_USAGE_REPORT.md').write_text('PRIVATE COSTING')
            full_output = Path(tmp) / 'full-client.zip'
            full = subprocess.run([command[0], command[1], str(full_output), '--with-app'],
                                  text=True, capture_output=True, timeout=20)
            self.assertEqual(full.returncode, 0, full.stderr)
            with zipfile.ZipFile(full_output) as archive:
                self.assertEqual(archive.read('application/Pepper.apk'), b'fixture-apk')
                self.assertIn('docs/PEPPER_CLIENT_GUIDE.pdf', archive.namelist())
                self.assertIn('install/INSTALLER_APPLICATION.md', archive.namelist())
                self.assertFalse(any('USAGE_REPORT' in name for name in archive.namelist()))


if __name__ == '__main__':
    unittest.main()
