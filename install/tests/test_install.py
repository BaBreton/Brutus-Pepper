"""Launchers tested without a Docker daemon, network traffic or a robot."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="pepper test spaces ")
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.bundle = self.base / "Pepper client"
        shutil.copytree(ROOT / "install", self.bundle / "install")
        brain = self.bundle / "server/brain"
        brain.mkdir(parents=True)
        for name in ("Dockerfile", "docker-compose.yml", "requirements.txt", "main.py"):
            shutil.copy2(ROOT / "server/brain" / name, brain / name)
        self.bin = self.base / "bin"
        self.bin.mkdir()
        for name in ("docker", "curl"):
            shutil.copy2(ROOT / f"install/tests/fake-{name}.sh", self.bin / name)
            (self.bin / name).chmod(0o755)
        self.log = self.base / "calls"
        self.env = dict(os.environ, PATH=f"{self.bin}:/usr/bin:/bin:/usr/sbin:/sbin",
                        PEPPER_TEST_LOG=str(self.log))
        for name in ("DOCKER_HOST", "DOCKER_CONTEXT", "SSH_CONNECTION", "SSH_TTY",
                     "PEPPER_LAN_IP", "BASH_ENV"):
            self.env.pop(name, None)

    def run_launcher(self, *args, **env):
        return subprocess.run(["/bin/bash", str(self.bundle / "install/pepper.sh"), *args],
                              env=dict(self.env, **env), text=True, capture_output=True,
                              timeout=12, cwd="/")

    def calls(self):
        return self.log.read_text() if self.log.exists() else ""

    def test_prerequisites_stop_before_mutation(self):
        for scenario, message in (("no-socket", "Docker"), ("no-compose", "Compose")):
            with self.subTest(scenario=scenario):
                result = self.run_launcher("start", SCENARIO=scenario)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn(message, result.stderr)
                self.assertNotIn(" up ", self.calls())

    def test_docker_not_installed(self):
        (self.bin / "docker").unlink()
        result = self.run_launcher("start")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Docker", result.stderr)
        self.assertEqual(self.calls(), "")

    def test_repeat_start_same_project_and_no_secrets(self):
        for _ in range(2):
            result = self.run_launcher("start", "--lan-ip", "198.51.100.12", "--wait", "1")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("http://198.51.100.12:8770", result.stdout)
            self.assertNotIn("SECRET-SENTINEL", result.stdout + result.stderr)
        calls = self.calls()
        self.assertEqual(calls.count("up -d --no-build --pull never brain"), 2)
        self.assertEqual(calls.count("build brain"), 2)
        self.assertIn("--project-name brain", calls)
        self.assertIn(str(self.bundle / "server/brain"), calls)
        for forbidden in ("down", "volume rm", "prune", "admin.token", "pairing.token"):
            self.assertNotIn(forbidden, calls)

    def test_timeout_is_failure(self):
        result = self.run_launcher("start", "--wait", "1", SCENARIO="timeout")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("délai", result.stderr)
        self.assertNotIn("admin.token", self.calls())

    def test_build_or_up_failure(self):
        for scenario in ("build-failed", "up-failed"):
            result = self.run_launcher("start", SCENARIO=scenario)
            self.assertNotEqual(result.returncode, 0)

    def test_setup_refuses_noninteractive(self):
        result = self.run_launcher("setup")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("interactif", result.stderr)
        self.assertNotIn("admin.token", self.calls())

    def test_remote_docker_and_foreign_project_refused(self):
        for env in ({"TEST_ENDPOINT": "ssh://example"}, {"TEST_PROJECT": "other"}):
            result = self.run_launcher("start", **env)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn(" up ", self.calls())

    def test_stop_and_status_never_build_or_delete(self):
        for command in ("stop", "status"):
            result = self.run_launcher(command)
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("stop brain", self.calls())
        for forbidden in ("build brain", "up -d", "down", "exec"):
            self.assertNotIn(forbidden, self.calls())

    def test_bad_arguments_do_not_call_docker(self):
        for args in (("start", "--lan-ip", "999.2.3.4"), ("start", "--wait", "0"),
                     ("start", "--lan-ip", "127.0.0.1"), ("remove",),
                     ("start", "--lan-ip", "host;bad")):
            result = self.run_launcher(*args)
            self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls(), "")


if __name__ == "__main__":
    unittest.main()
