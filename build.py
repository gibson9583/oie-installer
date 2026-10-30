"""Build the OIE Installer for one engine release.

python3 build.py --engine-repo ../oie --gradle-java-home <JDK 17> [--payload-only]

1. Builds the engine from its release tag in the engine repository with Gradle, or uses --engine-archive.
2. Downloads and checks the latest compatible add-ons, including the bundled web client.
3. Writes payload/ with a manifest that pins every file.
4. Compiles and tests the JavaFX wizard and packages it with jpackage.
5. Packs the engine archives (.tar.gz on Linux, .zip on Windows) and the installers.
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
PLUGINS = [
    ("websupport", "OIE Web Client", "gibson9583/oie-web-support-plugin", "websupport", "latest"),
    ("tls-manager", "TLS Manager", "NovaMap-Health/tls-manager-plugin", "tls-manager", "tags/1.1.0"),
    ("rbac", "Role-Based Access Control (RBAC)", "diridium-com/role-based-access-control", "rbac", "latest"),
    ("simple-channel-history", "Simple Channel History", "diridium-com/simple-channel-history", "simple-channel-history", "latest"),
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
    for plugin_id, name, repository, prefix, release_ref in PLUGINS:
        release = json.loads(get(f"https://api.github.com/repos/{repository}/releases/{release_ref}"))
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


def engine_commit(engine_repo: Path, version: str) -> str:
    """The commit of the engine release tag. The build uses the tag's files, not the clone's working tree."""
    result = subprocess.run(["git", "-C", str(engine_repo), "rev-parse", "--verify", "v%s^{commit}" % version], capture_output=True, text=True)
    if result.returncode != 0:
        fail("%s has no tag v%s. Fetch the engine tags, or pass --engine-repo with a clone that has them." % (engine_repo, version))
    return result.stdout.strip()


def build_engine(version: str, engine_repo: Path, gradle_java_home: Path, gradle_args: list, refresh: bool = False) -> Path:
    """Build the engine distribution from its release tag with Gradle, without install4j.

    The result is server/setup packed as oie-engine-<version>.tar.gz, with install4j's oie/ root folder. It is cached by tag commit and Gradle arguments."""
    commit = engine_commit(engine_repo, version)
    key = hashlib.sha256(json.dumps([commit, gradle_args]).encode()).hexdigest()[:16]
    cached = cache_dir() / "engine-build" / key / f"oie-engine-{version}.tar.gz"
    if not refresh and cached.is_file() and cached.with_name(cached.name + ".sha256").is_file() \
            and cached.with_name(cached.name + ".sha256").read_text().split()[0] == sha256_file(cached):
        print(f"  cached engine build {cached}")
        return cached
    source = ROOT / "target/engine-src"
    if source.exists():
        remove_tree(source)
    source.mkdir(parents=True)
    print(f"Exporting engine v{version} ({commit[:12]}) from {engine_repo}", flush=True)
    exported = subprocess.run(["git", "-C", str(engine_repo), "archive", "--format=tar", commit], check=True, capture_output=True).stdout
    with tarfile.open(fileobj=__import__("io").BytesIO(exported)) as package:
        package.extractall(source, filter="data")
    gradlew = source / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if sys.platform != "win32":
        gradlew.chmod(0o755)
    env = dict(os.environ, JAVA_HOME=str(gradle_java_home))
    command = [str(gradlew), "--no-daemon", "build", "-PdisableTests=true",
               "-Porg.gradle.java.installations.paths=" + str(gradle_java_home), *gradle_args]
    print("Building the engine: " + " ".join(command[1:]), flush=True)
    subprocess.run(command, cwd=source, env=env, check=True)
    setup = source / "server/setup"
    if not (setup / "mirth-server-launcher.jar").is_file() or not (setup / "conf/mirth.properties").is_file():
        fail("The engine build produced an incomplete server/setup")
    cached.parent.mkdir(parents=True, exist_ok=True)
    partial = cached.with_name(cached.name + ".part")
    # A reproducible archive: sorted entries, fixed owner and time.
    with tarfile.open(partial, "w:gz", format=tarfile.PAX_FORMAT) as archive:
        for path in [setup] + sorted(setup.rglob("*")):
            if path.is_symlink():
                fail("The engine build contains a link: " + str(path))
            info = archive.gettarinfo(str(path), arcname="oie" + ("/" + path.relative_to(setup).as_posix() if path != setup else ""))
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = 0
            info.mode = 0o755 if path.is_dir() or path.stat().st_mode & 0o111 else 0o644
            if path.is_file():
                with open(path, "rb") as handle:
                    archive.addfile(info, handle)
            else:
                archive.addfile(info)
    os.replace(partial, cached)
    cached.with_name(cached.name + ".sha256").write_text(f"{sha256_file(cached)}  {cached.name}\n")
    (cached.parent / "source.json").write_text(json.dumps({"repository": REPOSITORY, "tag": "v" + version, "commit": commit,
                                                           "gradle": command[1:]}, indent=2) + "\n")
    print(f"Built {cached}", flush=True)
    return cached


