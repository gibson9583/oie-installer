"""Build the OIE Installer for one engine release.

python3 build.py --engine-repo ../oie --refresh [--payload-only]

1. Reads the release from GitHub and checks that sha256sums agrees with the GitHub asset digests.
2. Downloads the engine archive into the user cache and checks its SHA-256.
3. Takes the portable launcher from the release tag in the engine repository.
4. Downloads and checks the latest compatible add-ons, including the bundled web client.
5. Writes payload/ with a manifest that pins every file.
6. Compiles the JavaFX wizard and packages it with jpackage.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
import tarfile
import stat
import tempfile
import xml.etree.ElementTree as ET
import platform as host
from pathlib import Path

from windows_manifest import require_administrator

ROOT = Path(__file__).resolve().parent
PAYLOAD = ROOT / "payload"
REPOSITORY = "OpenIntegrationEngine/engine"
LAUNCHER_SOURCE = "server/basedir-includes/"
LAUNCHERS = {"unix": ["oieserver"], "windows-x64": ["oieserver.ps1"], "windows-arm64": ["oieserver.ps1"]}
PLUGINS = [
    ("websupport", "OIE Web Client", "gibson9583/oie-web-support-plugin", "websupport"),
    ("rbac", "Role-Based Access Control (RBAC)", "diridium-com/role-based-access-control", "rbac"),
    ("simple-channel-history", "Simple Channel History", "diridium-com/simple-channel-history", "simple-channel-history"),
]


def fail(message: str) -> None:
    print("Error: %s" % message, file=sys.stderr)
    sys.exit(1)


def get(url: str) -> bytes:
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "oie-installer-build"}
    if url.startswith("https://api.github.com/") and os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cache_dir() -> Path:
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Caches" / "oie-installer"
    return Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))) / "oie-installer"


def host_platform() -> str:
    if sys.platform == "win32":
        return "windows-arm64" if machine() == "arm64" else "windows-x64"
    return "unix"


def machine() -> str:
    """The hardware architecture, also when an x64 Python runs emulated on Windows ARM64."""
    name = os.environ.get("PROCESSOR_ARCHITEW6432") or os.environ.get("PROCESSOR_ARCHITECTURE") if sys.platform == "win32" else host.machine()
    return "arm64" if (name or "").lower() in ("arm64", "aarch64") else "x64"


def archive_name(version: str, platform: str) -> str:
    underscored = version.replace(".", "_")
    if platform == "unix":
        return "oie_unix_%s.tar.gz" % underscored
    return "oie_%s_%s.zip" % (platform, underscored)


def download(url: str, size: int, expected: str, target: Path, refresh: bool = False) -> None:
    if not refresh and target.exists() and target.stat().st_size == size and sha256_file(target) == expected:
        print("  cached %s" % target)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".part")
    digest, received = hashlib.sha256(), 0
    request = urllib.request.Request(url, headers={"User-Agent": "oie-installer-build"})
    with urllib.request.urlopen(request, timeout=60) as response, open(partial, "wb") as out:
        for chunk in iter(lambda: response.read(1 << 20), b""):
            out.write(chunk)
            digest.update(chunk)
            received += len(chunk)
            sys.stdout.write("\r  %3d%%  %d of %d MB" % (received * 100 // size, received >> 20, size >> 20))
            sys.stdout.flush()
    print()
    if received != size or digest.hexdigest() != expected:
        partial.unlink()
        fail("%s failed verification. The download was deleted." % target.name)
    os.replace(partial, target)


def bundle_plugins(version: str, files: dict, refresh: bool) -> list:
    plugins = []
    (PAYLOAD / "plugins").mkdir(exist_ok=True)
    for plugin_id, name, repository, prefix in PLUGINS:
        release = json.loads(get(f"https://api.github.com/repos/{repository}/releases/latest"))
        tag = release["tag_name"]
        plugin_version = tag.removeprefix("v")
        archive = f"{prefix}-{plugin_version}.zip"
        assets = {item["name"]: item for item in release["assets"]}
        asset = assets.get(archive)
        digest = (asset or {}).get("digest") or ""
        if not asset or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            fail(f"{repository}@{tag} has no GitHub-verified {archive}")
        checksum = next((assets[n] for n in (archive + ".sha256", "SHA256SUMS", "sha256sums") if n in assets), None)
        if not checksum:
            fail(f"{repository}@{tag} has no checksum file")
        sums = {}
        for line in get(checksum["browser_download_url"]).decode().splitlines():
            match = re.match(r"^([0-9a-fA-F]{64}) [ *](.+)$", line.strip())
            if match:
                sums[match.group(2).strip()] = match.group(1).lower()
        if sums.get(archive) != digest[7:]:
            fail(f"Checksums and GitHub disagree on {archive}")
        cached = cache_dir() / repository.replace("/", "--") / archive
        print(f"Downloading {repository}@{tag}: {archive}")
        download(asset["browser_download_url"], int(asset["size"]), digest[7:], cached, refresh)
        with zipfile.ZipFile(cached) as package:
            descriptor = ET.fromstring(package.read(f"{plugin_id}/plugin.xml"))
            supported = [v.strip() for v in (descriptor.findtext("mirthVersion") or "").split(",")]
            if version not in supported:
                fail(f"{archive} does not support engine {version}: {supported}")
        relative = "plugins/" + archive
        shutil.copyfile(cached, PAYLOAD / relative)
        files[relative] = digest[7:]
        plugins.append({"id": plugin_id, "name": name, "version": plugin_version,
                        "archive": relative, "size": int(asset["size"]), "sha256": digest[7:],
                        "source": release["html_url"], "engineVersions": supported})
    return plugins


def build_payload(version: str, engine_repo: Path, platform: str, refresh: bool = False) -> dict:
    tag = "v" + version
    print("Reading release %s from GitHub" % tag)
    release = json.loads(get("https://api.github.com/repos/%s/releases/tags/%s" % (REPOSITORY, tag)))
    assets = {item["name"]: item for item in release.get("assets", [])}
    if "sha256sums" not in assets:
        fail("Release %s has no sha256sums file." % tag)
    sums = {}
    for line in get(assets["sha256sums"]["browser_download_url"]).decode().splitlines():
        match = re.match(r"^([0-9a-fA-F]{64}) [ *](.+)$", line.strip())
        if match:
            sums[match.group(2).strip()] = match.group(1).lower()
    for name, expected in sums.items():
        digest = (assets.get(name) or {}).get("digest") or ""
        if digest.startswith("sha256:") and digest[7:] != expected:
            fail("sha256sums and GitHub disagree on %s. Do not ship this release." % name)

    archive = archive_name(version, platform)
    if archive not in assets or archive not in sums:
        fail("Release %s has no verified asset %s." % (tag, archive))
    asset = assets[archive]
    print("  %s: sha256sums and the GitHub digest agree" % archive)
    cached = cache_dir() / archive
    print("Downloading %s" % archive)
    download(asset["browser_download_url"], int(asset["size"]), sums[archive], cached, refresh)

    if PAYLOAD.exists():
        for child in PAYLOAD.iterdir():
            if child.is_dir():
                remove_tree(child)
            else:
                child.unlink()
    (PAYLOAD / "launcher").mkdir(parents=True, exist_ok=True)
    try:
        os.link(cached, PAYLOAD / archive)
    except OSError:
        shutil.copyfile(cached, PAYLOAD / archive)

    print("Taking the portable launcher from %s at %s" % (engine_repo, tag))
    git = lambda *args: subprocess.run(["git", "-C", str(engine_repo)] + list(args), check=True, capture_output=True).stdout
    commit = git("rev-parse", tag + "^{commit}").decode().strip()
    files = {archive: sums[archive]}
    for launcher in LAUNCHERS[platform]:
        path = PAYLOAD / "launcher" / launcher
        path.write_bytes(git("show", "%s:%s%s" % (tag, LAUNCHER_SOURCE, launcher)))
        files["launcher/" + launcher] = sha256_file(path)
        print("  %s  %s" % (files["launcher/" + launcher], launcher))

    plugins = bundle_plugins(version, files, refresh)
    digest = asset.get("digest") or ""
    manifest = {
        "version": version,
        "platform": platform,
        "archive": archive,
        "size": int(asset["size"]),
        "githubDigest": digest[7:] if digest.startswith("sha256:") else None,
        "launcher": {"ref": tag, "commit": commit, "source": LAUNCHER_SOURCE, "files": LAUNCHERS[platform]},
        "files": files,
        "plugins": plugins,
        "builtAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    (PAYLOAD / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Wrote %s" % PAYLOAD)
    return manifest


APP_NAME = "Open Integration Engine Installer"
WINSW_VERSION = "2.12.0"
WINSW_SHA256 = "05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da"


def remove_tree(path: Path) -> None:
    """shutil.rmtree that also removes read-only files and waits out brief Windows file locks."""
    def writable(function, name, _):
        os.chmod(name, stat.S_IWRITE)
        function(name)
    for attempt in range(5):
        try:
            shutil.rmtree(path, onexc=writable)
            return
        except PermissionError:
            if attempt == 4:
                raise
            time.sleep(3)


def executable(java_home: Path, name: str) -> str:
    return str(java_home / "bin" / (name + (".exe" if sys.platform == "win32" else "")))


# Modules the engine launchers need when the engine runs on an installed Java (the no-JRE media).
LAUNCHER_MODULES = "java.base,java.desktop,java.prefs,java.sql,jdk.unsupported"
# install4j's init header for oieservice, so /etc/init.d/oieservice can link to it.
INIT_HEADER = """# chkconfig:         2345 75 15
# description:       oieservice
### BEGIN INIT INFO
# Provides:          oieservice
# Required-Start:    $local_fs $network $remote_fs $syslog $time
# Required-Stop:     $local_fs $network $remote_fs $syslog $time
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: Open Integration Engine Service
### END INIT INFO
"""


def native_payload(java_home: Path, env: dict, bundled_java: bool = True) -> dict:
    """Stage the verified release and a native engine image built with java_home's jpackage.

    With bundled_java the engine image carries a full Java runtime (install4j's -with-JRE media). Without it,
    the image carries only what its launchers need, and the engine runs on an installed Java 17 or later."""
    manifest = json.loads((PAYLOAD / "manifest.json").read_text())
    source = manifest.get("sourceArchive", {"name": manifest["archive"], "sha256": manifest["files"].get(manifest["archive"]), "size": manifest["size"]})
    archive = PAYLOAD / source["name"]
    if not archive.is_file():
        archive = cache_dir() / source["name"]
    if not archive.is_file() or sha256_file(archive) != source["sha256"]:
        fail("The verified source archive is unavailable. Run the build without --app-only to prepare it.")
    stage = ROOT / "target/native-engine"
    if stage.exists():
        remove_tree(stage)
    stage.mkdir(parents=True)
    excluded = {".install4j", "oieserver", "oieserver.exe", "oieserver.ps1", "oieservice", "oieservice.exe", "oiecommand", "oiecommand.exe", "oiemanager", "oiemanager.exe", "uninstall", "uninstall.exe", "uninstall.dat", "jre"}

    def accept(name: str) -> Path | None:
        parts = Path(name.replace("\\", "/")).parts
        if not parts or parts[0] not in ("oie", "OpenIntegrationEngine") or ".." in parts or ":" in name or name.startswith(("/", "\\")):
            fail("Unsafe engine release path: " + name)
        if len(parts) == 1 or parts[1] in excluded:
            return None
        return stage.joinpath(*parts[1:])

    print("Staging the verified engine release; removing install4j-generated launchers/runtime", flush=True)
    if source["name"].endswith(".zip"):
        with zipfile.ZipFile(archive) as package:
            for entry in package.infolist():
                target = accept(entry.filename)
                if target is None:
                    continue
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode):
                    fail("Engine release contains a link: " + entry.filename)
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with package.open(entry) as src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    target.chmod(0o755 if mode & 0o111 else 0o644)
    else:
        with tarfile.open(archive, "r:gz") as package:
            for entry in package:
                target = accept(entry.name)
                if target is None:
                    continue
                if not entry.isfile() and not entry.isdir():
                    fail("Engine release contains a link or special file: " + entry.name)
                if entry.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with package.extractfile(entry) as src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    target.chmod(0o755 if entry.mode & 0o111 else 0o644)
    if not (stage / "mirth-server-launcher.jar").is_file() or not (stage / "conf/mirth.properties").is_file():
        fail("The engine release layout is incomplete")
    inputs = ROOT / "target/engine-input"
    if inputs.exists():
        remove_tree(inputs)
    inputs.mkdir()
    shutil.copy2(ROOT / "target/oie-installer-jpackage.jar", inputs)
    shutil.copytree(ROOT / "target/lib", inputs / "lib")
    native_out = ROOT / "target/engine-image"
    if native_out.exists():
        remove_tree(native_out)
    mac = sys.platform == "darwin"
    windows = sys.platform == "win32"
    name = "OIE Engine" if mac else "oieserver"
    relative_home = "$APPDIR/../../../.." if not windows else "$APPDIR/.."
    args = [executable(java_home, "jpackage"), "--type", "app-image", "--name", name, "--dest", str(native_out),
            "--input", str(inputs), "--main-jar", "oie-installer-jpackage.jar", "--main-class", "org.openintegrationengine.installer.EngineBootstrap",
            "--app-version", manifest["version"], "--vendor", "Open Integration Engine contributors", "--add-modules", "ALL-MODULE-PATH" if bundled_java else LAUNCHER_MODULES,
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--java-options", "-Doie.engine.home=" + relative_home, "--java-options", "-Doie.engine.role=server"]
    launcher_configs = ROOT / "target/engine-launchers"
    launcher_configs.mkdir(exist_ok=True)
    for launcher, role in [("oieserver", "server"), ("oieservice", "service"), ("oiecommand", "command"), ("uninstall", "uninstall")]:
        if launcher == name:
            continue
        props = launcher_configs / (launcher + ".properties")
        props.write_text("main-class=org.openintegrationengine.installer.EngineBootstrap\njava-options=-Doie.engine.home=" + relative_home + " -Doie.engine.role=" + role + ("\nwin-console=true" if windows else "") + "\n")
        args += ["--add-launcher", launcher + "=" + str(props)]
    if mac:
        args += ["--icon", str(ROOT / "packaging/icon.icns"), "--mac-package-identifier", "org.openintegrationengine.engine"]
    elif windows:
        args += ["--icon", str(ROOT / "packaging/icon.ico"), "--win-console"]
    else:
        args += ["--icon", str(ROOT / "packaging/icon.png")]
    print("Generating native engine launchers and its independent Java runtime", flush=True)
    subprocess.run(args, cwd=ROOT, env=env, check=True)
    if mac:
        image = stage / ".launcher/OIE Engine.app"
        shutil.copytree(native_out / "OIE Engine.app", image, symlinks=False)
        subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(image)], check=True)
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(image)], check=True)
        runtime = ".launcher/OIE Engine.app/Contents/runtime/Contents/Home/bin/java"
    elif windows:
        shutil.copytree(native_out / name, stage, dirs_exist_ok=True)
        require_administrator(stage / "oiecommand.exe")
        runtime = "runtime/bin/java.exe"
        vendor = cache_dir() / "vendor" / f"WinSW-{WINSW_VERSION}-x64.exe"
        download(f"https://github.com/winsw/winsw/releases/download/v{WINSW_VERSION}/WinSW-x64.exe", 18243033, WINSW_SHA256, vendor)
        shutil.copy2(vendor, stage / "oie-service.exe")
        license_text = get(f"https://raw.githubusercontent.com/winsw/winsw/v{WINSW_VERSION}/LICENSE.txt")
        (stage / "docs/LICENSE-WinSW.txt").write_bytes(license_text)
    else:
        shutil.copytree(native_out / name, stage / ".launcher/oieserver", symlinks=False)
        runtime = ".launcher/oieserver/lib/runtime/bin/java"
    if not windows:
        for launcher in ("oieserver", "oieservice", "oiecommand"):
            native = f".launcher/OIE Engine.app/Contents/MacOS/{launcher}" if mac else f".launcher/oieserver/bin/{launcher}"
            # Resolve /usr/local/bin and /etc/init.d links to the engine folder.
            (stage / launcher).write_text("#!/bin/sh\n" + (INIT_HEADER if launcher == "oieservice" else "")
                                          + 'self=$0\nwhile [ -h "$self" ]; do\n  link=$(readlink "$self")\n  case $link in /*) self=$link ;; *) self=$(dirname -- "$self")/$link ;; esac\ndone\n'
                                          + 'OIE_HOME=$(CDPATH= cd -- "$(dirname -- "$self")" && pwd -P) || exit 1\ncd "$OIE_HOME" || exit 1\nexec "$OIE_HOME/' + native + '" "$@"\n')
            (stage / launcher).chmod(0o755)
    # Both launcher roles consume the shipped OIE includes and custom.vmoptions.
    server_options = (stage / "oieserver.vmoptions").read_text()
    (stage / "oieservice.vmoptions").write_text(server_options)
    if not (stage / "oiecommand.vmoptions").exists():
        (stage / "oiecommand.vmoptions").write_text("-include-options conf/default_modules.vmoptions\n-include-options conf/custom.vmoptions\n")
    destination = PAYLOAD / "engine"
    if destination.exists():
        remove_tree(destination)
    shutil.move(stage, destination)
    files = {p["archive"]: p["sha256"] for p in manifest["plugins"]}
    modes = {}
    for path in sorted(destination.rglob("*")):
        if path.is_symlink():
            fail("Staged engine contains an unresolved symbolic link: " + str(path))
        if path.is_file():
            relative = path.relative_to(PAYLOAD).as_posix()
            files[relative] = sha256_file(path)
            modes[relative] = 0o755 if path.stat().st_mode & 0o111 else 0o644
    manifest.update({"format": 2, "sourceArchive": source, "files": files, "modes": modes, "engineJava": runtime, "bundledJava": bundled_java,
                     "directories": [p.relative_to(destination).as_posix() for p in sorted(destination.rglob("*")) if p.is_dir()],
                     "engineLaunchers": {n: n + (".exe" if windows else "") for n in ("oieserver", "oieservice", "oiecommand")},
                     "engineRuntime": subprocess.check_output([executable(java_home, "java"), "-version"], stderr=subprocess.STDOUT, text=True),
                     "size": sum((PAYLOAD / name).stat().st_size for name in files), "packagedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
    manifest["launcher"]["kind"] = "jpackage"
    manifest["launcher"]["source"] = "jpackage engine image / EngineBootstrap.java"
    if windows:
        manifest["serviceWrapper"] = {"name": "WinSW", "version": WINSW_VERSION, "sha256": WINSW_SHA256}
    staged = PAYLOAD / "manifest.json.tmp"
    staged.write_text(json.dumps(manifest, indent=2) + "\n")
    os.replace(staged, PAYLOAD / "manifest.json")
    old = PAYLOAD / source["name"]
    if old.is_file():
        old.unlink()  # The immutable, verified source archive remains in the shared download cache.
    if (PAYLOAD / "launcher").is_dir():
        remove_tree(PAYLOAD / "launcher")
    print(f"Pinned {len(files)} staged payload files; no compressed engine archive is shipped", flush=True)
    return manifest


def verify_packaged_payload(directory: Path) -> None:
    """Check the pinned bytes after packaging/signing, without installing the engine."""
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    files = manifest["files"]
    if not files:
        fail("The packaged payload has no pinned files")
    for name, expected in files.items():
        path = directory / name
        if path.is_symlink() or not path.is_file() or sha256_file(path) != expected:
            fail("Packaging changed or omitted a pinned payload file: " + name)
    print(f"Verified all {len(files)} pinned files in {directory}", flush=True)


def verify_macos_dmg(package: Path) -> None:
    subprocess.run(["hdiutil", "verify", str(package)], check=True, stdout=subprocess.DEVNULL)
    with tempfile.TemporaryDirectory(prefix="oie-installer-dmg-") as temporary:
        mount = Path(temporary) / "mounted"
        attached = False
        try:
            subprocess.run(["hdiutil", "attach", str(package), "-readonly", "-nobrowse", "-noautoopen",
                            "-mountpoint", str(mount)], check=True, stdout=subprocess.DEVNULL)
            attached = True
            image = mount / (APP_NAME + ".app")
            subprocess.run(["codesign", "--verify", "--deep", "--strict", str(image)], check=True)
            verify_packaged_payload(image / "Contents/app/payload")
        finally:
            if attached:
                subprocess.run(["hdiutil", "detach", str(mount)], check=True, stdout=subprocess.DEVNULL)


def macos_dmg(image: Path, destination: Path) -> None:
    # jpackage's DMG bundler re-signs ad-hoc app images recursively, changing
    # the embedded engine. Wrap the final jpackage image without modifying it.
    subprocess.run(["hdiutil", "create", "-quiet", "-srcfolder", str(image.parent),
                    "-volname", APP_NAME, "-fs", "HFS+", "-format", "UDZO", str(destination)], check=True)


def windows_bootstrap(image: Path, destination: Path, version: str) -> None:
    """Self-extract the jpackage image and open our wizard directly."""
    compiler = shutil.which("makensis.exe")
    if not compiler:
        candidate = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "NSIS/makensis.exe"
        if candidate.is_file():
            compiler = str(candidate)
    if not compiler:
        fail("NSIS 3 is required for the Windows EXE wrapper. Install NSIS and add makensis.exe to PATH.")
    script = ROOT / "target/windows-bootstrap.nsi"
    def nsis(value: str) -> str:
        return value.replace("$", "$$").replace('"', '$\\"')
    script.write_text(f'''
Unicode true
!include FileFunc.nsh
Name "Open Integration Engine Installer"
OutFile "{nsis(str(destination))}"
Icon "{nsis(str(ROOT / 'packaging/icon.ico'))}"
RequestExecutionLevel user
SilentInstall silent
SetCompressor /SOLID lzma
SetDatablockOptimize on
VIProductVersion "{version}.0"
VIAddVersionKey "ProductName" "Open Integration Engine Installer"
VIAddVersionKey "FileDescription" "Open Integration Engine Installer"
VIAddVersionKey "FileVersion" "{version}"
Section
  InitPluginsDir
  SetOutPath "$PLUGINSDIR\\{APP_NAME}"
  File /r "{nsis(str(image))}\\*.*"
  ${{GetParameters}} $R0
  ExecWait '"$PLUGINSDIR\\{APP_NAME}\\{APP_NAME}.exe" $R0' $0
  SetErrorLevel $0
SectionEnd
''', encoding="utf-8")
    subprocess.run([compiler, "/V3", str(script)], check=True)


def media_name(version: str, bundled_java: bool) -> str:
    """install4j's media file names: oie_<platform>_<version>[-with-JRE]."""
    underscored, jre, arm = version.replace(".", "_"), "-with-JRE" if bundled_java else "", machine() == "arm64"
    if sys.platform == "win32":
        return f"oie_windows-{machine()}_{underscored}{jre}.exe"
    if sys.platform == "darwin":
        return f"oie_macos_{underscored}-{'aarch' if arm else 'x86'}{jre}.dmg"
    # install4j ships one Unix installer; a native installer needs one per CPU, so ARM64 adds a suffix.
    return f"oie_unix_{underscored}{'-aarch64' if arm else ''}{jre}.sh"


def unix_installer(image: Path, destination: Path) -> None:
    """A self-extracting shell installer, like install4j's oie_unix_*.sh: run it as root, or with -q -dir <folder>."""
    arch = "aarch64" if machine() == "arm64" else "x86_64"
    payload = ROOT / "target/unix-installer.tar.gz"
    with tarfile.open(payload, "w:gz") as archive:
        archive.add(image, arcname=image.name)
    digest = sha256_file(payload)
    header = f"""#!/bin/sh
# Open Integration Engine Installer
case "$(uname -m)" in
  {arch}{"|arm64" if arch == "aarch64" else "|amd64"}) ;;
  *) echo "This installer is for {arch} Linux. Use the oie_unix installer for $(uname -m)." >&2; exit 1 ;;
esac
self=$(cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")
if [ "$(id -u)" != 0 ]; then
  keep="DISPLAY XAUTHORITY WAYLAND_DISPLAY XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS XDG_CURRENT_DESKTOP DESKTOP_SESSION XDG_SESSION_TYPE XDG_CONFIG_HOME XDG_CONFIG_DIRS XDG_DATA_HOME XDG_DATA_DIRS"
  if [ ! -t 0 ] && [ -n "$DISPLAY$WAYLAND_DISPLAY" ] && command -v pkexec >/dev/null 2>&1; then
    # Started from a file manager: no terminal for sudo, so ask with the desktop's administrator prompt.
    exec pkexec env SUDO_UID="$(id -u)" SUDO_GID="$(id -g)" SUDO_USER="$(id -un)" \\
      DISPLAY="$DISPLAY" XAUTHORITY="${{XAUTHORITY:-$HOME/.Xauthority}}" WAYLAND_DISPLAY="$WAYLAND_DISPLAY" XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" \\
      DBUS_SESSION_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS" XDG_CURRENT_DESKTOP="$XDG_CURRENT_DESKTOP" XDG_SESSION_TYPE="$XDG_SESSION_TYPE" \\
      sh "$self" "$@"
  fi
  command -v sudo >/dev/null 2>&1 || {{ echo "Run this installer as root." >&2; exit 1; }}
  exec sudo --preserve-env=$(echo $keep | tr ' ' ',') sh "$self" "$@"
fi
skip=$(awk '/^__OIE_INSTALLER_PAYLOAD__$/ {{ print NR + 1; exit }}' "$self")
if command -v sha256sum >/dev/null 2>&1; then
  actual=$(tail -n +"$skip" "$self" | sha256sum | cut -d' ' -f1)
  [ "$actual" = "{digest}" ] || {{ echo "The installer is damaged. Download it again." >&2; exit 1; }}
fi
# /var/tmp is on disk; /tmp is often a small memory file system.
dir=$(mktemp -d "${{TMPDIR:-/var/tmp}}/oie-installer.XXXXXX") || exit 1
trap 'rm -rf "$dir"' EXIT HUP INT TERM
tail -n +"$skip" "$self" | tar -xzf - -C "$dir" || {{ echo "The installer is damaged. Download it again." >&2; exit 1; }}
# Use the bundled java command directly: the Linux jpackage app launcher can
# crash before Main runs. Keep the wizard and unattended modes on the same path.
app="$dir/{APP_NAME}"
"$app/lib/runtime/bin/java" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 \
  -cp "$app/lib/app/oie-installer-jpackage.jar:$app/lib/app/lib/*" \
  org.openintegrationengine.installer.Main "$@"
exit $?
__OIE_INSTALLER_PAYLOAD__
"""
    with open(destination, "wb") as out:
        out.write(header.encode("utf-8"))
        with open(payload, "rb") as source:
            shutil.copyfileobj(source, out)
    payload.unlink()
    destination.chmod(0o755)


def app_image(version: str, java_home: Path, env: dict, image_out: Path) -> Path:
    target = ROOT / "target"
    inputs = target / "app-input"
    if inputs.exists():
        remove_tree(inputs)
    inputs.mkdir()
    shutil.copyfile(target / "oie-installer-jpackage.jar", inputs / "oie-installer-jpackage.jar")
    shutil.copytree(target / "lib", inputs / "lib")
    if sys.platform != "darwin":
        shutil.copytree(PAYLOAD, inputs / "payload")
    if image_out.exists():
        remove_tree(image_out)
    args = [executable(java_home, "jpackage"), "--type", "app-image", "--name", APP_NAME,
            "--dest", str(image_out), "--input", str(inputs), "--main-jar", "oie-installer-jpackage.jar",
            "--main-class", "org.openintegrationengine.installer.Main", "--app-version", version,
            "--vendor", "Open Integration Engine contributors", "--description", "Install or upgrade Open Integration Engine",
            "--add-modules", "java.se,jdk.crypto.ec,jdk.unsupported", "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--java-options", "-Dfile.encoding=UTF-8"]
    if sys.platform == "darwin":
        args += ["--icon", str(ROOT / "packaging/icon.icns"), "--mac-package-identifier", "org.openintegrationengine.installer",
                 "--mac-package-name", "OIE Installer"]
    elif sys.platform == "win32":
        args += ["--icon", str(ROOT / "packaging/icon.ico")]
    else:
        args += ["--icon", str(ROOT / "packaging/icon.png")]
    print("Building a self-contained application image with jpackage", flush=True)
    subprocess.run(args, cwd=ROOT, env=env, check=True)
    image = image_out / (APP_NAME + (".app" if sys.platform == "darwin" else ""))
    if sys.platform == "darwin":
        # Sign the wizard before adding the already-signed, hash-pinned engine.
        # Recursive signing after embedding it would invalidate its payload hashes.
        subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(image)], check=True)
        shutil.copytree(PAYLOAD, image / "Contents/app/payload")
        subprocess.run(["codesign", "--force", "--sign", "-", str(image)], check=True)
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(image)], check=True)
    verify_packaged_payload(image / "Contents/app/payload" if sys.platform == "darwin" else image / ("app/payload" if sys.platform == "win32" else "lib/app/payload"))
    print(f"Built app: {image}", flush=True)
    return image


