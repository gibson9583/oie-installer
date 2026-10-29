"""Regression coverage for the actual Temurin manifest behind the Windows failure."""

import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from xml.dom import minidom
from xml.parsers.expat import ExpatError
import zipfile

import windows_manifest as manifests


FIXTURE = Path(__file__).parent / "fixtures/temurin-21.0.12.1-console.manifest"


class ManifestTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="oie-uac-test-")
        self.addCleanup(temporary.cleanup)
        self.exe = Path(temporary.name) / "engine with spaces/oiecommand.exe"
        self.exe.parent.mkdir()
        self.exe.write_bytes(b"requireAdministrator outside the manifest is not sufficient")
        self.embedded = FIXTURE.read_bytes()
        self.input_paths = []
        self.embed_calls = 0
        self.extract_failure = False
        self.embed_failure = False
        self.ignore_embed = False
        self.enterContext(patch.object(manifests, "_manifest_tool", return_value="mt.exe"))
        self.enterContext(patch.object(manifests.subprocess, "run", side_effect=self.mt))

    def mt(self, command, *, check):
        self.assertTrue(check, "SDK errors must abort the operation")
        if command[2].startswith("-inputresource:"):
            self.assertEqual(command[2], f"-inputresource:{self.exe};#1")
            if self.extract_failure:
                raise subprocess.CalledProcessError(1, command)
            destination = Path(command[3].removeprefix("-out:"))
            self.input_paths.append(destination)
            destination.write_bytes(self.embedded)
        else:
            self.assertEqual(command[2], "-manifest")
            self.assertEqual(command[4], f"-outputresource:{self.exe};#1")
            self.assertTrue(self.exe.stat().st_mode & stat.S_IWRITE)
            self.embed_calls += 1
            if self.embed_failure:
                raise subprocess.CalledProcessError(1, command)
            if not self.ignore_embed:
                self.embedded = Path(command[3]).read_bytes()
        return subprocess.CompletedProcess(command, 0)

    def test_actual_paired_tag_is_elevated_and_other_metadata_is_preserved(self):
        self.assertIn(b'level="asInvoker" uiAccess="false"></requestedExecutionLevel>', self.embedded)
        before = minidom.parseString(self.embedded)
        manifests.require_administrator(self.exe)
        manifests.verify_administrator(self.exe)
        after = minidom.parseString(self.embedded)
        for tag in ("assemblyIdentity", "dependency", "compatibility", "asmv3:application"):
            self.assertEqual([node.toxml() for node in before.getElementsByTagName(tag)],
                             [node.toxml() for node in after.getElementsByTagName(tag)])
        self.assertEqual(after.documentElement.attributes.items(), before.documentElement.attributes.items())
        self.assertEqual(self.embed_calls, 1)

    def test_self_closing_and_utf16_manifests(self):
        document = minidom.parseString(self.embedded)
        level = document.getElementsByTagNameNS(manifests.UAC_NAMESPACE, "requestedExecutionLevel")[0]
        self.assertFalse(level.childNodes)
        # minidom serializes empty elements in self-closing form.
        self.embedded = document.toxml(encoding="utf-16")
        manifests.require_administrator(self.exe)
        manifests.verify_administrator(self.exe)

    def test_repeat_is_idempotent_and_uses_fresh_temporary_files(self):
        manifests.require_administrator(self.exe)
        first = self.embedded
        manifests.require_administrator(self.exe)
        self.assertEqual(self.embedded, first)
        self.assertEqual(len(set(self.input_paths)), 4)
        self.assertTrue(all(not path.parent.exists() for path in self.input_paths))

    def test_sdk_extraction_failure_aborts_without_embedding(self):
        self.extract_failure = True
        with self.assertRaises(subprocess.CalledProcessError):
            manifests.require_administrator(self.exe)
        self.assertEqual(self.embed_calls, 0)

    def test_sdk_embed_failure_restores_read_only_flag(self):
        self.exe.chmod(stat.S_IREAD)
        self.addCleanup(self.exe.chmod, stat.S_IREAD | stat.S_IWRITE)
        mode = self.exe.stat().st_mode
        self.embed_failure = True
        with self.assertRaises(subprocess.CalledProcessError):
            manifests.require_administrator(self.exe)
        self.assertEqual(self.exe.stat().st_mode, mode)

    def test_success_restores_read_only_flag(self):
        self.exe.chmod(stat.S_IREAD)
        self.addCleanup(self.exe.chmod, stat.S_IREAD | stat.S_IWRITE)
        mode = self.exe.stat().st_mode
        manifests.require_administrator(self.exe)
        self.assertEqual(self.exe.stat().st_mode, mode)
        manifests.verify_administrator(self.exe)

    def test_successful_sdk_exit_cannot_hide_an_unchanged_manifest(self):
        self.ignore_embed = True
        with self.assertRaisesRegex(RuntimeError, "requireAdministrator"):
            manifests.require_administrator(self.exe)
        self.assertEqual(self.embed_calls, 1)

    def test_verification_reads_the_resource_instead_of_searching_executable_bytes(self):
        with self.assertRaisesRegex(RuntimeError, "requireAdministrator"):
            manifests.verify_administrator(self.exe)

    def test_missing_duplicate_or_wrong_namespace_level_fails_before_embedding(self):
        for case in ("missing", "duplicate", "wrong-namespace"):
            with self.subTest(case=case):
                document = minidom.parseString(FIXTURE.read_bytes())
                level = document.getElementsByTagNameNS(manifests.UAC_NAMESPACE, "requestedExecutionLevel")[0]
                if case == "missing":
                    level.parentNode.removeChild(level)
                elif case == "duplicate":
                    level.parentNode.appendChild(level.cloneNode(True))
                else:
                    level.setAttribute("xmlns", "urn:unrelated")
                self.embedded = document.toxml(encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "exactly one"):
                    manifests.require_administrator(self.exe)
        self.assertEqual(self.embed_calls, 0)

    def test_invalid_xml_is_rejected_before_embedding(self):
        self.embedded = b"not XML"
        with self.assertRaises(ExpatError):
            manifests.require_administrator(self.exe)
        self.assertEqual(self.embed_calls, 0)

    def test_ui_access_is_disabled_like_install4j(self):
        self.embedded = self.embedded.replace(b'level="asInvoker"', b'level="requireAdministrator"').replace(b'uiAccess="false"', b'uiAccess="true"')
        with self.assertRaisesRegex(RuntimeError, "uiAccess=false"):
            manifests.verify_administrator(self.exe)
        manifests.require_administrator(self.exe)
        manifests.verify_administrator(self.exe)


