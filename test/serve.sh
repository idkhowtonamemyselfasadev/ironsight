#!/usr/bin/env bash
# Ironsight test server on port 25681 (runs on the test box via ~/claude/remote-test/run.sh).
#
#   test/serve.sh start        fresh world, install the built jar (+ Lithium/FerriteCore), boot
#   test/serve.sh stop
#   test/serve.sh cmd "..."    console command
#   test/serve.sh suite [args] start + node bots/suite.js [args] + stop, in one go
#
# The jar is built locally (./gradlew build); the test box has no Gradle.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
RUN="$ROOT/testserver"
FIFO="$RUN/console.in"
PORT=25681

start() {
  stop || true
  cd "$RUN"
  rm -rf world logs config/ironsight usercache.json ops.json server.log
  rm -f mods/ironsight-*.jar mods/lithium-*.jar mods/ferritecore-*.jar
  cp "$ROOT"/build/libs/ironsight-*.jar mods/
  # Server side of the perf mods Tim's players use (Sodium is client-only).
  cp "$ROOT"/testmods/lithium-*.jar "$ROOT"/testmods/ferritecore-*.jar mods/
  mkdir -p config/ironsight
  # Short timings so a full match fits in a test run; the rules (first to 7, 3 lives) stay real.
  cat > config/ironsight/settings.json <<JSON
{ "teamPickSeconds": 3, "freezeSeconds": 2, "roundSeconds": 25, "roundEndSeconds": 1, "roundsToWin": 7,
  "halftimeAfter": 6, "ffaMinPlayers": 2, "ffaMaxPlayers": 12, "ffaCountdownSeconds": 4, "ffaLives": 3,
  "ffaRespawnSeconds": 2, "ffaTimeLimitSeconds": 240, "spawnProtectionSeconds": 3, "voteSeconds": 4,
  "celebrateSeconds": 2, "autoRequeue": false, "lagCompMaxMs": 200, "manageGamerules": true,
  "buildLobby": true, "lobbyX": 0, "lobbyY": 120, "lobbyZ": 0 }
JSON
  cat > server.properties <<PROPS
server-port=$PORT
online-mode=false
enforce-secure-profile=false
level-type=minecraft\:flat
gamemode=adventure
difficulty=normal
spawn-protection=0
view-distance=8
simulation-distance=8
max-players=20
motd=Ironsight test
pause-when-empty-seconds=-1
network-compression-threshold=256
PROPS
  rm -f "$FIFO"; mkfifo "$FIFO"
  ( while true; do sleep 3600; done ) > "$FIFO" 2>/dev/null < /dev/null &
  echo $! > holder.pid
  # -Xmx3G on purpose: the brief's budget for an 8 GB machine.
  nohup java -Xms1G -Xmx3G -jar fabric-server-launch.jar --nogui < "$FIFO" > server.log 2>&1 &
  echo $! > server.pid
  for _ in $(seq 1 180); do
    grep -q 'Done (' server.log 2>/dev/null && { echo "server up on $PORT"; return 0; }
    if ! kill -0 "$(cat server.pid)" 2>/dev/null; then echo "server died"; tail -40 server.log; return 1; fi
    sleep 1
  done
  echo "server did not boot; see testserver/server.log" >&2
  tail -40 server.log
  return 1
}

stop() {
  cd "$RUN"
  if [ -f server.pid ] && kill -0 "$(cat server.pid)" 2>/dev/null; then
    [ -p "$FIFO" ] && ( echo stop > "$FIFO" & ) 2>/dev/null || true
    for _ in $(seq 1 40); do kill -0 "$(cat server.pid)" 2>/dev/null || break; sleep 1; done
  fi
  [ -f server.pid ] && kill "$(cat server.pid)" 2>/dev/null || true
  [ -f holder.pid ] && kill "$(cat holder.pid)" 2>/dev/null || true
  rm -f server.pid holder.pid "$FIFO"
  return 0
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  cmd) echo "${2:?command}" > "$FIFO" ;;
  suite)
    shift
    start
    code=0
    ( cd "$DIR/bots" && node suite.js "$@" ) || code=$?
    stop
    mkdir -p "$DIR/out"; cp "$RUN/server.log" "$DIR/out/server.log" || true
    exit $code ;;
  *) echo "usage: $0 {start|stop|cmd|suite}" >&2; exit 2 ;;
esac
