"""Install the packaged engine unattended, use its launchers, then uninstall it.

Runs against target/image after build.py. Run it as root on macOS and Linux (sudo),
or as an elevated administrator on Windows. Each pass installs a new engine into a
fresh folder, checks the install4j launcher set and verbs, and removes the engine.
The upgrade pass runs the previous install4j release first, then upgrades it.
"""

from __future__ import annotations

import json
import os
import platform
import plistlib
import hashlib
import re
import secrets
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from windows_manifest import verify_administrator

WINDOWS = sys.platform == "win32"
MAC = sys.platform == "darwin"
EXE = ".exe" if WINDOWS else ""
PREVIOUS = "4.6.0"
RELEASES = "https://github.com/OpenIntegrationEngine/engine/releases/download"
START_MENU = Path(os.environ.get("ProgramData", "C:/ProgramData")) / "Microsoft/Windows/Start Menu/Programs/Open Integration Engine"


def installer() -> tuple[Path, Path]:
    image = ROOT / "target/image"
    if MAC:
        contents = image / "Open Integration Engine Installer.app/Contents"
        return contents / "runtime/Contents/Home/bin/java", contents / "app"
    if WINDOWS:
        folder = image / "Open Integration Engine Installer"
        return folder / "runtime/bin/java.exe", folder / "app"
    folder = image / "Open Integration Engine Installer/lib"
    return folder / "runtime/bin/java", folder / "app"


def run(command: list, expect: int | None = 0, timeout: int = 900, env: dict | None = None) -> int:
    print("+ " + " ".join(str(part) for part in command), flush=True)
    # A file, not a pipe: a detached engine must not hold the output open.
    with tempfile.TemporaryFile() as output:
        code = subprocess.run([str(part) for part in command], stdin=subprocess.DEVNULL, stdout=output,
                              stderr=subprocess.STDOUT, timeout=timeout, env=env).returncode
        output.seek(0)
        data = output.read()
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            text = data.decode("cp1252" if WINDOWS else "latin-1", "replace")
        print("\n".join(line for line in text.splitlines() if line.strip()), flush=True)
    if expect is not None and code != expect:
        raise SystemExit(f"Expected exit {expect}, got {code}")
    return code


def wait(condition, what: str, seconds: int = 300) -> None:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if condition():
            return
        time.sleep(3)
    raise SystemExit(f"Timed out waiting for {what}")


def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2):
            return True
    except OSError:
        return False


def diagnostics(home: Path) -> None:
    """Print what an engine that did not start left behind."""
    print(f"== Diagnostics for {home}", flush=True)
    receipt = home / "oie-installer.json"
    unit = ""
    if receipt.is_file():
        data = json.loads(receipt.read_text(encoding="utf-8"))
        unit = ((data.get("service") or {}).get("name")) or ""
        print(json.dumps({k: v for k, v in data.items() if k != "plugins"}, indent=2), flush=True)
    for log in sorted((home / "logs").glob("*")) if (home / "logs").is_dir() else []:
        if log.is_file():
            lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
            print(f"--- {log} (last 60 lines)\n" + "\n".join(lines[-60:]), flush=True)
    if unit and sys.platform.startswith("linux"):
        run(["systemctl", "status", "--no-pager", unit], expect=None)
        run(["journalctl", "--no-pager", "-n", "80", "-u", unit], expect=None)
    if WINDOWS:
        run(["whoami", "/user", "/groups"], expect=None)
        run(["icacls", home / "conf", "/T"], expect=None)
        run(["icacls", home / "oieserver.exe"], expect=None)
    if unit and WINDOWS:
        run(["sc.exe", "query", unit], expect=None)
    elif unit and MAC:
        run(["launchctl", "print", "system/" + unit], expect=None)


def web_client(port: int) -> bool:
    """The Web Support add-on serves the bundled OIE Web Client."""
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE  # The new engine's self-signed loopback certificate.
    try:
        with urllib.request.urlopen(f"https://localhost:{port}/oie-webadmin/", context=context, timeout=10) as response:
            return response.status == 200
    except OSError:
        return False


LINUX = sys.platform.startswith("linux")
WINDOWS_SERVICE = "Open Integration Engine Service"
WINDOWS_APPLICATION_ID = "4145-9206-7630-8076"