def build_payload(version: str, engine_archive: Path, engine_repo: Path, platform: str, refresh: bool = False) -> dict:
    tag = "v" + version
    archive = engine_archive.name
    digest = sha256_file(engine_archive)
    commit = engine_commit(engine_repo, version)
    if PAYLOAD.exists():
        for child in PAYLOAD.iterdir():
            if child.is_dir():
                remove_tree(child)
            else:
                child.unlink()
    PAYLOAD.mkdir(parents=True, exist_ok=True)
    # target/engine-archive keeps the source for each media variant; the payload copy is removed once staged.
    kept = ROOT / "target/engine-archive"
    if kept.exists():
        remove_tree(kept)
    kept.mkdir(parents=True)
    for target in (PAYLOAD / archive, kept / archive):
        try:
            os.link(engine_archive, target)
        except OSError:
            shutil.copyfile(engine_archive, target)
    print(f"Engine {archive}: {digest}", flush=True)
    files = {archive: digest}
    plugins = bundle_plugins(version, files, refresh)
    source = engine_archive.parent / "source.json"
    manifest = {
        "version": version,
        "platform": platform,
        "archive": archive,
        "size": engine_archive.stat().st_size,
        "engineSource": json.loads(source.read_text()) if source.is_file() else {"repository": REPOSITORY, "tag": tag, "commit": commit},
        "launcher": {"ref": tag, "commit": commit, "files": []},
        "files": files,
        "plugins": plugins,
        "builtAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    (PAYLOAD / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Wrote %s" % PAYLOAD)
    return manifest


APP_NAME = "Open Integration Engine Installer"


def windows_service(destination: Path, version: str) -> None:
    """Compile oieservice.exe, the Windows service program, with the .NET Framework C# compiler that Windows includes.

    AnyCPU runs natively on x64 and, with .NET Framework 4.8.1, on ARM64."""
    windows = Path(os.environ.get("SystemRoot", r"C:\Windows"))
    csc = next((windows / item / "v4.0.30319/csc.exe" for item in ("Microsoft.NET/Framework64", "Microsoft.NET/Framework")
                if (windows / item / "v4.0.30319/csc.exe").is_file()), None)
    if csc is None:
        fail("The .NET Framework C# compiler (csc.exe) is required to build oieservice.exe")
    numeric = ".".join((version.split("-")[0].split(".") + ["0", "0", "0"])[:4])
    info = ROOT / "target/OieServiceVersion.cs"
    info.write_text(f'[assembly: System.Reflection.AssemblyVersion("{numeric}")]\n[assembly: System.Reflection.AssemblyFileVersion("{numeric}")]\n', encoding="utf-8")
    subprocess.run([str(csc), "/nologo", "/target:exe", "/platform:anycpu", "/optimize+", "/reference:System.ServiceProcess.dll",
                    "/win32icon:" + str(ROOT / "packaging/icon.ico"), "/out:" + str(destination),
                    str(ROOT / "packaging/windows/OieService.cs"), str(info)], check=True)


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


