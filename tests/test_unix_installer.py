"""Exercise the self-extracting installer without elevation or an engine install."""

import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import build


@unittest.skipIf(os.name == "nt", "Requires a POSIX shell")
class UnixInstallerTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory(prefix="oie-shell-package-")
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.tools = self.root / "tools"
        self.tools.mkdir()
        self.script("id", "echo 0")  # No elevation, regardless of the test runner's UID.
        self.script("uname", "echo x86_64")
        self.script("sha256sum", shlex.quote(sys.executable) + " -c " + shlex.quote(
            "import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest()+'  -')"))
        self.image = self.root / build.APP_NAME
        runtime = self.image / "lib/runtime/bin"
        runtime.mkdir(parents=True)
        capture = runtime / "capture.py"
        capture.write_text("import json,os,pathlib,sys\n"
                           "pathlib.Path(os.environ['OIE_TEST_RECEIPT']).write_text(json.dumps(sys.argv[1:]))\n"
                           "sys.exit(int(os.environ.get('OIE_TEST_EXIT','0')))\n")
        java = runtime / "java"
        java.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + ' "$(dirname "$0")/capture.py" "$@"\n')
        java.chmod(0o755)
        (self.image / "bin").mkdir()
        native = self.image / "bin" / build.APP_NAME
        native.write_text("#!/bin/sh\nexit 139\n")
        native.chmod(0o755)
        (self.root / "target").mkdir()
        self.package = self.root / "installer with spaces.sh"
        with patch.object(build, "ROOT", self.root), patch.object(build, "machine", return_value="x64"):
            build.unix_installer(self.image, self.package)
        self.receipt = self.root / "args.json"
        self.unpack = self.root / "unpack with spaces"
        self.unpack.mkdir()
        self.env = dict(os.environ, PATH=str(self.tools) + os.pathsep + os.environ["PATH"],
                        TMPDIR=str(self.unpack), OIE_TEST_RECEIPT=str(self.receipt))

    def script(self, name, body):
        path = self.tools / name
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(0o755)

    def run_package(self, *args, **env):
        return subprocess.run(["sh", str(self.package), *args], env=dict(self.env, **env),
                              capture_output=True, text=True, timeout=20)

    def test_bundled_java_receives_literal_arguments_and_cleanup_preserves_exit(self):
        args = ["-q", "-dir", str(self.root / "engine's folder $literal")]
        result = self.run_package(*args, OIE_TEST_EXIT="7")
        self.assertEqual(result.returncode, 7, result.stderr)
        received = json.loads(self.receipt.read_text())
        self.assertEqual(received[:2], ["--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8"])
        self.assertEqual(received[-3:], args)
        self.assertEqual(received[-4], "org.openintegrationengine.installer.Main")
        self.assertIn(str(self.unpack), received[3])
        jar, libraries = received[3].split(":")
        self.assertEqual(Path(jar).name, "oie-installer-jpackage.jar")
        self.assertEqual(libraries, str(Path(jar).parent / "lib/*"))
        self.assertEqual(list(self.unpack.iterdir()), [])

    def test_no_arguments_reach_the_same_wizard_entry_point(self):
        result = self.run_package()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.receipt.read_text())[-1], "org.openintegrationengine.installer.Main")
        self.assertEqual(list(self.unpack.iterdir()), [])

    def test_corrupted_payload_never_starts_java(self):
        with self.package.open("ab") as handle:
            handle.write(b"corruption")
        result = self.run_package("-q")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("installer is damaged", result.stderr)
        self.assertFalse(self.receipt.exists())
        self.assertEqual(list(self.unpack.iterdir()), [])

    def test_wrong_architecture_never_starts_java(self):
        self.script("uname", "echo aarch64")
        result = self.run_package("-q")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("x86_64 Linux", result.stderr)
        self.assertFalse(self.receipt.exists())


if __name__ == "__main__":
    unittest.main()