class SDKTests(unittest.TestCase):
    def test_missing_sdk_is_reported(self):
        with tempfile.TemporaryDirectory() as work, patch.dict(os.environ, {"ProgramFiles(x86)": work}):
            with self.assertRaisesRegex(RuntimeError, "Windows SDK"):
                manifests._manifest_tool()

    @unittest.skipUnless(sys.platform == "win32", "Requires Windows SDK mt.exe and a Windows JDK")
    def test_real_jdk_launcher_and_sdk_round_trip(self):
        # Exercise the real SDK, including its serialization and read-only file handling.
        module = Path(os.environ["JAVA_HOME"]) / "jmods/jdk.jpackage.jmod"
        with tempfile.TemporaryDirectory(prefix="oie-uac-native-") as work:
            exe = Path(work) / "oiecommand.exe"
            with zipfile.ZipFile(module) as package:
                resource = next(name for name in package.namelist() if name.endswith("/jpackageapplauncher.exe"))
                exe.write_bytes(package.read(resource))
            mode = stat.S_IREAD
            exe.chmod(mode)
            try:
                manifests.require_administrator(exe)
                manifests.verify_administrator(exe)
                manifests.require_administrator(exe)
                manifests.verify_administrator(exe)
                self.assertFalse(exe.stat().st_mode & stat.S_IWRITE)
            finally:
                exe.chmod(stat.S_IREAD | stat.S_IWRITE)


if __name__ == "__main__":
    unittest.main()