def native_launchers(stage: Path, manifest: dict, java_home: Path, env: dict, bundled_java: bool) -> str:
    """Native launchers and a Java runtime from java_home's jpackage. Returns the runtime's java path in the engine folder."""
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
        # On Windows, oieservice.exe is the service program built from packaging/windows/OieService.cs.
        if launcher == name or windows and launcher == "oieservice":
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
        windows_service(stage / "oieservice.exe", manifest["version"])
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
    return runtime


PORTABLE_LAUNCHER = """#!/bin/sh
@HEADER@self=$0
while [ -h "$self" ]; do
  link=$(readlink "$self")
  case $link in /*) self=$link ;; *) self=$(dirname -- "$self")/$link ;; esac
done
OIE_HOME=$(CDPATH='' cd -- "$(dirname -- "$self")" && pwd -P) || exit 1
cd "$OIE_HOME" || exit 1
supported() {
  [ -n "$1" ] && [ -f "$1" ] && [ -x "$1" ] || return 1
  major=$("$1" -version 2>&1 | sed -n 's/.*version "\\([0-9]*\\).*/\\1/p' | head -n 1)
  [ "${major:-0}" -ge 17 ]
}
java=
configured=$(sed -n 's/^[[:space:]]*-java-cmd[[:space:]][[:space:]]*//p' conf/custom.vmoptions 2>/dev/null | tail -n 1 | tr -d "\\"'")
for candidate in "${OIE_JAVA_PATH:-}" "$configured" "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null)"; do
  if supported "$candidate"; then java=$candidate; break; fi
done
if [ -z "$java" ]; then
  echo "Open Integration Engine needs Java 17 or later. Set JAVA_HOME, or add -java-cmd <path to java> to conf/custom.vmoptions." >&2
  exit 1
fi
exec "$java" -XX:+UseSerialGC -Xmx64m -Doie.engine.home="$OIE_HOME" -Doie.engine.role=@ROLE@ \\
  -cp "$OIE_HOME/.launcher/portable/oie-installer-jpackage.jar:$OIE_HOME/.launcher/portable/lib/*" \\
  org.openintegrationengine.installer.EngineBootstrap "$@"
"""


def portable_launchers(stage: Path) -> None:
    """Script launchers that run EngineBootstrap on an installed Java, with the installer classes it needs but no JavaFX."""
    portable = stage / ".launcher/portable"
    (portable / "lib").mkdir(parents=True)
    shutil.copy2(ROOT / "target/oie-installer-jpackage.jar", portable)
    for jar in sorted((ROOT / "target/lib").glob("*.jar")):
        if not jar.name.startswith("javafx-"):
            shutil.copy2(jar, portable / "lib")
    for launcher, role in (("oieserver", "server"), ("oieservice", "service"), ("oiecommand", "command")):
        (stage / launcher).write_text(PORTABLE_LAUNCHER.replace("@HEADER@", INIT_HEADER if launcher == "oieservice" else "").replace("@ROLE@", role))
        (stage / launcher).chmod(0o755)


