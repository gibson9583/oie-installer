"""Build contract checks; runtime compatibility is also checked against published ZIPs."""

import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("installer_build", Path(__file__).resolve().parents[1] / "build.py")
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)


class PluginBuildTests(unittest.TestCase):
    def package(self, plugin_id, version="4.7.0"):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as package:
            package.writestr(plugin_id + "/plugin.xml", f"<plugin><mirthVersion>{version}</mirthVersion></plugin>")
        return buffer.getvalue()

    def bundle(self, directory, supported="4.7.0", checksum="a" * 64, digest="a" * 64):
        payload = Path(directory) / "payload"
        payload.mkdir()

        def get(url):
            if "/releases/" in url:
                plugin_id = next(p[0] for p in build.PLUGINS if p[2] in url)
                tag = "1.1.0" if plugin_id == "tls-manager" else "v1.0"
                name = plugin_id + "-" + tag.removeprefix("v") + ".zip"
                return json.dumps({"tag_name": tag, "html_url": url,
                                   "assets": [{"name": name, "digest": "sha256:" + digest,
                                               "size": 123, "browser_download_url": plugin_id},
                                              {"name": "SHA256SUMS", "browser_download_url": "sums/" + name}]}).encode()
            name = url.removeprefix("sums/")
            return ((checksum if name.startswith("tls-manager-") else digest) + "  " + name).encode()

        def download(url, size, expected, target, refresh=False):
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(self.package(url, supported if url == "tls-manager" else "4.7.0"))
            self.assertTrue(refresh)

        files = {}
        with patch.object(build, "PAYLOAD", payload), patch.object(build, "get", get), \
                patch.object(build, "cache_dir", return_value=Path(directory) / "cache"), \
                patch.object(build, "download", download):
            plugins = build.bundle_plugins("4.7.0", files, True)
        return plugins, files

    def test_all_four_plugins_are_bundled_and_pinned(self):
        with tempfile.TemporaryDirectory(prefix="oie-build-test-") as directory:
            plugins, files = self.bundle(directory)
            self.assertEqual([p["id"] for p in plugins], [p[0] for p in build.PLUGINS])
            self.assertEqual(len(files), 4)
            self.assertEqual([p["id"] for p in plugins], ["websupport", "tls-manager", "rbac", "simple-channel-history"])
            self.assertEqual(plugins[1]["version"], "1.1.0")
            self.assertTrue(plugins[1]["source"].endswith("/releases/tags/1.1.0"))
            self.assertTrue(all((Path(directory) / "payload" / p["archive"]).is_file() for p in plugins))

    def test_incompatible_tls_manager_blocks_build(self):
        with tempfile.TemporaryDirectory(prefix="oie-build-test-") as directory:
            with self.assertRaises(SystemExit):
                self.bundle(directory, supported="4.6.0")

    def test_checksum_disagreement_blocks_build(self):
        with tempfile.TemporaryDirectory(prefix="oie-build-test-") as directory:
            with self.assertRaises(SystemExit):
                self.bundle(directory, checksum="b" * 64)

    def test_refresh_bypasses_verified_cache(self):
        with tempfile.TemporaryDirectory(prefix="oie-build-test-") as directory:
            target = Path(directory) / "asset.zip"
            target.write_bytes(b"old")
            with patch.object(build.urllib.request, "urlopen", return_value=io.BytesIO(b"new")) as request:
                build.download("https://example.invalid/asset.zip", 3,
                               __import__("hashlib").sha256(b"new").hexdigest(), target, refresh=True)
                request.assert_called_once()
            self.assertEqual(target.read_bytes(), b"new")


if __name__ == "__main__":
    unittest.main()