def windows_registration(home: Path, version: str | None, *, hive=None, bases=None) -> None:
    """Check the current application entry, or its absence after uninstall."""
    import winreg

    hive = winreg.HKEY_LOCAL_MACHINE if hive is None else hive
    bases = bases or (r"Software\Microsoft\Windows\CurrentVersion\Uninstall",
                      r"Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall")
    entries = []
    for base in bases:
        try:
            key = winreg.OpenKey(hive, base + "\\" + WINDOWS_APPLICATION_ID, 0, winreg.KEY_READ | winreg.KEY_WOW64_64KEY)
        except FileNotFoundError:
            continue
        with key:
            values = {}
            for field in ("DisplayName", "DisplayVersion", "Publisher", "URLInfoAbout", "InstallLocation", "UninstallString", "VersionMajor", "VersionMinor"):
                try:
                    values[field] = winreg.QueryValueEx(key, field)[0]
                except FileNotFoundError:
                    values[field] = None
            entries.append({"base": base, "values": values})
    print("Apps & Features: " + json.dumps(entries), flush=True)
    if version is None:
        if entries:
            raise SystemExit("Apps & Features registration remains after uninstall")
        return
    if len(entries) != 1 or entries[0]["base"] != bases[0]:
        raise SystemExit("Expected one native Apps & Features registration")
    major, minor = version.split(".")[:2]
    expected = {"DisplayName": "Open Integration Engine " + version, "DisplayVersion": version,
                "Publisher": "Open Integration Engine Project", "URLInfoAbout": "https://openintegrationengine.com",
                "InstallLocation": str(home), "UninstallString": f'"{home / "uninstall.exe"}"',
                "VersionMajor": int(major), "VersionMinor": int(minor)}
    if entries[0]["values"] != expected:
        raise SystemExit("Apps & Features registration does not describe the installed engine")


def service_definition() -> Path:
    """install4j's service file: the launch daemon or the systemd unit."""
    return Path("/Library/LaunchDaemons/com.oie.oieservice.plist") if MAC else Path("/etc/systemd/system/oieservice.service")


def windows_service_binary() -> str:
    result = subprocess.run(["sc.exe", "qc", WINDOWS_SERVICE], capture_output=True, text=True)
    match = re.search(r"BINARY_PATH_NAME\s*:\s*(.+)", result.stdout)
    return match.group(1).strip().strip('"') if match else ""


def install4j_output(home: Path, service: bool) -> None:
    """The launchers, links, program group and service that install4j creates."""
    for name in ("oieserver", "oieservice", "oiecommand", "uninstall"):
        if not launcher(home, name).is_file():
            raise SystemExit(f"Missing launcher {name}")
    for name in ("oieserver.vmoptions", "oieservice.vmoptions", "conf/mirth.properties", "mirth-server-launcher.jar"):
        if not (home / name).is_file():
            raise SystemExit(f"Missing {name}")
    if (home / ".install4j").exists():
        raise SystemExit("install4j runtime files were installed")
    if WINDOWS:
        for entry in ("Open Integration Engine Service.lnk", "Open Integration Engine.lnk"):
            if not (START_MENU / entry).is_file():
                raise SystemExit(f"Missing Start Menu entry {entry}")
        verify_administrator(home / "oiecommand.exe")
        version = json.loads((installer()[1] / "payload/manifest.json").read_text(encoding="utf-8"))["version"]
        windows_registration(home, version)
        # install4j's service program: <engine>\oieservice.exe.
        if service and windows_service_binary().lower() != str(home / "oieservice.exe").lower():
            raise SystemExit(f"The {WINDOWS_SERVICE} does not run {home / 'oieservice.exe'}")
    if LINUX:
        for name in ("oieservice", "oiecommand"):
            link = Path("/usr/local/bin", name)
            if not link.is_symlink() or Path(os.readlink(link)) != home / name:
                raise SystemExit(f"{link} does not link to {home / name}")
    if service and not WINDOWS:
        definition = service_definition()
        text = definition.read_text() if definition.is_file() else ""
        if f"{home}/oieservice" not in text or "start-launchd" not in text:
            raise SystemExit(f"{definition} is not install4j's definition for {home}")
        print(text, flush=True)


def close_console_engine(home: Path) -> None:
    """Close a Windows console engine, as a user closes its window."""
    run(["powershell.exe", "-NoProfile", "-Command",
         f"Get-CimInstance Win32_Process | Where-Object {{ $_.ExecutablePath -like '{home}\\*' }} | ForEach-Object {{ taskkill.exe /PID $_.ProcessId /T /F }}"], expect=None)


def launcher(home: Path, name: str) -> Path:
    return home / (name + EXE)