def native_payload(java_home: Path, env: dict, bundled_java: bool = True, portable: bool = False) -> dict:
    """Stage the verified release with its launchers.

    With bundled_java the engine image carries a full Java runtime (install4j's -with-JRE media). Without it,
    the image carries only what its launchers need, and the engine runs on an installed Java 17 or later.
    A portable engine holds no native code: script launchers run on an installed Java, on any CPU, like install4j's oie_unix media."""
    manifest = json.loads((PAYLOAD / "manifest.json").read_text())
    source = manifest.get("sourceArchive", {"name": manifest["archive"], "sha256": manifest["files"].get(manifest["archive"]), "size": manifest["size"]})
    archive = PAYLOAD / source["name"]
    if not archive.is_file():
        archive = ROOT / "target/engine-archive" / source["name"]
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

    print("Staging the engine build; the native launchers replace the portable ones", flush=True)
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
    if portable:
        portable_launchers(stage)
        runtime = ""
    else:
        runtime = native_launchers(stage, manifest, java_home, env, bundled_java)
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
                     "engineLaunchers": {n: n + (".exe" if sys.platform == "win32" and not portable else "") for n in ("oieserver", "oieservice", "oiecommand")}, "portable": portable,
                     "engineRuntime": "An installed Java 17 or later" if portable else subprocess.check_output([executable(java_home, "java"), "-version"], stderr=subprocess.STDOUT, text=True),
                     "size": sum((PAYLOAD / name).stat().st_size for name in files), "packagedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
    manifest["launcher"]["kind"] = "script" if portable else "jpackage"
    manifest["launcher"]["source"] = "EngineBootstrap.java" if portable else "jpackage engine image / EngineBootstrap.java" + (" / packaging/windows/OieService.cs" if sys.platform == "win32" else "")
    staged = PAYLOAD / "manifest.json.tmp"
    staged.write_text(json.dumps(manifest, indent=2) + "\n")
    os.replace(staged, PAYLOAD / "manifest.json")
    old = PAYLOAD / source["name"]
    if old.is_file():
        old.unlink()  # The source archive remains in target/engine-archive and the build cache.
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
    # hdiutil create fails now and then on macOS runners without a stated reason; retry, and keep its messages for the log.
    for attempt in range(3):
        destination.unlink(missing_ok=True)
        result = subprocess.run(["hdiutil", "create", "-srcfolder", str(image.parent),
                                 "-volname", APP_NAME, "-fs", "HFS+", "-format", "UDZO", str(destination)],
                                capture_output=True, text=True)
        if result.returncode == 0:
            return
        print(f"hdiutil create failed (attempt {attempt + 1} of 3): {(result.stdout + result.stderr).strip()}", flush=True)
        time.sleep(10)
    fail("hdiutil could not create " + destination.name)


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
    # Without a bundled Java the portable .sh covers every CPU. A bundled Java needs one installer per CPU, so ARM64 adds a suffix.
    return f"oie_unix_{underscored}{'-aarch64' if arm else ''}{jre}.sh"


