"""Install the packaged engine unattended, use its launchers, then uninstall it.

Runs against target/image after build.py. Run it as root on macOS and Linux (sudo),
or as an elevated administrator on Windows. Each pass installs a new engine into a
fresh folder, checks the install4j launcher set and verbs, and removes the engine.
"""

from __future__ import annotations

import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WINDOWS = sys.platform == "win32"
MAC = sys.platform == "darwin"
EXE = ".exe" if WINDOWS else ""
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
    if unit and WINDOWS:
        run(["sc.exe", "query", unit], expect=None)
    elif unit and MAC:
        run(["launchctl", "print", "system/" + unit], expect=None)


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

            script = Path(work) / "cli.txt"
            script.write_text("status\n")
            command = [launcher(home, "oiecommand"), "-a", f"https://localhost:{https}", "-u", "admin", "-p", password, "-s", script]
            wait(lambda: run(command, expect=None, timeout=180) == 0, "oiecommand", seconds=240)
            run([service_launcher, "restart"], expect=0)
            wait(lambda: run([service_launcher, "status"], expect=None) == 0, "restart")
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


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    os.environ["OIE_INSTALLER_DEBUG"] = "1"
    smoke(True, 18080, 18443)
    smoke(False, 28080, 28443)
    return 0


if __name__ == "__main__":
    sys.exit(main())