def keep_data(home: Path, plan: Path, command: list, java: Path, classpath: str, https: int) -> None:
    """Uninstall without --delete-data keeps the settings and database; installing again continues with them."""
    print("== Uninstall and keep the data", flush=True)
    run([launcher(home, "uninstall"), "--yes"])
    # Windows removes the program files after the uninstaller exits.
    wait(lambda: not (home / "server-lib").exists() and not launcher(home, "oieserver").exists(), "program file removal", seconds=180)
    for kept in ("conf/mirth.properties", "appdata/mirthdb", "logs/mirth.log", "oie-installer.json"):
        if not (home / kept).exists():
            raise SystemExit(f"The uninstaller removed {kept}")
    if launcher(home, "uninstall").exists() or (home / "extensions/websupport").exists():
        raise SystemExit("The uninstaller left program files")
    if not WINDOWS and service_definition().exists():
        raise SystemExit("The uninstaller left the service registered")
    if WINDOWS:
        windows_registration(home, None)
    print("== Install again over the kept data", flush=True)
    run([java, "-cp", classpath, "org.openintegrationengine.installer.Main", "--unattended", plan])
    wait(lambda: port_open(https), f"HTTPS port {https}")
    # The administrator password from the first installation still signs in: the database was kept.
    wait(lambda: run(command, expect=None, timeout=180) == 0, "oiecommand on the kept database", seconds=240)


def smoke(service: bool, http: int, https: int) -> None:
    base = Path(os.environ.get("SystemDrive", "C:") + "\\") if WINDOWS else Path("/Applications") if MAC else Path("/opt")
    home = base / f"oie-smoke-{'service' if service else 'process'}-{secrets.token_hex(3)}"
    # oiecommand's shipped parser can read a leading '-' as another option.
    password = "OIE-" + secrets.token_urlsafe(18)
    java, app = installer()
    with tempfile.TemporaryDirectory() as work:
        plan = Path(work) / "plan.json"
        plan.write_text(json.dumps({"installDir": str(home), "javaCmd": "@bundled", "httpPort": http, "httpsPort": https,
                                    "database": "derby", "adminPassword": password, "service": service}))
        classpath = os.pathsep.join([str(app / "oie-installer-jpackage.jar"), str(app / "lib" / "*")])
        failed = False
        try:
            print(f"== {'Service' if service else 'Process'} install into {home}", flush=True)
            run([java, "-cp", classpath, "org.openintegrationengine.installer.Main", "--unattended", plan])

            install4j_output(home, service)
            if "server.initialadminpassword" in (home / "conf/mirth.properties").read_text(encoding="latin-1"):
                raise SystemExit("The initial administrator password remains in conf/mirth.properties")

            service_launcher = launcher(home, "oieservice")
            run([service_launcher, "status"], expect=0)
            if WINDOWS and not service:
                # install4j's oieservice.exe controls only the service; a console engine stops when its window closes.
                run([service_launcher, "stop"], expect=1)
            else:
                run([service_launcher, "stop"], expect=0)
                run([service_launcher, "status"], expect=3)
                run([service_launcher, "start"], expect=0)
            wait(lambda: run([service_launcher, "status"], expect=None) == 0, "oieservice status")
            wait(lambda: port_open(https), f"HTTPS port {https}")
            wait(lambda: web_client(https), "the OIE Web Client", seconds=180)

            script = Path(work) / "cli.txt"
            script.write_text("status\n")
            command = [launcher(home, "oiecommand"), "-a", f"https://localhost:{https}", "-u", "admin", "-p", password, "-s", script]
            wait(lambda: run(command, expect=None, timeout=180) == 0, "oiecommand", seconds=240)
            if not (WINDOWS and not service):
                run([service_launcher, "restart"], expect=0)
                wait(lambda: run([service_launcher, "status"], expect=None) == 0, "restart")
            if service:
                print("== Reinstall over the installed engine", flush=True)
                run([java, "-cp", classpath, "org.openintegrationengine.installer.Main", "--unattended", plan])
                wait(lambda: run([service_launcher, "status"], expect=None) == 0, "reinstalled engine")
                keep_data(home, plan, command, java, classpath, https)
        except BaseException:
            failed = True
            if home.exists():
                diagnostics(home)
            raise
        finally:
            if WINDOWS and not service:
                close_console_engine(home)
            if launcher(home, "uninstall").is_file():
                print(f"== Uninstall {home}", flush=True)
                run([launcher(home, "uninstall"), "--yes", "--delete-data"], expect=None)
                # Windows removes the folder after the uninstaller exits.
                try:
                    wait(lambda: not home.exists(), "engine folder removal", seconds=180)
                except SystemExit as error:
                    if not failed:
                        raise
                    print(error, flush=True)
    if WINDOWS and any(START_MENU.glob("*.lnk")):
        raise SystemExit("Start Menu entries remain after uninstall")
    if WINDOWS:
        windows_registration(home, None)
    if LINUX and any(Path("/usr/local/bin", name).is_symlink() for name in ("oieservice", "oiecommand")):
        raise SystemExit("/usr/local/bin links remain after uninstall")
    if not WINDOWS and service_definition().exists():
        raise SystemExit(f"{service_definition()} remains after uninstall")
    print(f"== {'Service' if service else 'Process'} pass complete", flush=True)


