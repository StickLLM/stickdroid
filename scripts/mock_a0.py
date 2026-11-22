#!/usr/bin/env python3
"""Mock Agent Zero External API for box tests. Loopback only. Not a real A0."""
from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class State:
    def __init__(self, key: str, delay: float):
        self.key = key
        self.delay = delay
        self.n = 0
        self.logs: dict[str, dict] = {}
        self.lock = threading.Lock()


STATE: State  # set in main


class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(time.strftime("%H:%M:%S") + " mock-a0: " + (fmt % args), flush=True)

    def do_POST(self):
        if self.headers.get("X-API-KEY", "") != STATE.key:
            self._json(401, {"error": "unauthorized"})
            return
        n = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            self._json(400, {"error": "bad json"})
            return
        if self.path == "/api/api_message":
            text = body.get("message") or ""
            if text.startswith("FAIL "):
                self._json(503, {"error": "mock failure"})
                return
            # Advertise "thinking" while the request is in flight so a later
            # turn (which has context_id) can poll progress.
            with STATE.lock:
                cid = body.get("context_id") or f"ctx-{STATE.n + 1}"
                STATE.n += 1
                STATE.logs[cid] = {
                    "progress": "searching",
                    "items": [{"type": "think", "content": "mock think"}],
                }
            time.sleep(STATE.delay)
            if text.startswith("LONG "):
                answer = ("lorem ipsum " * 1200).strip()  # ~13 KiB, two 8 KiB pages
            else:
                # first line only, so a "Context:" suffix from companiond stays out of the echo
                head = text.split("\n", 1)[0]
                answer = f"echo:{head}"
            with STATE.lock:
                STATE.logs[cid] = {
                    "progress": "done",
                    "items": [{"type": "response", "content": answer}],
                }
            self._json(200, {"context_id": cid, "response": answer})
        elif self.path == "/api/api_log_get":
            cid = body.get("context_id")
            with STATE.lock:
                rec = STATE.logs.get(cid) or {"progress": "thinking", "items": [{"type": "think"}]}
            self._json(200, rec)
        elif self.path == "/api/api_terminate_chat":
            self._json(200, {"ok": True, "note": "mock does not delete"})
        elif self.path == "/api/api_reset_chat":
            self._json(200, {"ok": True})
        else:
            self._json(404, {"error": "unknown route"})

    def _json(self, code, obj):
        raw = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def main():
    global STATE
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="127.0.0.1:15099")
    ap.add_argument("--key", default="test-key")
    ap.add_argument("--delay", type=float, default=0.4)
    args = ap.parse_args()
    host, port = args.listen.rsplit(":", 1)
    STATE = State(args.key, args.delay)
    httpd = ThreadingHTTPServer((host, int(port)), H)
    print(time.strftime("%H:%M:%S") + f" mock-a0: listening on {args.listen}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
