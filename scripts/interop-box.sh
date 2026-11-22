#!/usr/bin/env bash
# Box-only interop: throwaway identities, local mock signalling + HTTP derper + sticklinkd.
# Never touches a real stick. Logs are scanned for secrets.
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
W="${SL_WORK:-$(mktemp -d /tmp/stickdroid-e2e.XXXXXX)}"
chmod 700 "$W"
LOGDIR="${1:-$ROOT/evidence/interop-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$LOGDIR"
echo "work: $W"
echo "logs: $LOGDIR"

PIDS=()
cleanup() {
  for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup EXIT

# --- stack ---
"$PY" "$SL/tests/sticklink/tools/mock_signal.py" --listen 127.0.0.1:8788 --fixtures "$FIXTURES" --signing-vectors "$SIGN_VEC" \
  >"$LOGDIR/mock.log" 2>&1 & PIDS+=($!)
# HTTP derper (no TLS); admission open
python3 -c "import os,json; json.dump({'PrivateKey':'privkey:'+os.urandom(32).hex()}, open('$W/derper.key.json','w'))"
"$DERPER" -a 127.0.0.1:3341 -c "$W/derper.key.json" -http-port=-1 -stun=false -certdir "$W/derp-certs" -hostname 127.0.0.1 \
  >"$LOGDIR/derper.log" 2>&1 & PIDS+=($!)
sleep 0.8

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

# JVM pair (same core as the app)
HARNESS=("$ROOT/interop/build/install/interop/bin/interop")
if [ ! -x "${HARNESS[0]}" ]; then
  (cd "$ROOT" && ./gradlew --no-daemon :interop:installDist >/dev/null)
fi
"${HARNESS[0]}" pair --offer "$W/offer.json" --state "$W/peer.json" --answer "$W/answer.json"
$D pair-accept "${K[@]}" --in "$W/answer.json"
rm -f "$W/offer.json" "$W/answer.json"

FAIL=0
run_mode() {
  local MODE="$1" EXP="$2"; shift 2
  echo "== $MODE"
  local SARGS=() PARGS=("$@")
  [ "$MODE" = stick-force-relay ] && SARGS=(--force-relay)
  $D run "${K[@]}" --ws "$WS" --derp "$DERP" --stun "$STUN" "${SARGS[@]}" >"$LOGDIR/stick-$MODE.log" 2>&1 &
  local SP=$!; PIDS+=($SP)
  sleep 2
  if "${HARNESS[0]}" connect --state "$W/peer.json" --ws "$WS" --derp "$DERP" \
      --expect "$EXP" "${PARGS[@]}" >"$LOGDIR/peer-$MODE.log" 2>&1; then
    echo "PASS $MODE: $(grep '^RESULT' "$LOGDIR/peer-$MODE.log" || true)"
  else
    echo "FAIL $MODE"
    tail -20 "$LOGDIR/peer-$MODE.log" || true
    tail -20 "$LOGDIR/stick-$MODE.log" || true
    FAIL=1
  fi
  kill $SP 2>/dev/null || true
  wait $SP 2>/dev/null || true
  # drop from PIDS list conceptually; leftover kills are fine
}

run_mode direct direct --duration 6
run_mode peer-force-relay derp --force-relay --duration 6
run_mode udp-blocked derp --block-udp-rx --duration 8
# stick-side force-relay: stick advertises no cands, client should land on DERP
run_mode stick-force-relay derp --duration 8

if grep -RqiE "pair_psk|invite_secret|auth_seed|dev_secret|nodekey:[0-9a-f]{64}" "$LOGDIR"/*.log; then
  echo "FAIL secrets/full keys in logs"
  FAIL=1
else
  echo "PASS no secrets or full keys in logs"
fi
echo "FAIL=$FAIL logs=$LOGDIR work=$W"
exit $FAIL
