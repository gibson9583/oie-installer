"""Install the packaged engine unattended, use its launchers, then uninstall it.

Runs against target/image after build.py. Run it as root on macOS and Linux (sudo),
or as an elevated administrator on Windows. Each pass installs a new engine into a
fresh folder, checks the install4j launcher set and verbs, and removes the engine.
The upgrade pass runs the previous install4j release first, then upgrades it.
"""

from __future__ import annotations

import json
import os
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
WINDOWS = sys.platform == "win32"
MAC = sys.platform == "darwin"
EXE = ".exe" if WINDOWS else ""
PREVIOUS = "4.6.0"
RELEASES = "https://github.com/OpenIntegrationEngine/engine/releases/download"
START_MENU = Path(os.environ.get("ProgramData", "C:/ProgramData")) / "Microsoft/Windows/Start Menu/Programs/Open Integration Engine"


def installer() -> tuple[Path, Path]:
    image = ROOT / "target/image"
    if MAC:
        contents = image / "OIE Installer JavaFX.app/Contents"
        return contents / "runtime/Contents/Home/bin/java", contents / "app"
    if WINDOWS:
        folder = image / "OIE Installer JavaFX"
        return folder / "runtime/bin/java.exe", folder / "app"
    folder = image / "OIE Installer JavaFX/lib"
    return folder / "runtime/bin/java", folder / "app"


def run(command: list, expect: int | None = 0, timeout: int = 900) -> int:
    print("+ " + " ".join(str(part) for part in command), flush=True)
    # A file, not a pipe: a detached engine must not hold the output open.
    with tempfile.TemporaryFile() as output:
        code = subprocess.run([str(part) for part in command], stdin=subprocess.DEVNULL, stdout=output,
                              stderr=subprocess.STDOUT, timeout=timeout).returncode
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


def launcher(home: Path, name: str) -> Path:
    return home / (name + EXE)


def smoke(service: bool, http: int, https: int) -> None:
    base = Path(os.environ.get("SystemDrive", "C:") + "\\") if WINDOWS else Path("/Applications") if MAC else Path("/opt")
    home = base / f"oie-smoke-{'service' if service else 'process'}-{secrets.token_hex(3)}"
    password = secrets.token_urlsafe(18)
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

            # The install4j launcher set, without install4j's runtime.
            for name in ("oieserver", "oieservice", "oiecommand", "oie-uninstall"):
                if not launcher(home, name).is_file():
                    raise SystemExit(f"Missing launcher {name}")
            for name in ("oieserver.vmoptions", "oieservice.vmoptions", "conf/mirth.properties", "mirth-server-launcher.jar"):
                if not (home / name).is_file():
                    raise SystemExit(f"Missing {name}")
            if (home / ".install4j").exists():
                raise SystemExit("install4j runtime files were installed")
            if WINDOWS:
                for entry in ("Open Integration Engine.lnk", "Uninstall Open Integration Engine.lnk"):
                    if not (START_MENU / entry).is_file():
                        raise SystemExit(f"Missing Start Menu entry {entry}")

            service_launcher = launcher(home, "oieservice")
            run([service_launcher, "status"], expect=0)
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
            run([service_launcher, "restart"], expect=0)
            wait(lambda: run([service_launcher, "status"], expect=None) == 0, "restart")
            if service:
                print("== Reinstall over the installed engine", flush=True)
                run([java, "-cp", classpath, "org.openintegrationengine.installer.Main", "--unattended", plan])
                wait(lambda: run([service_launcher, "status"], expect=None) == 0, "reinstalled engine")
        except BaseException:
            failed = True
            if home.exists():
                diagnostics(home)
            raise
        finally:
            if launcher(home, "oie-uninstall").is_file():
                print(f"== Uninstall {home}", flush=True)
                run([launcher(home, "oie-uninstall"), "--yes"], expect=None)
                # Windows removes the folder after the uninstaller exits.
                try:
                    wait(lambda: not home.exists(), "engine folder removal", seconds=180)
                except SystemExit as error:
                    if not failed:
                        raise
                    print(error, flush=True)
    if WINDOWS and any(START_MENU.glob("*.lnk")):
        raise SystemExit("Start Menu entries remain after uninstall")
    print(f"== {'Service' if service else 'Process'} pass complete", flush=True)


def previous_release(work: Path) -> Path:
    """Download and verify the previous install4j archive for this platform."""
    platform = ("windows-arm64" if os.environ.get("PROCESSOR_ARCHITECTURE", "").upper() == "ARM64" else "windows-x64") if WINDOWS else "unix"
    name = f"oie_{platform}_{PREVIOUS.replace('.', '_')}" + (".zip" if WINDOWS else ".tar.gz")
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
            print(f"== Upgrade {PREVIOUS} to the packaged release", flush=True)
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
            if launcher(home, "oie-uninstall").is_file() and (home / "oie-installer.json").is_file():
                print(f"== Uninstall {home}", flush=True)
                run([launcher(home, "oie-uninstall"), "--yes"], expect=None)
                try:
                    wait(lambda: not home.exists(), "engine folder removal", seconds=180)
                except SystemExit as error:
                    if not failed:
                        raise
                    print(error, flush=True)
            elif home.exists():
                shutil.rmtree(home, ignore_errors=True)
    print("== Upgrade pass complete", flush=True)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    os.environ["OIE_INSTALLER_DEBUG"] = "1"
    smoke(True, 18080, 18443)
    smoke(False, 28080, 28443)
    upgrade(38080, 38443)
    return 0


if __name__ == "__main__":
    sys.exit(main())