def previous_release(work: Path, installer_media: bool = False) -> Path:
    """Download and verify the previous install4j archive, or its installer, for this platform."""
    version = PREVIOUS.replace(".", "_")
    arm = (os.environ.get("PROCESSOR_ARCHITECTURE", "") if WINDOWS else platform.machine()).lower() in ("arm64", "aarch64")
    if not installer_media:
        name = f"oie_{'windows-arm64' if arm else 'windows-x64'}_{version}.zip" if WINDOWS else f"oie_unix_{version}.tar.gz"
    elif WINDOWS:
        name = f"oie_{'windows-arm64' if arm else 'windows-x64'}_{version}.exe"
    elif MAC:
        name = f"oie_macos_{version}-{'aarch' if arm else 'x86'}.dmg"
    else:
        name = f"oie_unix_{version}.sh"
    with urllib.request.urlopen(f"{RELEASES}/v{PREVIOUS}/sha256sums", timeout=60) as response:
        sums = dict(reversed(line.split(None, 1)) for line in response.read().decode().splitlines() if line.strip())
    archive = work / name
    with urllib.request.urlopen(f"{RELEASES}/v{PREVIOUS}/{name}", timeout=300) as response, open(archive, "wb") as out:
        shutil.copyfileobj(response, out)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if sums.get(name.lstrip("*")) != digest and sums.get("*" + name) != digest:
        raise SystemExit(f"{name} does not match the published sha256sums")
    return archive


def engine_java_home() -> Path:
    """The Java runtime bundled with the payload, to run the previous install4j release."""
    payload = installer()[1] / "payload"
    java = payload / "engine" / json.loads((payload / "manifest.json").read_text(encoding="utf-8"))["engineJava"]
    return java.parent.parent