def build_app(version: str, java_home: Path, package_type: str, engine_java_home: Path, variants: list) -> None:
    for home in (java_home, engine_java_home):
        if not Path(executable(home, "jpackage")).is_file():
            fail(f"{home} has no jpackage. Select a full JDK 21 or later.")
    env = dict(os.environ, JAVA_HOME=str(java_home), PATH=str(java_home / "bin") + os.pathsep + os.environ.get("PATH", ""))
    print("Compiling the installer (tests skipped)", flush=True)
    maven = str(ROOT / ("mvnw.cmd" if sys.platform == "win32" else "mvnw"))
    subprocess.run([maven, "-B", "-Dmaven.test.skip=true", "package"], cwd=ROOT, env=env, check=True)
    out = ROOT / "dist"
    out.mkdir(exist_ok=True)
    built = []
    for bundled_java in variants:
        print(f"== {'With' if bundled_java else 'Without'} a bundled engine Java runtime", flush=True)
        native_payload(engine_java_home, dict(env, JAVA_HOME=str(engine_java_home)), bundled_java)
        # target/image holds the -with-JRE application; target/image-no-jre the other.
        image = app_image(version, java_home, env, ROOT / ("target/image" if bundled_java else "target/image-no-jre"))
        if package_type == "app-image":
            continue
        package_out = ROOT / "target/packages"
        if package_out.exists():
            remove_tree(package_out)
        package_out.mkdir()
        package = package_out / media_name(version, bundled_java)
        if sys.platform == "darwin" and package_type == "dmg":
            macos_dmg(image, package)
            verify_macos_dmg(package)
        elif sys.platform == "win32" and package_type == "exe":
            windows_bootstrap(image, package, version)
        elif sys.platform.startswith("linux") and package_type == "sh":
            unix_installer(image, package)
        else:
            fail(f"{package_type} packages are not built on {sys.platform}")
        destination = out / package.name
        # Replace the artifact atomically: an older DMG may still be mounted.
        partial = destination.with_name(destination.name + ".part")
        try:
            shutil.copyfile(package, partial)
            digest = sha256_file(partial)
            os.replace(partial, destination)
        finally:
            partial.unlink(missing_ok=True)
        destination.with_name(destination.name + ".sha256").write_text(f"{digest}  {destination.name}\n")
        built.append(destination.name)
        print(f"Built {destination}\nSHA-256: {digest}", flush=True)
    receipt = {"engineVersion": version, "media": built, "javaHome": str(java_home), "javaVersion": subprocess.check_output([executable(java_home, "java"), "-version"], stderr=subprocess.STDOUT, text=True),
               "engineJavaHome": str(engine_java_home), "engineJavaVersion": subprocess.check_output([executable(engine_java_home, "java"), "-version"], stderr=subprocess.STDOUT, text=True),
               "javafxVersion": "21.0.13", "architecture": machine(), "testsRun": False}
    (out / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Open Integration Engine Installer")
    parser.add_argument("--version", default="latest", help="Engine release (default: latest published)")
    parser.add_argument("--engine-repo", type=Path, default=ROOT.parent / "oie", help="Engine clone containing the release tag")
    parser.add_argument("--java-home", type=Path, help="JDK 21+ used to compile and bundle the installer runtime")
    parser.add_argument("--engine-java-home", type=Path, help="JDK 21+ bundled as the engine runtime (default: --java-home). "
                        "Windows ARM64 uses an x64 JDK for the wizard, because JavaFX 21 has no Windows ARM64 build, and an ARM64 JDK here")
    parser.add_argument("--refresh", action="store_true", help="Download release assets again rather than reusing verified cache entries")
    parser.add_argument("--payload-only", action="store_true", help="Prepare the pinned payload and stop")
    parser.add_argument("--app-only", action="store_true", help="Reuse the existing pinned payload and rebuild the app")
    parser.add_argument("--type", default="dmg" if sys.platform == "darwin" else "exe" if sys.platform == "win32" else "sh", choices=["app-image", "dmg", "exe", "sh"])
    parser.add_argument("--media", default="both", choices=["both", "with-jre", "no-jre"], help="install4j media variants: with and without a bundled engine Java runtime")
    args = parser.parse_args()
    if args.app_only:
        version = json.loads((PAYLOAD / "manifest.json").read_text())["version"]
    else:
        version = args.version
        if version == "latest":
            version = json.loads(get(f"https://api.github.com/repos/{REPOSITORY}/releases/latest"))["tag_name"].removeprefix("v")
        build_payload(version, args.engine_repo.resolve(), host_platform(), args.refresh)
    if args.payload_only:
        return 0
    java_home = args.java_home or (Path(os.environ["JAVA_HOME"]) if "JAVA_HOME" in os.environ else None)
    if java_home is None:
        if sys.platform == "darwin":
            java_home = Path(subprocess.check_output(["/usr/libexec/java_home"], text=True).strip())
        else:
            executable = shutil.which("javac")
            if not executable:
                fail("Install a JDK 21+ and pass --java-home")
            java_home = Path(executable).resolve().parent.parent
    variants = {"both": [False, True], "with-jre": [True], "no-jre": [False]}[args.media]
    build_app(version, java_home.resolve(), args.type, (args.engine_java_home or java_home).resolve(), variants)
    return 0


if __name__ == "__main__":
    sys.exit(main())
