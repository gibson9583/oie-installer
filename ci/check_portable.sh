#!/bin/sh
# Install the portable oie_unix .sh on an installed Java without systemd, then run the portable .tar.gz.
# Runs as root inside a container, for example Alpine, Java 17 or an emulated CPU:
#   check_portable.sh <oie_unix_<version>.sh> <oie_unix_<version>.tar.gz>
set -eu
package=$1
archive=$2
home=/opt/oie-portable
unpacked=/opt/oie-archive
log() { echo "== $*"; }
fail() { echo "FAIL: $*" >&2; ls -la "$home" 2>&1 | head -30; tail -40 "$home/logs/mirth.log" 2>/dev/null; tail -20 "$unpacked/logs/mirth.log" 2>/dev/null; exit 1; }
wait_for() {
  tries=0
  until "$@" >/dev/null 2>&1; do tries=$((tries + 1)); [ "$tries" -lt 200 ] || return 1; sleep 3; done
}
started() { grep -q "server successfully started" "$1/logs/mirth.log"; }
not_running() { ! "$1/oieservice" status; }
# An emulated JVM can take minutes to exit, or hang, after its shutdown hooks finish. Under emulation the
# check is that the engine began its clean shutdown. Everywhere else, oieservice stop must succeed.
stop_engine() {
  if [ -z "${EMULATED:-}" ]; then
    "$1/oieservice" stop || fail "stop failed"
    return
  fi
  "$1/oieservice" stop || true
  grep -q "shutting down mirth" "$1/logs/mirth.log" || fail "the engine did not begin its shutdown"
  tries=0
  until not_running "$1" >/dev/null 2>&1; do
    tries=$((tries + 1))
    if [ "$tries" -ge 40 ]; then
      echo "The emulated JVM did not exit; ending it"
      for process in /proc/[0-9]*; do
        if tr '\0' ' ' < "$process/cmdline" 2>/dev/null | grep -q "$1/mirth-server-launcher.jar"; then kill -9 "${process#/proc/}" || true; fi
      done
      sleep 5
      break
    fi
    sleep 15
  done
}

java -version
uname -sm
log "Without a display, the .sh asks in text mode, and a declined license installs nothing"
if printf 'a\nn\n' | env -u DISPLAY -u WAYLAND_DISPLAY sh "$package" -c -dir "$home"; then fail "declining the license succeeded"; fi
[ ! -e "$home" ] || fail "declining the license installed files"

log "With an unusable display, the installer falls back to text mode"
if printf 'a\nn\n' | DISPLAY=:99 sh "$package"; then fail "declining the license succeeded"; fi
[ ! -e /opt/openintegrationengine ] || fail "declining the license installed files"

log "Unattended installation"
sh "$package" -q -dir "$home"
wait_for started "$home" || fail "the installed engine did not start"
"$home/oieservice" status || fail "the installed engine is not running"
grep -q '^-java-cmd ' "$home/conf/custom.vmoptions" || fail "the Java runtime was not recorded"
[ ! -e "$home/.launcher/oieserver" ] || fail "the portable engine holds native launchers"
stop_engine "$home"
if "$home/oieservice" status; then fail "the engine still runs after stop"; fi
"$home/uninstall" --yes
[ ! -e "$home/server-lib" ] && [ ! -e "$home/oieservice" ] || fail "the program files remain"
[ -f "$home/conf/mirth.properties" ] && [ -d "$home/appdata" ] || fail "the uninstaller removed the data"
rm -rf "$home"

log "The .tar.gz on an installed Java"
mkdir -p "$unpacked.tmp"
tar -xzf "$archive" -C "$unpacked.tmp"
mv "$unpacked.tmp/oie" "$unpacked"
sed -i -e 's/^http.port *=.*/http.port = 8180/' -e 's/^https.port *=.*/https.port = 8543/' "$unpacked/conf/mirth.properties"
"$unpacked/oieservice" start || fail "start failed"
wait_for started "$unpacked" || fail "the archive engine did not start"
"$unpacked/oieservice" status || fail "the archive engine is not running"
stop_engine "$unpacked"
echo "portable pass complete"