def upgrade(http: int, https: int) -> None:
    base = Path(os.environ.get("SystemDrive", "C:") + "\\") if WINDOWS else Path("/Applications") if MAC else Path("/opt")
    home = base / f"oie-smoke-upgrade-{secrets.token_hex(3)}"
    java, app = installer()
    classpath = os.pathsep.join([str(app / "oie-installer-jpackage.jar"), str(app / "lib" / "*")])
    with tempfile.TemporaryDirectory() as work:
        work = Path(work)
        failed = False
        try:
            print(f"== Install4j {PREVIOUS} archive into {home}", flush=True)
            archive = previous_release(work)
            unpacked = work / "unpacked"
            if WINDOWS:
                with zipfile.ZipFile(archive) as package:
                    package.extractall(unpacked)
            else:
                with tarfile.open(archive) as package:
                    package.extractall(unpacked, filter="data")
            shutil.move(str(unpacked / "oie"), str(home))
            properties = home / "conf/mirth.properties"
            text = re.sub(r"(?m)^http\.port\s*=.*$", f"http.port = {http}", properties.read_text(encoding="latin-1"))
            text = re.sub(r"(?m)^https\.port\s*=.*$", f"https.port = {https}", text)
            properties.write_text(text + "\nsmoke.marker = kept\n", encoding="latin-1")

            # Run the install4j engine once so that it creates its database.
            env = dict(os.environ, INSTALL4J_JAVA_HOME=str(engine_java_home()))
            with open(work / "previous.out", "wb") as out:
                engine = subprocess.Popen([str(home / ("oieserver" + EXE))], cwd=home, env=env, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                                          **({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if WINDOWS else {"start_new_session": True}))
            log = home / "logs/mirth.log"
            try:
                wait(lambda: log.is_file() and f"Open Integration Engine {PREVIOUS} " in log.read_text(encoding="utf-8", errors="replace")
                     and "server successfully started" in log.read_text(encoding="utf-8", errors="replace"), f"the {PREVIOUS} engine")
            finally:
                if WINDOWS:
                    run(["taskkill.exe", "/PID", engine.pid, "/T", "/F"], expect=None)
                else:
                    os.killpg(engine.pid, signal.SIGTERM)
                engine.wait(timeout=120)
            print((work / "previous.out").read_text(encoding="utf-8", errors="replace")[-2000:], flush=True)

            plan = work / "plan.json"
            plan.write_text(json.dumps({"installDir": str(home), "javaCmd": "@bundled", "service": True}))
            # A file where an add-on folder belongs makes the upgrade fail after the program files are replaced.
            print(f"== Upgrade {PREVIOUS} that fails and rolls back", flush=True)
            blocker = home / "extensions/rbac"
            blocker.write_text("not an add-on folder")
            run([java, "-cp", classpath, "org.openintegrationengine.installer.Main", "--unattended", plan], expect=1)
            receipt = json.loads((home / "oie-installer.json").read_text())
            if not receipt.get("rolledBack"):
                raise SystemExit("The failed upgrade did not roll back")
            if (home / "server-lib").exists() or launcher(home, "oieserver").exists():
                raise SystemExit("The rollback left new program files")
            if "smoke.marker = kept" not in properties.read_text(encoding="latin-1") or not (home / "appdata/mirthdb").is_dir():
                raise SystemExit("The rollback did not keep the settings and database")
            if not WINDOWS and service_definition().exists():
                raise SystemExit("The rollback left the new service registered")
            blocker.unlink()
            print(f"== Upgrade {PREVIOUS} to the packaged release after the rollback", flush=True)
            run([java, "-cp", classpath, "org.openintegrationengine.installer.Main", "--unattended", plan])
            if (home / ".install4j").exists() or not list(home.glob("backups/*/program/.install4j")):
                raise SystemExit("The install4j runtime was not moved into the backup")
            if "smoke.marker = kept" not in properties.read_text(encoding="latin-1"):
                raise SystemExit("The upgrade did not keep conf/mirth.properties")
            if not (home / "appdata/mirthdb").is_dir():
                raise SystemExit(f"The {PREVIOUS} database is missing")
            if not WINDOWS and ".launcher" not in (home / "oieserver").read_text(errors="replace"):
                raise SystemExit("oieserver is not the native launcher")
            service_launcher = launcher(home, "oieservice")
            run([service_launcher, "status"], expect=0)
            wait(lambda: web_client(https), "the OIE Web Client", seconds=180)
            script = work / "cli.txt"
            script.write_text("status\n")
            # The database came from the install4j engine, with its default administrator.
            run([launcher(home, "oiecommand"), "-a", f"https://localhost:{https}", "-u", "admin", "-p", "admin", "-s", script], timeout=180)
        except BaseException:
            failed = True
            if home.exists():
                diagnostics(home)
            raise
        finally:
            if launcher(home, "uninstall").is_file() and (home / "oie-installer.json").is_file():
                print(f"== Uninstall {home}", flush=True)
                run([launcher(home, "uninstall"), "--yes", "--delete-data"], expect=None)
                try:
                    wait(lambda: not home.exists(), "engine folder removal", seconds=180)
                except SystemExit as error:
                    if not failed:
                        raise
                    print(error, flush=True)
            elif home.exists():
                shutil.rmtree(home, ignore_errors=True)
    if WINDOWS:
        windows_registration(home, None)
    print("== Upgrade pass complete", flush=True)


def legacy_service(home: Path) -> str:
    """The service the install4j installer registered for this engine, or an empty string."""
    if WINDOWS:
        script = (f"Get-CimInstance Win32_Service | Where-Object {{ $_.PathName -like '*{home}\\oieservice.exe*' }} | "
                  "Select-Object -First 1 -ExpandProperty Name")
        result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", "$ProgressPreference='SilentlyContinue'; " + script],
                                capture_output=True, text=True)
        return result.stdout.strip().splitlines()[-1] if result.stdout.strip() else ""
    if MAC:
        plist = Path("/Library/LaunchDaemons/com.oie.oieservice.plist")
        return "com.oie.oieservice" if plist.is_file() and str(home) in plist.read_text(errors="replace") else ""
    for unit in sorted(Path("/etc/systemd/system").glob("*.service")):
        if unit.is_file() and not unit.is_symlink() and f"{home}/oieservice" in unit.read_text(errors="replace"):
            return unit.name
    return ""


def control_legacy(name: str, action: str) -> None:
    if WINDOWS:
        verb = "Stop-Service" if action == "stop" else "Start-Service"
        run(["powershell.exe", "-NoProfile", "-Command", f"$ProgressPreference='SilentlyContinue'; {verb} -Name '{name}'"], expect=None)
    elif MAC:
        plist = f"/Library/LaunchDaemons/{name}.plist"
        if action == "stop":
            run(["launchctl", "bootout", f"system/{name}"], expect=None)
        else:
            run(["launchctl", "bootstrap", "system", plist], expect=None)
            run(["launchctl", "kickstart", f"system/{name}"], expect=None)
    else:
        run(["systemctl", action, name], expect=None)


