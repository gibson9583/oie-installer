"""Install the engine through the packaged JavaFX wizard, as a user does, then check the result.

Runs on Linux after build.py, as root, under a virtual display:
  sudo env JAVA_HOME=... xvfb-run -a -s "-screen 0 1280x900x24" python3 ci/check_wizard.py
WizardEndToEnd.java clicks through every page, checks validation and navigation, installs with
RBAC cleared, checks the Done page, and opens the wizard again on the installed engine.
Screenshots go to target/wizard-screens.
"""

from __future__ import annotations

import os
import secrets
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "ci"))
import smoke_install as smoke  # noqa: E402


def main() -> int:
    if not sys.platform.startswith("linux"):
        print("The wizard end-to-end check runs on Linux under xvfb; skipped")
        return 0
    if not os.environ.get("DISPLAY"):
        raise SystemExit("Run under xvfb-run: no DISPLAY")
    java, app = smoke.installer()
    javac = Path(os.environ["JAVA_HOME"]) / "bin/javac"
    home = Path("/opt") / f"oie-smoke-wizard-{secrets.token_hex(3)}"
    http, https = 39080, 39443
    password = "OIE-" + secrets.token_urlsafe(18)
    shots = ROOT / "target/wizard-screens"
    shutil.rmtree(shots, ignore_errors=True)
    classpath = os.pathsep.join([str(app / "oie-installer-jpackage.jar"), str(app / "lib" / "*")])
    with tempfile.TemporaryDirectory() as work:
        subprocess.run([str(javac), "-d", work, "-cp", classpath, str(ROOT / "ci/WizardEndToEnd.java")], check=True, timeout=120)
        try:
            print(f"== Wizard installation into {home}", flush=True)
            # Software rendering: the virtual display has no GPU.
            smoke.run([java, "--enable-native-access=ALL-UNNAMED", "-Dprism.order=sw", "-Doie.reduceMotion=true",
                       "-cp", classpath + os.pathsep + work, "WizardEndToEnd", home, http, https, password, shots], timeout=1200)

            smoke.install4j_output(home, True)
            if "server.initialadminpassword" in (home / "conf/mirth.properties").read_text(encoding="latin-1"):
                raise SystemExit("The initial administrator password remains in conf/mirth.properties")
            if (home / "extensions/rbac").exists() or not (home / "extensions/websupport").is_dir():
                raise SystemExit("The add-ons do not match the wizard's choices")
            smoke.run([smoke.launcher(home, "oieservice"), "status"], expect=0)
            smoke.wait(lambda: smoke.web_client(https), "the OIE Web Client", seconds=180)
            script = Path(work) / "cli.txt"
            script.write_text("status\n")
            command = [smoke.launcher(home, "oiecommand"), "-a", f"https://localhost:{https}", "-u", "admin", "-p", password, "-s", script]
            smoke.wait(lambda: smoke.run(command, expect=None, timeout=180) == 0, "oiecommand with the wizard's password", seconds=240)
        except BaseException:
            if home.exists():
                smoke.diagnostics(home)
            raise
        finally:
            if smoke.launcher(home, "uninstall").is_file():
                smoke.run([smoke.launcher(home, "uninstall"), "--yes", "--delete-data"], expect=None)
            shutil.rmtree(home, ignore_errors=True)
    print("== Wizard end-to-end pass complete", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