def unix_installer(image: Path, destination: Path, portable: bool = False) -> None:
    """A self-extracting shell installer, like install4j's oie_unix_*.sh: run it as root, or with -q -dir <folder>.

    The portable installer runs on an installed Java 17 or later, on any CPU. It opens the wizard on Linux x64 and ARM64 with glibc."""
    arch = "aarch64" if machine() == "arm64" else "x86_64"
    if portable:
        requirement = """supported() {
  [ -n "$1" ] && [ -f "$1" ] && [ -x "$1" ] || return 1
  major=$("$1" -version 2>&1 | sed -n 's/.*version "\\([0-9]*\\).*/\\1/p' | head -n 1)
  [ "${major:-0}" -ge 17 ]
}
java=
for candidate in "${OIE_JAVA_PATH:-}" "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null)"; do
  if supported "$candidate"; then java=$candidate; break; fi
done
if [ -z "$java" ]; then
  echo "This installer needs Java 17 or later. Set JAVA_HOME, or use a -with-JRE installer." >&2
  exit 1
fi
# sudo resets the PATH: pass on the Java found here.
OIE_JAVA_PATH=$java
export OIE_JAVA_PATH"""
    else:
        requirement = f"""case "$(uname -m)" in
  {arch}{"|arm64" if arch == "aarch64" else "|amd64"}) ;;
  *) echo "This installer is for {arch} Linux. Use the oie_unix installer for $(uname -m)." >&2; exit 1 ;;
esac"""
    if portable:
        launch = f"""app="$dir/{APP_NAME}/app"
classpath="$app/oie-installer-jpackage.jar:$app/lib/*"
javafx=
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64|Linux-amd64) javafx=linux-x86_64 ;;
  Linux-aarch64|Linux-arm64) javafx=linux-aarch64 ;;
esac
# JavaFX needs glibc; other systems use the text installer.
for library in /lib/ld-musl-*; do
  if [ -e "$library" ]; then javafx=; fi
done
if [ -n "$javafx" ]; then classpath="$classpath:$app/javafx/$javafx/*"; fi
"$java" -Dfile.encoding=UTF-8 -cp "$classpath" org.openintegrationengine.installer.Main "$@\""""
    else:
        launch = f"""# Use the bundled java command directly: the Linux jpackage app launcher can
# crash before Main runs. Keep the wizard and unattended modes on the same path.
app="$dir/{APP_NAME}"
"$app/lib/runtime/bin/java" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 \\
  -cp "$app/lib/app/oie-installer-jpackage.jar:$app/lib/app/lib/*" \\
  org.openintegrationengine.installer.Main "$@\""""
    payload = ROOT / "target/unix-installer.tar.gz"
    payload.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(payload, "w:gz") as archive:
        archive.add(image, arcname=image.name)
    digest = sha256_file(payload)
    header = f"""#!/bin/sh
# Open Integration Engine Installer
{requirement}
self=$(cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")
if [ "$(id -u)" != 0 ]; then
  keep="DISPLAY,XAUTHORITY,WAYLAND_DISPLAY,XDG_RUNTIME_DIR,DBUS_SESSION_BUS_ADDRESS,XDG_CURRENT_DESKTOP,DESKTOP_SESSION,XDG_SESSION_TYPE,XDG_CONFIG_HOME,XDG_CONFIG_DIRS,XDG_DATA_HOME,XDG_DATA_DIRS,OIE_JAVA_PATH"
  if [ ! -t 0 ] && [ -n "$DISPLAY$WAYLAND_DISPLAY" ] && command -v pkexec >/dev/null 2>&1; then
    # Started from a file manager: no terminal for sudo, so ask with the desktop's administrator prompt.
    exec pkexec env SUDO_UID="$(id -u)" SUDO_GID="$(id -g)" SUDO_USER="$(id -un)" \\
      DISPLAY="$DISPLAY" XAUTHORITY="${{XAUTHORITY:-$HOME/.Xauthority}}" WAYLAND_DISPLAY="$WAYLAND_DISPLAY" XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" \\
      DBUS_SESSION_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS" XDG_CURRENT_DESKTOP="$XDG_CURRENT_DESKTOP" XDG_SESSION_TYPE="$XDG_SESSION_TYPE" OIE_JAVA_PATH="${{OIE_JAVA_PATH:-}}" \\
      sh "$self" "$@"
  fi
  command -v sudo >/dev/null 2>&1 || {{ echo "Run this installer as root." >&2; exit 1; }}
  exec sudo --preserve-env="$keep" sh "$self" "$@"
fi
skip=$(awk '/^__OIE_INSTALLER_PAYLOAD__$/ {{ print NR + 1; exit }}' "$self")
if command -v sha256sum >/dev/null 2>&1; then
  actual=$(tail -n +"$skip" "$self" | sha256sum | cut -d' ' -f1)
  [ "$actual" = "{digest}" ] || {{ echo "The installer is damaged. Download it again." >&2; exit 1; }}
elif command -v shasum >/dev/null 2>&1; then
  actual=$(tail -n +"$skip" "$self" | shasum -a 256 | cut -d' ' -f1)
  [ "$actual" = "{digest}" ] || {{ echo "The installer is damaged. Download it again." >&2; exit 1; }}
fi
# /var/tmp is on disk; /tmp is often a small memory file system.
dir=$(mktemp -d "${{TMPDIR:-/var/tmp}}/oie-installer.XXXXXX") || exit 1
trap 'rm -rf "$dir"' EXIT HUP INT TERM
tail -n +"$skip" "$self" | tar -xzf - -C "$dir" || {{ echo "The installer is damaged. Download it again." >&2; exit 1; }}
{launch}
exit $?
__OIE_INSTALLER_PAYLOAD__
"""
    with open(destination, "wb") as out:
        out.write(header.encode("utf-8"))
        with open(payload, "rb") as source:
            shutil.copyfileobj(source, out)
    payload.unlink()
    destination.chmod(0o755)