def legacy_installer(http: int, https: int) -> None:
    """Upgrade an engine installed by the previous install4j installer, with its service."""
    base = Path(os.environ.get("SystemDrive", "C:") + "\\") if WINDOWS else Path("/Applications") if MAC else Path("/opt")
    home = base / f"oie-smoke-legacy-{secrets.token_hex(3)}"
    java, app = installer()
    classpath = os.pathsep.join([str(app / "oie-installer-jpackage.jar"), str(app / "lib" / "*")])
    with tempfile.TemporaryDirectory() as work:
        work = Path(work)
        failed = False
        try:
            print(f"== Install4j {PREVIOUS} installer into {home}", flush=True)
            media = previous_release(work, installer_media=True)
            env = dict(os.environ, INSTALL4J_JAVA_HOME=str(engine_java_home()))
            if WINDOWS:
                run([media, "-q", "-dir", home], env=env)
            elif MAC:
                mount = work / "mounted"
                mount.mkdir()
                run(["hdiutil", "attach", media, "-nobrowse", "-readonly", "-mountpoint", mount])
                try:
                    bundle = next(mount.glob("*.app"))
                    executable = plistlib.loads((bundle / "Contents/Info.plist").read_bytes())["CFBundleExecutable"]
                    run([bundle / "Contents/MacOS" / executable, "-q", "-dir", home], env=env)
                finally:
                    run(["hdiutil", "detach", mount], expect=None)
            else:
                run(["sh", media, "-q", "-dir", home], env=env)
            name = legacy_service(home)
            print(f"install4j service: {name or '(none)'}", flush=True)
            if not name:
                raise SystemExit("The install4j installer registered no service for the engine")

            # Restart the install4j service on free ports so that it creates its database.
            control_legacy(name, "stop")
            properties = home / "conf/mirth.properties"
            text = re.sub(r"(?m)^http\.port\s*=.*$", f"http.port = {http}", properties.read_text(encoding="latin-1"))
            text = re.sub(r"(?m)^https\.port\s*=.*$", f"https.port = {https}", text)
            properties.write_text(text + "\nsmoke.marker = kept\n", encoding="latin-1")
            log = home / "logs/mirth.log"
            offset = log.stat().st_size if log.is_file() else 0
            control_legacy(name, "start")
            def started() -> bool:
                if not log.is_file():
                    return False
                with open(log, "rb") as handle:
                    handle.seek(offset if log.stat().st_size >= offset else 0)
                    text = handle.read().decode("utf-8", "replace")
                return f"Open Integration Engine {PREVIOUS} " in text and "server successfully started" in text
            wait(started, f"the {PREVIOUS} install4j service")

            plan = work / "plan.json"
            plan.write_text(json.dumps({"installDir": str(home), "javaCmd": "@bundled", "service": True}))
            print(f"== Upgrade the running install4j {PREVIOUS} service", flush=True)
            run([java, "-cp", classpath, "org.openintegrationengine.installer.Main", "--unattended", plan])
            # install4j and this installer use the same service name and program, oieservice.exe; the program is now this installer's.
            if WINDOWS and windows_service_binary().lower() != str(home / "oieservice.exe").lower():
                raise SystemExit("The install4j service was not replaced")
            install4j_output(home, True)
            if (home / ".install4j").exists():
                raise SystemExit("The install4j runtime was not moved into the backup")
            if "smoke.marker = kept" not in properties.read_text(encoding="latin-1"):
                raise SystemExit("The upgrade did not keep conf/mirth.properties")
            if WINDOWS:
                # The current entry uses the same application ID and uninstaller path.
                # install4j_output checked that it describes the new engine. The
                # retired entry must also be preserved in the upgrade backup.
                backups = list(home.glob("backups/*/previous-uninstall-registration.json"))
                if len(backups) != 1:
                    raise SystemExit("Missing backup of the previous Apps & Features registration")
                previous = json.loads(backups[0].read_text(encoding="utf-8-sig"))
                if not any(entry["values"].get("DisplayName") == "Open Integration Engine " + PREVIOUS for entry in previous):
                    raise SystemExit("The Apps & Features backup does not describe the previous engine")
            service_launcher = launcher(home, "oieservice")
            run([service_launcher, "status"], expect=0)
            wait(lambda: web_client(https), "the OIE Web Client", seconds=180)
            script = work / "cli.txt"
            script.write_text("status\n")
            run([launcher(home, "oiecommand"), "-a", f"https://localhost:{https}", "-u", "admin", "-p", "admin", "-s", script], timeout=180)
        except BaseException:
            failed = True
            if home.exists():
                diagnostics(home)
            if sys.platform.startswith("linux"):
                run(["sh", "-c", "ls -la /etc/systemd/system /etc/init.d | grep -i oie; grep -rl oie /etc/systemd/system 2>/dev/null"], expect=None)
            raise
        finally:
            if launcher(home, "uninstall").is_file() and (home / "oie-installer.json").is_file():
                print(f"== Uninstall {home}", flush=True)
                run([launcher(home, "uninstall"), "--yes", "--delete-data"], expect=None)
                try:
                    wait(lambda: not home.exists(), "engine folder removal", seconds=180)
                except SystemExit as error:
                    if not failed:
                        raise
                    print(error, flush=True)
    if WINDOWS:
        windows_registration(home, None)
    print("== Install4j installer upgrade pass complete", flush=True)


