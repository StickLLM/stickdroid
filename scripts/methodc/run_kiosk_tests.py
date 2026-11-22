"""Box-only Method C kiosk-QR tests: phone core (JVM harness) vs the stick's real
methodc_peer.py + app_mux_bridge.py, local wrangler signalling, mock gateway/A0.

QR URLs are read from the stick's own published file (what GET /api/pair/sticklink-join
serves to the kiosk) and handed to the harness via a 0600 temp file, never argv/logs.
Uses 4 pair.open mints (local limit 5/10 min).
"""
import json, os, subprocess, sys, tempfile, threading, time, urllib.parse
RUN = "/workspace/mc-lab/run"
EV = sys.argv[1]
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HARN = os.environ.get("STICKDROID_INTEROP") or os.path.join(ROOT, "interop/build/install/interop/bin/interop")
os.makedirs(EV, exist_ok=True)
results = []

def now(): return time.strftime("%H:%M:%S")

def remint(prev_exp):
    open(f"{RUN}/sticklink-join-remint.req", "w").close()
    t0 = time.time()
    while time.time() - t0 < 60:
        try:
            d = json.load(open(f"{RUN}/sticklink-join.json"))
            if d["exp"] != prev_exp:
                return d
        except Exception:
            pass
        time.sleep(0.3)
    raise SystemExit("no remint")

def cur():
    return json.load(open(f"{RUN}/sticklink-join.json"))

def url_file(url):
    fd, p = tempfile.mkstemp(prefix="mcurl-", dir="/tmp"); os.write(fd, url.encode()); os.close(fd)
    return p

def mutate_p(url, how):
    u = urllib.parse.urlsplit(url)
    q = dict(urllib.parse.parse_qsl(u.query))
    sl = q["qr"]
    parts = dict(x.split("=", 1) for x in sl[4:].split("&"))
    P = parts["P"]
    if how == "flip":  # still canonical base64url, wrong secret
        i = 10; c = P[i]; parts["P"] = P[:i] + ("A" if c != "A" else "B") + P[i + 1:]
    elif how == "short":
        parts["P"] = P[:-2]
    q["qr"] = "sl1:" + "&".join(f"{k}={v}" for k, v in parts.items())
    return urllib.parse.urlunsplit((u.scheme, u.netloc, u.path, urllib.parse.urlencode(q, quote_via=urllib.parse.quote, safe=""), ""))

def wrong_host(url):
    return url.replace("://127.0.0.1:8800/browser", "://evil.example/browser", 1)

def run(name, url, expect, *extra, bg=False):
    uf = url_file(url)
    cmd = [HARN, "methodc", "--url-file", uf, "--debug-hosts", "--expect", expect, *extra]
    logf = open(f"{EV}/{name}.log", "w")
    logf.write(f"# {name}  expect={expect}  started {now()} (America/Toronto)\n# args: {' '.join(extra)}\n"); logf.flush()
    p = subprocess.Popen(cmd, stdout=logf, stderr=subprocess.STDOUT)
    def wait():
        rc = p.wait(); os.unlink(uf)
        txt = open(f"{EV}/{name}.log").read()
        res = "PASS" if rc == 0 and "PASS" in txt else "FAIL"
        results.append((name, expect, res)); print(now(), name, expect, res, flush=True)
    if bg:
        t = threading.Thread(target=wait); t.start(); return t
    wait()

# --- QR-A -------------------------------------------------------------------
A = remint(cur()["exp"]); print(now(), "QR-A minted exp", A["exp"], flush=True)
run("T01-corrupt-P-flipped", mutate_p(A["url"], "flip"), "MAILBOX_GONE")
run("T02-corrupt-P-short", mutate_p(A["url"], "short"), "BAD_LINK")
run("T03-wrong-host", wrong_host(A["url"]), "BAD_LINK")
t4 = run("T04-direct-chat-session", A["url"], "ok",
         "--text", "hello from the phone core", "--text2", "second turn same chat",
         "--long", "--cancel", "--forbidden", "--hold-sec", "40", bg=True)
# wait until T04 has a live session, then try to reuse the same invite
while "SESSION up" not in open(f"{EV}/T04-direct-chat-session.log").read():
    time.sleep(0.3)
run("T05-reuse-invite-while-live", A["url"], "STICK_BUSY", "--offer-timeout-ms", "15000")
t4.join()
time.sleep(4)
run("T06-reuse-invite-after-session", A["url"], "MAILBOX_GONE")

# --- QR-B: let it expire unscanned -----------------------------------------
B = remint(cur()["exp"]); print(now(), "QR-B minted exp", B["exp"], flush=True)
while time.time() < B["exp"] + 3:
    time.sleep(1)
run("T07-expired-client-check", B["url"], "EXPIRED")
run("T08-expired-server-refuses", B["url"], "MAILBOX_GONE", "--skip-client-expiry")

# --- QR-C: a fresh scan gives a fresh session --------------------------------
C = remint(cur()["exp"]); print(now(), "QR-C minted exp", C["exp"], flush=True)
run("T09-fresh-scan-second-session", C["url"], "ok", "--text", "fresh scan, new session", "--text2", "DUMP hello", "--text3", "WAIT please")

# --- stick goes away mid-session: phone must notice and say so --------------
D = remint(cur()["exp"]); print(now(), "QR-D minted exp", D["exp"], flush=True)
t10 = run("T10-stick-dies-mid-session", D["url"], "LOST", "--text", "before the stick dies", "--hold-sec", "60", bg=True)
while "holding session" not in open(f"{EV}/T10-stick-dies-mid-session.log").read():
    time.sleep(0.3)
time.sleep(3)
subprocess.call(["pkill", "-f", "run_stick_peer.py"])
print(now(), "stick peer killed", flush=True)
t10.join()

json.dump({"results": results, "invites_used": 4}, open(f"{EV}/summary.json", "w"), indent=1)
ok = sum(1 for r in results if r[2] == "PASS")
print(f"SUMMARY {ok}/{len(results)} PASS")
sys.exit(0 if ok == len(results) else 1)
