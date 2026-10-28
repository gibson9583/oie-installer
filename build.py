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
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "oie-installer-build"})
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
        import platform
        return "windows-arm64" if platform.machine().lower() in ("arm64", "aarch64") else "windows-x64"
    return "unix"


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
                shutil.rmtree(child)
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


APP_NAME = "OIE Installer JavaFX"
WINSW_VERSION = "2.12.0"
WINSW_SHA256 = "05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da"


def executable(java_home: Path, name: str) -> str:
    return str(java_home / "bin" / (name + (".exe" if sys.platform == "win32" else "")))


def native_payload(java_home: Path, env: dict) -> dict:
    """Stage verified release files and a separate, native engine image at build time."""
    manifest = json.loads((PAYLOAD / "manifest.json").read_text())
    source = manifest.get("sourceArchive", {"name": manifest["archive"], "sha256": manifest["files"].get(manifest["archive"]), "size": manifest["size"]})
    archive = PAYLOAD / source["name"]
    if not archive.is_file():
        archive = cache_dir() / source["name"]
    if not archive.is_file() or sha256_file(archive) != source["sha256"]:
        fail("The verified source archive is unavailable. Run the build without --app-only to prepare it.")
    stage = ROOT / "target/native-engine"
    if stage.exists():
        shutil.rmtree(stage)
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
        shutil.rmtree(inputs)
    inputs.mkdir()
    shutil.copy2(ROOT / "target/oie-installer-jpackage.jar", inputs)
    shutil.copytree(ROOT / "target/lib", inputs / "lib")
    native_out = ROOT / "target/engine-image"
    if native_out.exists():
        shutil.rmtree(native_out)
    mac = sys.platform == "darwin"
    windows = sys.platform == "win32"
    name = "OIE Engine" if mac else "oieserver"
    relative_home = "$APPDIR/../../../.." if not windows else "$APPDIR/.."
    args = [executable(java_home, "jpackage"), "--type", "app-image", "--name", name, "--dest", str(native_out),
            "--input", str(inputs), "--main-jar", "oie-installer-jpackage.jar", "--main-class", "org.openintegrationengine.installer.EngineBootstrap",
            "--app-version", manifest["version"], "--vendor", "Open Integration Engine contributors", "--add-modules", "ALL-MODULE-PATH",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--java-options", "-Doie.engine.home=" + relative_home, "--java-options", "-Doie.engine.role=server"]
    launcher_configs = ROOT / "target/engine-launchers"
    launcher_configs.mkdir(exist_ok=True)
    for launcher, role in [("oieserver", "server"), ("oieservice", "service"), ("oiecommand", "command"), ("oie-uninstall", "uninstall")]:
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
            (stage / launcher).write_text('#!/bin/sh\nOIE_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1\ncd "$OIE_HOME" || exit 1\nexec "$OIE_HOME/' + native + '" "$@"\n')
            (stage / launcher).chmod(0o755)
    # Both launcher roles consume the shipped OIE includes and custom.vmoptions.
    server_options = (stage / "oieserver.vmoptions").read_text()
    (stage / "oieservice.vmoptions").write_text(server_options)
    if not (stage / "oiecommand.vmoptions").exists():
        (stage / "oiecommand.vmoptions").write_text("-include-options conf/default_modules.vmoptions\n-include-options conf/custom.vmoptions\n")
    destination = PAYLOAD / "engine"
    if destination.exists():
        shutil.rmtree(destination)
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
    manifest.update({"format": 2, "sourceArchive": source, "files": files, "modes": modes, "engineJava": runtime,
                     "directories": [p.relative_to(destination).as_posix() for p in sorted(destination.rglob("*")) if p.is_dir()],
                     "engineLaunchers": {n: n + (".exe" if windows else "") for n in ("oieserver", "oieservice", "oiecommand")},
                     "engineRuntime": subprocess.check_output([executable(java_home, "java"), "-version"], stderr=subprocess.STDOUT, text=True),
                     "size": sum((PAYLOAD / name).stat().st_size for name in files), "packagedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
    manifest["launcher"]["kind"] = "jpackage"
    manifest["launcher"]["source"] = "jpackage engine image / EngineBootstrap.java"
    if windows:
        manifest["serviceWrapper"] = {"name": "WinSW", "version": WINSW_VERSION, "sha256": WINSW_SHA256}
    (PAYLOAD / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    old = PAYLOAD / source["name"]
    if old.is_file():
        old.unlink()  # The immutable, verified source archive remains in the shared download cache.
    if (PAYLOAD / "launcher").is_dir():
        shutil.rmtree(PAYLOAD / "launcher")
    print(f"Pinned {len(files)} staged payload files; no compressed engine archive is shipped", flush=True)
    return manifest


def link_or_copy(source: Path, target: Path) -> None:
    source, target = Path(source), Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.link(source, target)
    except OSError:
        shutil.copyfile(source, target)


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
  SetOutPath "$PLUGINSDIR\\OIE Installer JavaFX"
  File /r "{nsis(str(image))}\\*.*"
  ExecWait '"$PLUGINSDIR\\OIE Installer JavaFX\\OIE Installer JavaFX.exe"' $0
  SetErrorLevel $0
SectionEnd
''', encoding="utf-8")
    subprocess.run([compiler, "/V3", str(script)], check=True)


def build_app(version: str, java_home: Path, package_type: str) -> None:
    if not Path(executable(java_home, "jpackage")).is_file():
        fail(f"{java_home} has no jpackage. Select a full JDK 21 or later with --java-home.")
    env = dict(os.environ, JAVA_HOME=str(java_home), PATH=str(java_home / "bin") + os.pathsep + os.environ.get("PATH", ""))
    print("Compiling the native JavaFX wizard (tests skipped)", flush=True)
    subprocess.run(["mvn.cmd" if sys.platform == "win32" else "mvn", "-B", "-Dmaven.test.skip=true", "package"], cwd=ROOT, env=env, check=True)
    native_payload(java_home, env)
    target = ROOT / "target"
    inputs = target / "app-input"
    if inputs.exists():
        shutil.rmtree(inputs)
    inputs.mkdir()
    shutil.copyfile(target / "oie-installer-jpackage.jar", inputs / "oie-installer-jpackage.jar")
    shutil.copytree(target / "lib", inputs / "lib")
    if sys.platform != "darwin":
        shutil.copytree(PAYLOAD, inputs / "payload", copy_function=link_or_copy)
    image_out = target / "image"
    if image_out.exists():
        shutil.rmtree(image_out)
    args = [executable(java_home, "jpackage"), "--type", "app-image", "--name", APP_NAME,
            "--dest", str(image_out), "--input", str(inputs), "--main-jar", "oie-installer-jpackage.jar",
            "--main-class", "org.openintegrationengine.installer.Main", "--app-version", version,
            "--vendor", "Open Integration Engine contributors", "--description", "Install or upgrade Open Integration Engine",
            "--add-modules", "java.se,jdk.crypto.ec,jdk.unsupported", "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--java-options", "-Dfile.encoding=UTF-8"]
    if sys.platform == "darwin":
        args += ["--icon", str(ROOT / "packaging/icon.icns"), "--mac-package-identifier", "org.openintegrationengine.installer.javafx",
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
    payload_in_image = image / "Contents/app/payload" if sys.platform == "darwin" else image / ("app/payload" if sys.platform == "win32" else "lib/app/payload")
    verify_packaged_payload(payload_in_image)
    print(f"Built app: {image}", flush=True)
    if package_type == "app-image":
        return
    package_out = target / "packages"
    if package_out.exists():
        shutil.rmtree(package_out)
    package_out.mkdir()
    args = [executable(java_home, "jpackage"), "--type", package_type, "--app-image", str(image), "--name", APP_NAME,
            "--app-version", version, "--dest", str(package_out), "--resource-dir", str(ROOT / "packaging")]
    if sys.platform == "darwin":
        args += ["--mac-package-name", "OIE Installer"]
    elif sys.platform == "win32":
        args += ["--win-menu", "--win-shortcut", "--win-upgrade-uuid", "649c44c5-2d75-44d5-a3dc-d79ee02c4641"]
    if sys.platform == "darwin" and package_type == "dmg":
        macos_dmg(image, package_out / (APP_NAME + "-" + version + ".dmg"))
    elif sys.platform == "win32" and package_type == "exe":
        windows_bootstrap(image, package_out / "OIE-Installer.exe", version)
    else:
        subprocess.run(args, cwd=ROOT, env=env, check=True)
    out = ROOT / "dist"
    out.mkdir(exist_ok=True)
    architecture = "arm64" if host.machine().lower() in ("aarch64", "arm64") else "x64"
    packages = list(package_out.glob("*." + package_type))
    if not packages:
        fail("jpackage produced no " + package_type + " package")
    for package in packages:
        if package_type == "dmg":
            verify_macos_dmg(package)
        platform_name = "windows" if sys.platform == "win32" else "macos" if sys.platform == "darwin" else "linux"
        destination = out / f"OIE-Installer-JavaFX-{version}-{platform_name}-{architecture}.{package_type}"
        # Replace the artifact atomically: an older DMG may still be mounted.
        partial = destination.with_name(destination.name + ".part")
        try:
            shutil.copyfile(package, partial)
            digest = sha256_file(partial)
            os.replace(partial, destination)
        finally:
            partial.unlink(missing_ok=True)
        destination.with_name(destination.name + ".sha256").write_text(f"{digest}  {destination.name}\n")
        print(f"Built {destination}\nSHA-256: {digest}", flush=True)
    receipt = {"engineVersion": version, "javaHome": str(java_home), "javaVersion": subprocess.check_output([executable(java_home, "java"), "-version"], stderr=subprocess.STDOUT, text=True),
               "javafxVersion": "21.0.13", "architecture": architecture, "application": str(image), "testsRun": False}
    (out / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the alternative JavaFX + jpackage OIE Installer")
    parser.add_argument("--version", default="latest", help="Engine release (default: latest published)")
    parser.add_argument("--engine-repo", type=Path, default=ROOT.parent / "oie", help="Engine clone containing the release tag")
    parser.add_argument("--java-home", type=Path, help="JDK 21+ used to compile and bundle the installer runtime")
    parser.add_argument("--refresh", action="store_true", help="Download release assets again rather than reusing verified cache entries")
    parser.add_argument("--payload-only", action="store_true", help="Prepare the pinned payload and stop")
    parser.add_argument("--app-only", action="store_true", help="Reuse the existing pinned payload and rebuild the app")
    parser.add_argument("--type", default="dmg" if sys.platform == "darwin" else "exe" if sys.platform == "win32" else "deb", choices=["app-image", "dmg", "pkg", "exe", "msi", "deb", "rpm"])
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
    build_app(version, java_home.resolve(), args.type)
    return 0


if __name__ == "__main__":
    sys.exit(main())
