"""Exercise smoke-test assertions against temporary Windows registry keys."""

from pathlib import Path
import sys
import unittest
import uuid

from ci.smoke_install import WINDOWS_APPLICATION_ID, windows_registration


@unittest.skipUnless(sys.platform == "win32", "Requires the Windows registry")
class RegistrationTests(unittest.TestCase):
    def setUp(self):
        import winreg
        self.registry = winreg
        self.hive = winreg.HKEY_CURRENT_USER
        self.root = r"Software\OIEInstallerTest-" + uuid.uuid4().hex
        self.bases = (self.root + r"\native", self.root + r"\wow64")
        self.home = Path(r"C:\engine with spaces")
        self.addCleanup(self.remove, self.root)

    def remove(self, path):
        registry = self.registry
        try:
            key = registry.OpenKey(self.hive, path, 0, registry.KEY_READ | registry.KEY_WOW64_64KEY)
        except FileNotFoundError:
            return
        with key:
            children = [registry.EnumKey(key, index) for index in range(registry.QueryInfoKey(key)[0])]
        for child in children:
            self.remove(path + "\\" + child)
        registry.DeleteKeyEx(self.hive, path, registry.KEY_WOW64_64KEY)

    def write(self, version="4.7.0", *, base=None, **overrides):
        registry = self.registry
        major, minor = version.split(".")[:2]
        values = {"DisplayName": "Open Integration Engine " + version, "DisplayVersion": version,
                  "Publisher": "Open Integration Engine Project", "URLInfoAbout": "https://openintegrationengine.com",
                  "InstallLocation": str(self.home), "UninstallString": f'"{self.home / "uninstall.exe"}"',
                  "VersionMajor": int(major), "VersionMinor": int(minor)}
        values.update(overrides)
        with registry.CreateKeyEx(self.hive, (base or self.bases[0]) + "\\" + WINDOWS_APPLICATION_ID,
                                  0, registry.KEY_WRITE | registry.KEY_WOW64_64KEY) as key:
            for name, value in values.items():
                registry.SetValueEx(key, name, 0, registry.REG_DWORD if isinstance(value, int) else registry.REG_SZ, value)

    def check(self, version="4.7.0"):
        windows_registration(self.home, version, hive=self.hive, bases=self.bases)

    def test_upgrade_replaces_the_entry_at_the_same_uninstaller_path(self):
        self.write("4.6.0")
        self.write("4.7.0")
        self.check()
        self.check()  # Repeating the check must not change the entry.

    def test_stale_version_is_rejected(self):
        self.write("4.6.0")
        with self.assertRaisesRegex(SystemExit, "does not describe"):
            self.check()

    def test_duplicate_native_and_wow64_entries_are_rejected(self):
        self.write()
        self.write("4.6.0", base=self.bases[1])
        with self.assertRaisesRegex(SystemExit, "one native"):
            self.check()

    def test_entry_only_in_wow64_is_rejected(self):
        self.write(base=self.bases[1])
        with self.assertRaisesRegex(SystemExit, "one native"):
            self.check()

    def test_wrong_uninstaller_is_rejected(self):
        self.write(UninstallString='"C:\\different-engine\\uninstall.exe"')
        with self.assertRaisesRegex(SystemExit, "does not describe"):
            self.check()

    def test_missing_entry_is_rejected(self):
        with self.assertRaisesRegex(SystemExit, "one native"):
            self.check()

    def test_uninstall_requires_the_entry_to_be_removed(self):
        self.write()
        with self.assertRaisesRegex(SystemExit, "remains after uninstall"):
            self.check(None)
        self.remove(self.root)
        self.check(None)


if __name__ == "__main__":
    unittest.main()
