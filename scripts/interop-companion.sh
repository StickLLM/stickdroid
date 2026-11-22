#!/usr/bin/env bash
# Box-only companiond interop: throwaway identities, mock signalling + HTTP derper
# + sticklinkd + companiond + mock Agent Zero. Never touches a real stick or real A0.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SL="${SL_ROOT:-/workspace/sticklink/stick-build/stickllm}"
PY="${PY:-/workspace/.tooling/venv/bin/python}"
export JAVA_HOME="${JAVA_HOME:-/workspace/.tooling/jdk/jdk-17.0.21+9}"
export PATH="$JAVA_HOME/bin:$PATH"
export STICKLINK_TESTING=1 STICKLINK_FAST_KDF=1
export PYTHONPATH="$SL/overlay/usr/local/lib${PYTHONPATH:+:$PYTHONPATH}"
FIXTURES="${SL_FIXTURES:-/workspace/sticklink/server/worker/fixtures/dev-fixtures.json}"
SIGN_VEC="${SL_SIGN_VEC:-/workspace/sticklink/stick-build/vectors-v2/signalling-v2.json}"
DERPER="${DERPER:-/workspace/sticklink/server/e2e/bin/derper}"
WS="${SL_WS:-ws://127.0.0.1:8788/v1/ws}"
DERP="${SL_DERP:-http://127.0.0.1:3341/derp}"
STUN="${SL_STUN:-127.0.0.1:3479}"
A0="${SL_A0:-127.0.0.1:15099}"
W="${SL_WORK:-$(mktemp -d /tmp/stickdroid-companion.XXXXXX)}"
chmod 700 "$W"
LOGDIR="${1:-$ROOT/evidence/companion-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$LOGDIR"
echo "work: $W"
echo "logs: $LOGDIR"

PIDS=()
cleanup() {
  for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup EXIT

"$PY" "$SL/tests/sticklink/tools/mock_signal.py" --listen 127.0.0.1:8788 --fixtures "$FIXTURES" --signing-vectors "$SIGN_VEC" \
  >"$LOGDIR/mock.log" 2>&1 & PIDS+=($!)
python3 -c "import os,json; json.dump({'PrivateKey':'privkey:'+os.urandom(32).hex()}, open('$W/derper.key.json','w'))"
"$DERPER" -a 127.0.0.1:3341 -c "$W/derper.key.json" -http-port=-1 -stun=false -certdir "$W/derp-certs" -hostname 127.0.0.1 \
  >"$LOGDIR/derper.log" 2>&1 & PIDS+=($!)
"$PY" "$ROOT/scripts/mock_a0.py" --listen "$A0" --key test-key --delay 0.4 \
  >"$LOGDIR/mock-a0.log" 2>&1 & PIDS+=($!)
sleep 0.5
start_companiond() {
  if [ -n "${CDPID:-}" ]; then
    kill "$CDPID" 2>/dev/null || true
    wait "$CDPID" 2>/dev/null || true
    CDPID=""
  fi
  rm -f "$W/companiond.sock"
  local logf="$1"
  "$PY" -m sticklink.companiond --socket "$W/companiond.sock" --a0-url "http://$A0" --a0-key test-key     >"$logf" 2>&1 & CDPID=$!; PIDS+=($CDPID)
  for i in $(seq 1 50); do
    [ -S "$W/companiond.sock" ] && return 0
    sleep 0.05
  done
  echo "FAIL companiond socket missing"; return 1
}

D="$PY -m sticklink.daemon"
K=(--root "$W/sticklink" --registry-key-file "$W/regkey")
head -c 32 /dev/urandom > "$W/regkey"; chmod 600 "$W/regkey"
"$PY" - "$FIXTURES" "$W" <<'PYEOF'
import json, sys, os
d = json.load(open(sys.argv[1]))["devices"]["stick1"]
fd = os.open(sys.argv[2] + "/authseed", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
os.write(fd, d["auth_seed"].encode()); os.close(fd)
open(sys.argv[2] + "/ids", "w").write(d["dev"] + " " + d["home"] + "\n")
PYEOF
read DEV HOME_ID < "$W/ids"
$D init "${K[@]}" --dev "$DEV" --home "$HOME_ID" --auth-seed-file "$W/authseed" >/dev/null
rm -f "$W/authseed"
$D pair-offer "${K[@]}" --out "$W/offer.json"

HARNESS=("$ROOT/interop/build/install/interop/bin/interop")
if [ ! -x "${HARNESS[0]}" ]; then
  (cd "$ROOT" && ./gradlew --no-daemon :interop:installDist >/dev/null)
fi
"${HARNESS[0]}" pair --offer "$W/offer.json" --state "$W/peer.json" --answer "$W/answer.json"
$D pair-accept "${K[@]}" --in "$W/answer.json"
rm -f "$W/offer.json" "$W/answer.json"

FAIL=0
run_mode() {
  local MODE="$1"; shift
  echo "== $MODE"
  start_companiond "$LOGDIR/companiond-$MODE.log" || return 1
  local SARGS=() PARGS=("$@")
  [ "$MODE" = force-relay ] && SARGS=(--force-relay) PARGS+=(--force-relay)
  $D run "${K[@]}" --ws "$WS" --derp "$DERP" --stun "$STUN" --companion-sock "$W/companiond.sock" \
    "${SARGS[@]}" >"$LOGDIR/stick-$MODE.log" 2>&1 &
  local SP=$!; PIDS+=($SP)
  sleep 2
  if "${HARNESS[0]}" chat --state "$W/peer.json" --ws "$WS" --derp "$DERP" \
      --text "hello from stickdroid" --long --timeout 20 "${PARGS[@]}" \
      >"$LOGDIR/peer-$MODE.log" 2>&1; then
    echo "PASS $MODE: $(grep '^RESULT' "$LOGDIR/peer-$MODE.log" || true)"
  else
    echo "FAIL $MODE"
    tail -30 "$LOGDIR/peer-$MODE.log" || true
    tail -30 "$LOGDIR/stick-$MODE.log" || true
    tail -20 "$LOGDIR/companiond.log" || true
    FAIL=1
  fi
  kill $SP 2>/dev/null || true
  wait $SP 2>/dev/null || true
}

run_mode direct
run_mode force-relay

if grep -RqiE "pair_psk|invite_secret|auth_seed|dev_secret|nodekey:[0-9a-f]{64}" "$LOGDIR"/*.log; then
  echo "FAIL secrets/full keys in logs"
  FAIL=1
else
  echo "PASS no secrets or full keys in logs"
fi
echo "FAIL=$FAIL logs=$LOGDIR work=$W"
exit $FAIL
