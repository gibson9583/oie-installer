#!/bin/sh
# Install the downloaded .sh package on Linux without systemd, where the service is /etc/init.d/oieservice.
# Runs as root inside a container: check_initd.sh <package.sh>
set -eu
package=$1
home=/opt/oie-initd
fail() { echo "FAIL: $*" >&2; ls -la /etc/init.d "$home" 2>&1 | head -40; tail -40 "$home/logs/mirth.log" 2>/dev/null; exit 1; }

[ ! -d /run/systemd/system ] || fail "the container runs systemd"
command -v update-rc.d >/dev/null 2>&1 || { apt-get update -qq && apt-get install -y -qq init-system-helpers >/dev/null; }
sh "$package" -q -dir "$home"

[ -L /etc/init.d/oieservice ] || fail "/etc/init.d/oieservice is not a link"
[ "$(readlink /etc/init.d/oieservice)" = "$home/oieservice" ] || fail "/etc/init.d/oieservice links to $(readlink /etc/init.d/oieservice)"
grep -q '^# Provides: *oieservice' "$home/oieservice" || fail "oieservice has no init header"
ls /etc/rc2.d/S*oieservice >/dev/null 2>&1 || fail "update-rc.d did not enable oieservice"
[ -L /usr/local/bin/oieservice ] && [ -L /usr/local/bin/oiecommand ] || fail "the /usr/local/bin links are missing"

/etc/init.d/oieservice status || fail "the engine is not running"
/etc/init.d/oieservice stop || fail "stop failed"
if /etc/init.d/oieservice status; then fail "the engine still runs after stop"; fi
/etc/init.d/oieservice start || fail "start failed"
tries=0
until /etc/init.d/oieservice status >/dev/null; do tries=$((tries + 1)); [ $tries -lt 60 ] || fail "the engine did not start"; sleep 2; done

"$home/uninstall" --yes
[ ! -e /etc/init.d/oieservice ] || fail "/etc/init.d/oieservice remains"
if ls /etc/rc2.d/S*oieservice >/dev/null 2>&1; then fail "the rc links remain"; fi
[ ! -e /usr/local/bin/oieservice ] || fail "/usr/local/bin/oieservice remains"
[ ! -e "$home/server-lib" ] && [ ! -e "$home/oieservice" ] || fail "the program files remain"
[ -f "$home/conf/mirth.properties" ] && [ -d "$home/appdata" ] || fail "the uninstaller removed the configuration or data"
rm -rf "$home"
echo "init.d pass complete"