def quiet_no_jre() -> None:
    """The no-JRE media in install4j's unattended mode: -q -dir <folder>; the engine runs on an installed Java."""
    image = ROOT / "target/image-no-jre"
    if not image.exists():
        print("== No no-JRE image; skipped", flush=True)
        return
    base = Path(os.environ.get("SystemDrive", "C:") + "\\") if WINDOWS else Path("/Applications") if MAC else Path("/opt")
    home = base / f"oie-smoke-nojre-{secrets.token_hex(3)}"
    if MAC:
        java, app = image / "Open Integration Engine Installer.app/Contents/runtime/Contents/Home/bin/java", image / "Open Integration Engine Installer.app/Contents/app"
    elif WINDOWS:
        java, app = image / "Open Integration Engine Installer/runtime/bin/java.exe", image / "Open Integration Engine Installer/app"
    else:
        java, app = image / "Open Integration Engine Installer/lib/runtime/bin/java", image / "Open Integration Engine Installer/lib/app"
    classpath = os.pathsep.join([str(app / "oie-installer-jpackage.jar"), str(app / "lib" / "*")])
    failed = False
    try:
        print(f"== No-JRE install with -q -dir {home}", flush=True)
        run([java, "-cp", classpath, "org.openintegrationengine.installer.Main", "-q", "-dir", home])
        install4j_output(home, True)
        receipt = json.loads((home / "oie-installer.json").read_text())
        if str(home) in receipt.get("javaCmd", ""):
            raise SystemExit("The no-JRE engine runs on a bundled Java: " + receipt["javaCmd"])
        run([launcher(home, "oieservice"), "status"], expect=0)
    except BaseException:
        failed = True
        if home.exists():
            diagnostics(home)
        raise
    finally:
        if launcher(home, "uninstall").is_file():
            run([launcher(home, "uninstall"), "--yes", "--delete-data"], expect=None)
            try:
                wait(lambda: not home.exists(), "engine folder removal", seconds=180)
            except SystemExit as error:
                if not failed:
                    raise
                print(error, flush=True)
    if WINDOWS:
        windows_registration(home, None)
    print("== No-JRE pass complete", flush=True)


def downloaded_package() -> None:
    """Run the package a user downloads, the .exe, .dmg or .sh, in install4j's unattended mode."""
    packages = sorted((ROOT / "dist").glob("oie_*-with-JRE." + ("exe" if WINDOWS else "dmg" if MAC else "sh")))
    if not packages:
        raise SystemExit("No -with-JRE package in dist/")
    package = packages[0]
    base = Path(os.environ.get("SystemDrive", "C:") + "\\") if WINDOWS else Path("/Applications") if MAC else Path("/opt")
    home = base / f"oie-smoke-package-{secrets.token_hex(3)}"
    failed, mount = False, None
    try:
        print(f"== {package.name} -q -dir {home}", flush=True)
        if MAC:
            mount = Path(tempfile.mkdtemp()) / "mounted"
            mount.mkdir()
            run(["hdiutil", "attach", package, "-nobrowse", "-readonly", "-mountpoint", mount])
            command = [mount / "Open Integration Engine Installer.app/Contents/MacOS/Open Integration Engine Installer"]
        elif WINDOWS:
            command = [package]
        else:
            command = ["sh", package]
        run(command + ["-q", "-dir", home])
        install4j_output(home, True)
        run([launcher(home, "oieservice"), "status"], expect=0)
    except BaseException:
        failed = True
        if home.exists():
            diagnostics(home)
        raise
    finally:
        if mount:
            run(["hdiutil", "detach", mount], expect=None)
        if launcher(home, "uninstall").is_file():
            run([launcher(home, "uninstall"), "--yes", "--delete-data"], expect=None)
            try:
                wait(lambda: not home.exists(), "engine folder removal", seconds=180)
            except SystemExit as error:
                if not failed:
                    raise
                print(error, flush=True)
    print("== Downloaded package pass complete", flush=True)


def java_cmd(home: Path) -> bool:
    """conf/custom.vmoptions sets -java-cmd; its comments mention the directive."""
    return any(line.strip().startswith("-java-cmd ") for line in (home / "conf/custom.vmoptions").read_text().splitlines())


