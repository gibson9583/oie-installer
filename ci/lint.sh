#!/bin/sh
# Lint the workflows and shell scripts, including the header of the generated .sh installer.
# Usage: ci/lint.sh   (set ACTIONLINT, SHELLCHECK and PYTHON to use specific binaries)
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
actionlint=${ACTIONLINT:-actionlint}
shellcheck=${SHELLCHECK:-shellcheck}
python=${PYTHON:-python3}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

echo "== actionlint"
SHELLCHECK_OPTS="--severity=warning" "$actionlint" -shellcheck "$shellcheck"

echo "== shellcheck ci/*.sh"
"$shellcheck" --severity=warning ci/*.sh

echo "== shellcheck the generated installers and launcher"
"$python" -I - "$root" "$work" <<'EOF'
import pathlib, sys
root, work = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
sys.path.insert(0, str(root))
import build
image = work / build.APP_NAME
image.mkdir()
installer = work / "installer.sh"
build.unix_installer(image, installer)
(work / "header.sh").write_bytes(installer.read_bytes().split(b"\n__OIE_INSTALLER_PAYLOAD__\n")[0] + b"\n")
build.unix_installer(image, installer, portable=True)
(work / "portable.sh").write_bytes(installer.read_bytes().split(b"\n__OIE_INSTALLER_PAYLOAD__\n")[0] + b"\n")
(work / "launcher.sh").write_text(build.PORTABLE_LAUNCHER.replace("@HEADER@", build.INIT_HEADER).replace("@ROLE@", "service"))
EOF
"$shellcheck" --severity=warning "$work/header.sh" "$work/portable.sh" "$work/launcher.sh"
echo "Lint passed"
