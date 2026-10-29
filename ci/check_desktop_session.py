"""Check desktop command plans and native Linux sudo UID transitions without opening a browser."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent.parent


def check() -> None:
    suffix = ".exe" if os.name == "nt" else ""
    java_home = os.environ.get("JAVA_HOME")
    executable = lambda name: str(Path(java_home) / "bin" / (name + suffix)) if java_home else shutil.which(name)
    classpath = os.pathsep.join([str(ROOT / "target/classes"), str(ROOT / "target/lib/*")])
    with tempfile.TemporaryDirectory(prefix="oie-desktop-check-") as work:
        subprocess.run([executable("javac"), "-cp", classpath, "-d", work,
                        str(ROOT / "ci/DesktopSessionCheck.java")], check=True, timeout=60)
        # The hosted tool-cache Python can depend on LD_LIBRARY_PATH, which the desktop UID transition intentionally removes.
        fixture_python = "/usr/bin/python3" if sys.platform.startswith("linux") and Path("/usr/bin/python3").is_file() else sys.executable
        command = [executable("java"), "-Doie.check.python=" + fixture_python, "-cp", work + os.pathsep + classpath,
                   "org.openintegrationengine.installer.DesktopSessionCheck"]
        subprocess.run(command, check=True, timeout=20)
        if sys.platform.startswith("linux"):
            if os.geteuid() == 0 and os.environ.get("SUDO_UID", "0") != "0":
                native = command
            elif os.geteuid() != 0 and shutil.which("sudo") and subprocess.run(
                    ["sudo", "-n", "true"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5).returncode == 0:
                native = ["sudo", "-n", "--", *command]
            else:
                if os.environ.get("CI"):
                    raise RuntimeError("Native desktop checks require sudo from the CI runner user")
                print("SKIP: native Linux UID transition requires noninteractive sudo from a regular user")
                return
            subprocess.run([*native, "--native-linux"], check=True, timeout=20)


if __name__ == "__main__":
    check()