def jdk_javafx(java_home: Path) -> bool:
    """A JDK that includes JavaFX, such as Liberica Full JDK. Windows ARM64 needs it: OpenJFX publishes no Windows ARM64 build."""
    return (java_home / "jmods/javafx.controls.jmod").is_file()


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
            "--add-modules", "java.se,jdk.crypto.ec,jdk.unsupported" + (",javafx.controls" if jdk_javafx(java_home) else ""), "--java-options", "--enable-native-access=ALL-UNNAMED",
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


def archive_media_name(version: str) -> str | None:
    """install4j's Windows archive name. The Unix archive, oie_unix_<version>.tar.gz, is portable: see build_portable."""
    return f"oie_windows-{machine()}_{version.replace('.', '_')}.zip" if sys.platform == "win32" else None


def engine_archive_media(version: str, destination: Path) -> None:
    """The engine without an installer: the staged no-JRE engine under oie/. It runs on an installed Java 17 or later."""
    engine = PAYLOAD / "engine"
    modes = json.loads((PAYLOAD / "manifest.json").read_text())["modes"]
    # The uninstaller needs the installer's receipt; an unpacked archive is removed by deleting its folder.
    excluded = {"uninstall.exe"}
    paths = [p for p in sorted(engine.rglob("*")) if p.relative_to(engine).parts[0] not in excluded]
    if destination.suffix == ".zip":
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in paths:
                name = "oie/" + path.relative_to(engine).as_posix()
                if path.is_dir():
                    archive.writestr(zipfile.ZipInfo(name + "/", (1980, 1, 1, 0, 0, 0)), b"")
                else:
                    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = modes.get("engine/" + path.relative_to(engine).as_posix(), 0o644) << 16
                    with open(path, "rb") as handle:
                        archive.writestr(info, handle.read())
    else:
        with tarfile.open(destination, "w:gz", format=tarfile.PAX_FORMAT) as archive:
            for path in [engine] + paths:
                relative = path.relative_to(engine).as_posix()
                info = archive.gettarinfo(str(path), arcname="oie" + ("" if path == engine else "/" + relative))
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                info.mtime = 0
                info.mode = 0o755 if path.is_dir() else modes.get("engine/" + relative, 0o644)
                if path.is_file():
                    with open(path, "rb") as handle:
                        archive.addfile(info, handle)
                else:
                    archive.addfile(info)


def publish(package: Path, out: Path) -> str:
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
    print(f"Built {destination}\nSHA-256: {digest}", flush=True)
    return destination.name


def javafx_jars(destination: Path, classifier: str, env: dict) -> None:
    """The JavaFX jars for one platform, for the portable installer's wizard."""
    version = re.search(r"<javafx.version>([^<]+)<", (ROOT / "pom.xml").read_text()).group(1)
    maven = str(ROOT / ("mvnw.cmd" if sys.platform == "win32" else "mvnw"))
    destination.mkdir(parents=True)
    for module in ("base", "graphics", "controls"):
        subprocess.run([maven, "-B", "-q", "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy",
                        f"-Dartifact=org.openjfx:javafx-{module}:{version}:jar:{classifier}", f"-DoutputDirectory={destination}"],
                       cwd=ROOT, env=env, check=True)


