"""Compile and check completion links against the Maven-built installer classes."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent.parent


def check() -> None:
    suffix = ".exe" if sys.platform == "win32" else ""
    java_home = os.environ.get("JAVA_HOME")
    executable = lambda name: str(Path(java_home) / "bin" / (name + suffix)) if java_home else shutil.which(name)
    classpath = os.pathsep.join([str(ROOT / "target/classes"), str(ROOT / "target/lib/*")])
    with tempfile.TemporaryDirectory(prefix="oie-completion-links-") as work:
        subprocess.run([executable("javac"), "-cp", classpath, "-d", work,
                        str(ROOT / "ci/CompletionLinksCheck.java")], check=True, timeout=60)
        subprocess.run([executable("java"), "-cp", work + os.pathsep + classpath,
                        "org.openintegrationengine.installer.CompletionLinksCheck", str(Path(work) / "files")],
                       check=True, timeout=60)


if __name__ == "__main__":
    check()