def archive_media(http: int, https: int) -> None:
    """The engine archive, like install4j's: unpack it, run it on an installed Java, register and remove the service."""
    archives = sorted((ROOT / "dist").glob("oie_*.zip" if WINDOWS else "oie_*.tar.gz"))
    if MAC or not archives:
        print("== No engine archive on this platform; skipped", flush=True)
        return
    base = Path(os.environ.get("SystemDrive", "C:") + "\\") if WINDOWS else Path("/opt")
    home = base / f"oie-smoke-archive-{secrets.token_hex(3)}"
    # A full Java 17+ runtime, as a user installs it: the engine runtime from the -with-JRE payload.
    env = dict(os.environ, JAVA_HOME=str(engine_java_home()))
    with tempfile.TemporaryDirectory() as work:
        work = Path(work)
        try:
            print(f"== {archives[0].name} into {home}", flush=True)
            if WINDOWS:
                with zipfile.ZipFile(archives[0]) as package:
                    package.extractall(work)
            else:
                with tarfile.open(archives[0]) as package:
                    package.extractall(work, filter="data")
            shutil.move(str(work / "oie"), str(home))
            for name in ("oieserver", "oieservice", "oiecommand"):
                if not launcher(home, name).is_file():
                    raise SystemExit(f"The archive has no {name} launcher")
            if launcher(home, "uninstall").exists() or java_cmd(home):
                raise SystemExit("The archive contains installer-only settings")
            properties = home / "conf/mirth.properties"
            text = re.sub(r"(?m)^http\.port\s*=.*$", f"http.port = {http}", properties.read_text(encoding="latin-1"))
            properties.write_text(re.sub(r"(?m)^https\.port\s*=.*$", f"https.port = {https}", text), encoding="latin-1")
            service_launcher = launcher(home, "oieservice")
            run([service_launcher, "status"], expect=3, env=env)
            if WINDOWS:
                # install4j's archive registers its service with oieservice.exe /install.
                run([service_launcher, "/install"], env=env)
                if not java_cmd(home):
                    raise SystemExit("oieservice /install did not record the Java runtime for the service")
                if windows_service_binary().lower() != str(home / "oieservice.exe").lower():
                    raise SystemExit(f"The {WINDOWS_SERVICE} does not run {home / 'oieservice.exe'}")
            else:
                run([service_launcher, "start"], env=env)
            wait(lambda: run([service_launcher, "status"], expect=None, env=env) == 0, "oieservice status")
            wait(lambda: port_open(https), f"HTTPS port {https}")
            run([service_launcher, "/uninstall" if WINDOWS else "stop"], env=env)
            wait(lambda: run([service_launcher, "status"], expect=None, env=env) == 3, "the engine to stop", seconds=120)
            wait(lambda: not port_open(https), f"HTTPS port {https} to close", seconds=120)
        except BaseException:
            if home.exists():
                diagnostics(home)
            if WINDOWS:
                run([launcher(home, "oieservice"), "/uninstall"], expect=None, env=env)
            raise
        finally:
            if not WINDOWS:
                run([launcher(home, "oieservice"), "stop"], expect=None, env=env)
            shutil.rmtree(home, ignore_errors=True)
    print("== Engine archive pass complete", flush=True)


def text_mode(http: int, https: int) -> None:
    """install4j's console mode, -c, as on a Linux server without a desktop: answer each question on standard input."""
    if not LINUX:
        print("== Text mode is checked on Linux; skipped", flush=True)
        return
    home = Path("/opt") / f"oie-smoke-text-{secrets.token_hex(3)}"
    java, app = installer()
    classpath = os.pathsep.join([str(app / "oie-installer-jpackage.jar"), str(app / "lib" / "*")])
    password = "OIE-" + secrets.token_urlsafe(18)
    # License (skip to the end, accept), folder, bundled Java, ports, Derby, password twice, normal process, three add-ons, install.
    answers = "\n".join(["a", "y", "", "", str(http), str(https), "", password, password, "n", "", "", "", ""]) + "\n"
    try:
        print(f"== Text mode -c -dir {home}", flush=True)
        env = {k: v for k, v in os.environ.items() if k not in ("DISPLAY", "WAYLAND_DISPLAY")}
        result = subprocess.run([str(java), "-cp", classpath, "org.openintegrationengine.installer.Main", "-c", "-dir", str(home)],
                                input=answers, capture_output=True, text=True, timeout=900, env=env)
        print(result.stdout[-4000:] + result.stderr[-4000:], flush=True)
        if result.returncode != 0 or f"admin / {password}" not in result.stdout:
            raise SystemExit(f"The text-mode installation failed with exit {result.returncode}")
        install4j_output(home, False)
        run([launcher(home, "oieservice"), "status"], expect=0)
        wait(lambda: web_client(https), "the OIE Web Client", seconds=180)
    except BaseException:
        if home.exists():
            diagnostics(home)
        raise
    finally:
        if launcher(home, "uninstall").is_file():
            run([launcher(home, "uninstall"), "--yes", "--delete-data"], expect=None)
        shutil.rmtree(home, ignore_errors=True)
    print("== Text mode pass complete", flush=True)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    os.environ["OIE_INSTALLER_DEBUG"] = "1"
    smoke(True, 18080, 18443)
    smoke(False, 28080, 28443)
    upgrade(38080, 38443)
    legacy_installer(48080, 48443)
    quiet_no_jre()
    downloaded_package()
    archive_media(58080, 58443)
    text_mode(19080, 19443)
    return 0


if __name__ == "__main__":
    sys.exit(main())