def build_portable(version: str, java_home: Path, coverage: bool = False) -> None:
    """oie_unix_<version>.tar.gz and oie_unix_<version>.sh: one build for every CPU, on an installed Java 17 or later, like install4j's."""
    env = dict(os.environ, JAVA_HOME=str(java_home), PATH=str(java_home / "bin") + os.pathsep + os.environ.get("PATH", ""))
    maven = str(ROOT / ("mvnw.cmd" if sys.platform == "win32" else "mvnw"))
    print("Compiling and testing the installer", flush=True)
    subprocess.run([maven, "-B", *(["-Pcoverage"] if coverage else []), "package"], cwd=ROOT, env=env, check=True)
    native_payload(java_home, env, bundled_java=False, portable=True)
    underscored = version.replace(".", "_")
    package_out, out = ROOT / "target/packages", ROOT / "dist"
    if package_out.exists():
        remove_tree(package_out)
    package_out.mkdir()
    out.mkdir(exist_ok=True)
    archive = package_out / f"oie_unix_{underscored}.tar.gz"
    print(f"Packing {archive.name}", flush=True)
    engine_archive_media(version, archive)
    built = [publish(archive, out)]
    image = ROOT / "target/image-portable"
    if image.exists():
        remove_tree(image)
    app = image / APP_NAME / "app"
    (app / "lib").mkdir(parents=True)
    shutil.copy2(ROOT / "target/oie-installer-jpackage.jar", app)
    for jar in sorted((ROOT / "target/lib").glob("*.jar")):
        if not jar.name.startswith("javafx-"):
            shutil.copy2(jar, app / "lib")
    javafx_jars(app / "javafx/linux-x86_64", "linux", env)
    javafx_jars(app / "javafx/linux-aarch64", "linux-aarch64", env)
    shutil.copytree(PAYLOAD, app / "payload")
    verify_packaged_payload(app / "payload")
    package = package_out / f"oie_unix_{underscored}.sh"
    unix_installer(image / APP_NAME, package, portable=True)
    built.append(publish(package, out))
    (out / "build-receipt-portable.json").write_text(json.dumps({"engineVersion": version, "media": built, "portable": True, "testsRun": True}, indent=2) + "\n")


def build_app(version: str, java_home: Path, package_type: str, engine_java_home: Path, variants: list, coverage: bool = False) -> None:
    for home in (java_home, engine_java_home):
        if not Path(executable(home, "jpackage")).is_file():
            fail(f"{home} has no jpackage. Select a full JDK 21 or later.")
    env = dict(os.environ, JAVA_HOME=str(java_home), PATH=str(java_home / "bin") + os.pathsep + os.environ.get("PATH", ""))
    print("Compiling and testing the installer", flush=True)
    maven = str(ROOT / ("mvnw.cmd" if sys.platform == "win32" else "mvnw"))
    # With a JDK that includes JavaFX, the JavaFX jars are for compiling only; jpackage takes the modules from the JDK.
    javafx = ["-Djavafx.scope=provided", "-Djavafx.platform=win"] if jdk_javafx(java_home) else []
    subprocess.run([maven, "-B", *javafx, *(["-Pcoverage"] if coverage else []), "package"], cwd=ROOT, env=env, check=True)
    out = ROOT / "dist"
    out.mkdir(exist_ok=True)
    built = []
    for bundled_java in variants:
        print(f"== {'With' if bundled_java else 'Without'} a bundled engine Java runtime", flush=True)
        native_payload(engine_java_home, dict(env, JAVA_HOME=str(engine_java_home)), bundled_java)
        package_out = ROOT / "target/packages"
        if package_out.exists():
            remove_tree(package_out)
        package_out.mkdir()
        if not bundled_java and package_type != "app-image" and archive_media_name(version):
            archive = package_out / archive_media_name(version)
            print(f"Packing {archive.name}", flush=True)
            engine_archive_media(version, archive)
            built.append(publish(archive, out))
        # target/image holds the -with-JRE application; target/image-no-jre the other.
        image = app_image(version, java_home, env, ROOT / ("target/image" if bundled_java else "target/image-no-jre"))
        if package_type == "app-image":
            continue
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
        built.append(publish(package, out))
    receipt = {"engineVersion": version, "media": built, "javaHome": str(java_home), "javaVersion": subprocess.check_output([executable(java_home, "java"), "-version"], stderr=subprocess.STDOUT, text=True),
               "engineJavaHome": str(engine_java_home), "engineJavaVersion": subprocess.check_output([executable(engine_java_home, "java"), "-version"], stderr=subprocess.STDOUT, text=True),
               "javafx": "JDK " + str(java_home) if jdk_javafx(java_home) else "OpenJFX 21.0.13", "architecture": machine(), "testsRun": True}
    (out / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Open Integration Engine Installer")
    parser.add_argument("--version", default="latest", help="Engine release (default: latest published)")
    parser.add_argument("--engine-repo", type=Path, default=ROOT.parent / "oie", help="Engine clone containing the release tag")
    parser.add_argument("--java-home", type=Path, help="JDK 21+ used to compile and bundle the installer runtime")
    parser.add_argument("--engine-java-home", type=Path, help="JDK 21+ bundled as the engine runtime (default: --java-home). "
                        "On Windows ARM64, --java-home is a JDK that includes JavaFX, such as Liberica Full JDK, because OpenJFX has no Windows ARM64 build")
    parser.add_argument("--gradle-java-home", type=Path, help="JDK 17 that builds the engine with Gradle (default: --java-home)")
    parser.add_argument("--engine-gradle-arg", action="append", default=[], metavar="ARG",
                        help="Extra Gradle argument for the engine build, such as -Pkeystore_property_file=<file> to sign with the release certificate. Repeatable")
    parser.add_argument("--engine-archive", type=Path, help="Use this engine build (oie-engine-<version>.tar.gz from --engine-only) instead of building it")
    parser.add_argument("--engine-only", action="store_true", help="Build the engine archive into dist/engine and stop")
    parser.add_argument("--portable", action="store_true", help="Build the portable oie_unix .tar.gz and .sh, on an installed Java for any CPU, and stop")
    parser.add_argument("--coverage", action="store_true", help="Write a JaCoCo report of the unit tests to target/site/jacoco")
    parser.add_argument("--refresh", action="store_true", help="Build the engine and download add-ons again rather than reusing verified cache entries")
    parser.add_argument("--payload-only", action="store_true", help="Prepare the pinned payload and stop")
    parser.add_argument("--app-only", action="store_true", help="Reuse the existing pinned payload and rebuild the app")
    parser.add_argument("--type", default="dmg" if sys.platform == "darwin" else "exe" if sys.platform == "win32" else "sh", choices=["app-image", "dmg", "exe", "sh"])
    parser.add_argument("--media", default="both", choices=["both", "with-jre", "no-jre"], help="install4j media variants: with and without a bundled engine Java runtime")
    args = parser.parse_args()
    java_home = args.java_home or (Path(os.environ["JAVA_HOME"]) if "JAVA_HOME" in os.environ else None)
    if java_home is None:
        if sys.platform == "darwin":
            java_home = Path(subprocess.check_output(["/usr/libexec/java_home"], text=True).strip())
        else:
            javac = shutil.which("javac")
            if not javac:
                fail("Install a JDK 21+ and pass --java-home")
            java_home = Path(javac).resolve().parent.parent
    if args.app_only:
        version = json.loads((PAYLOAD / "manifest.json").read_text())["version"]
    else:
        version = args.version
        if version == "latest":
            version = json.loads(get(f"https://api.github.com/repos/{REPOSITORY}/releases/latest"))["tag_name"].removeprefix("v")
        engine_repo = args.engine_repo.resolve()
        if args.engine_archive:
            engine = args.engine_archive.resolve()
            if not engine.is_file():
                fail(f"{engine} does not exist")
        else:
            engine = build_engine(version, engine_repo, (args.gradle_java_home or java_home).resolve(), args.engine_gradle_arg, args.refresh)
        if args.engine_only:
            out = ROOT / "dist/engine"
            out.mkdir(parents=True, exist_ok=True)
            for item in (engine, engine.with_name(engine.name + ".sha256"), engine.parent / "source.json"):
                if item.is_file():
                    shutil.copyfile(item, out / item.name)
            print(f"Engine build in {out}", flush=True)
            return 0
        build_payload(version, engine, engine_repo, host_platform(), args.refresh)
    if args.payload_only:
        return 0
    if args.portable:
        build_portable(version, java_home.resolve(), args.coverage)
        return 0
    variants = {"both": [False, True], "with-jre": [True], "no-jre": [False]}[args.media]
    if sys.platform.startswith("linux"):
        # The portable .sh is the Linux installer without a bundled Java.
        variants = [True]
    build_app(version, java_home.resolve(), args.type, (args.engine_java_home or java_home).resolve(), variants, args.coverage)
    return 0


if __name__ == "__main__":
    sys.exit(main())
