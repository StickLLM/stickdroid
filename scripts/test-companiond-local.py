#!/usr/bin/env python3
"""Direct unix-socket test of companiond against mock A0 (no Noise, no stick)."""
from __future__ import annotations

import json
import os
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SL = Path(os.environ.get("SL_ROOT", "/workspace/sticklink/stick-build/stickllm"))
PY = os.environ.get("PY", sys.executable)

HDR = 7
VER = 1
FLAG_LAST = 0x01


def pack_frag(seq: int, index: int, last: bool, payload: bytes) -> bytes:
    flags = ((VER & 0x0F) << 4) | (FLAG_LAST if last else 0)
    return bytes([flags, (seq >> 24) & 0xFF, (seq >> 16) & 0xFF, (seq >> 8) & 0xFF, seq & 0xFF,
                  (index >> 8) & 0xFF, index & 0xFF]) + payload


def split_msg(seq: int, message: bytes, max_body: int = 1250) -> list[bytes]:
    cap = max_body - HDR
    if not message:
        return [pack_frag(seq, 0, True, b"")]
    out, i, idx = [], 0, 0
    while i < len(message):
        n = min(cap, len(message) - i)
        out.append(pack_frag(seq, idx, i + n >= len(message), message[i:i + n]))
        i += n
        idx += 1
    return out


def parse_frag(body: bytes):
    if len(body) < HDR:
        return None
    flags = body[0]
    if (flags >> 4) != VER:
        return None
    seq = (body[1] << 24) | (body[2] << 16) | (body[3] << 8) | body[4]
    index = (body[5] << 8) | body[6]
    return seq, index, bool(flags & FLAG_LAST), body[HDR:]


class Reasm:
    def __init__(self):
        self.parts, self.last = {}, {}

    def push(self, body: bytes):
        p = parse_frag(body)
        if p is None:
            return body
        seq, index, last, payload = p
        bag = self.parts.setdefault(seq, {})
        bag[index] = payload
        if last:
            self.last[seq] = index
        if seq not in self.last:
            return None
        last_i = self.last[seq]
        if len(bag) != last_i + 1:
            return None
        out = b"".join(bag[i] for i in range(last_i + 1))
        self.parts.pop(seq, None)
        self.last.pop(seq, None)
        return out


def send_env(sock, slot, typ, body, seq):
    env = {"t": typ, "s": seq, "b": body}
    raw = json.dumps(env, separators=(",", ":")).encode()
    for f in split_msg(seq, raw):
        sock.sendall(slot + struct.pack("!I", len(f)) + f)


def recv_envs(sock, reasm, deadline, want=None):
    sock.settimeout(max(0.05, deadline - time.time()))
    buf, got = b"", []
    while time.time() < deadline:
        try:
            data = sock.recv(65536)
        except socket.timeout:
            if want and any(e.get("t") == want for e in got):
                break
            sock.settimeout(max(0.05, deadline - time.time()))
            continue
        if not data:
            break
        buf += data
        while len(buf) >= 12:
            slot, n = buf[:8], struct.unpack("!I", buf[8:12])[0]
            if len(buf) < 12 + n:
                break
            body, buf = buf[12:12 + n], buf[12 + n:]
            msg = reasm.push(body)
            if msg:
                got.append(json.loads(msg.decode()))
        if want and any(e.get("t") == want for e in got if want != "answer"):
            # for non-answer, return as soon as we see the type (plus whatever arrived)
            if want != "collect":
                # keep going only for answer paging
                pass
        if want == "answer":
            parts = [e for e in got if e.get("t") == "answer"]
            if parts:
                b = parts[0].get("b") or {}
                if len(parts) >= int(b.get("parts") or 1):
                    break
        elif want and any(e.get("t") == want for e in got):
            break
    return got


def main():
    work = Path(os.environ.get("WORK", "/tmp/companiond-local"))
    work.mkdir(parents=True, exist_ok=True)
    sock_path = str(work / "companiond.sock")
    logdir = Path(os.environ.get("LOGDIR", work / "logs"))
    logdir.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["STICKLINK_TESTING"] = "1"
    env["PYTHONPATH"] = str(SL / "overlay/usr/local/lib") + ((":" + env["PYTHONPATH"]) if env.get("PYTHONPATH") else "")

    a0 = subprocess.Popen(
        [PY, str(ROOT / "scripts/mock_a0.py"), "--listen", "127.0.0.1:15099", "--key", "test-key", "--delay", "0.4"],
        stdout=open(logdir / "mock-a0.log", "w"), stderr=subprocess.STDOUT,
    )
    time.sleep(0.3)
    cd = subprocess.Popen(
        [PY, "-m", "sticklink.companiond", "--socket", sock_path,
         "--a0-url", "http://127.0.0.1:15099", "--a0-key", "test-key"],
        stdout=open(logdir / "companiond.log", "w"), stderr=subprocess.STDOUT, env=env,
    )
    try:
        for _ in range(50):
            if Path(sock_path).exists():
                break
            time.sleep(0.05)
        else:
            raise SystemExit("companiond socket never appeared")
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(sock_path)
        slot = b"slotTEST"
        reasm = Reasm()
        fail = 0

        send_env(s, slot, "hello", {"proto": 1, "app": "0.1.0", "dev": "lab"}, 1)
        ev = recv_envs(s, reasm, time.time() + 3, want="welcome")
        types = [e["t"] for e in ev]
        print("after hello:", types)
        if "welcome" not in types:
            print("FAIL no welcome"); fail = 1

        send_env(s, slot, "turn.start", {"turn": "t1", "conv": "c1", "text": "hello there"}, 2)
        ev = recv_envs(s, reasm, time.time() + 8, want="answer")
        types = [e["t"] for e in ev]
        print("after short turn:", types)
        answers = [e for e in ev if e["t"] == "answer"]
        progress = [e for e in ev if e["t"] == "progress"]
        if not any(e["t"] == "turn.accepted" for e in ev):
            print("FAIL no turn.accepted"); fail = 1
        if not progress or (progress[0].get("b") or {}).get("stage") != "wait":
            print("FAIL missing first-turn wait pulse", progress); fail = 1
        text = (answers[0].get("b") or {}).get("text") if answers else None
        if text != "echo:hello there":
            print("FAIL short answer", text); fail = 1
        else:
            print("PASS short answer + wait pulse")

        send_env(s, slot, "answer.ack", {"turn": "t1"}, 3)
        send_env(s, slot, "turn.start", {"turn": "t2", "conv": "c1", "text": "LONG please"}, 4)
        ev = recv_envs(s, reasm, time.time() + 10, want="answer")
        answers = [e for e in ev if e["t"] == "answer"]
        parts = [(a.get("b") or {}).get("part") for a in answers]
        nparts = (answers[0].get("b") or {}).get("parts") if answers else None
        combined = "".join((a.get("b") or {}).get("text") or "" for a in sorted(answers, key=lambda e: (e.get("b") or {}).get("part") or 0))
        print(f"after LONG: events={[e['t'] for e in ev]} parts={parts} nparts={nparts} bytes={len(combined)}")
        if nparts != 2 or len(answers) < 2 or len(combined) < 10000:
            print("FAIL long paging"); fail = 1
        else:
            print("PASS long answer paged into 2 parts")

        send_env(s, slot, "turn.start", {"turn": "t3", "conv": "c1", "text": "FAIL now"}, 5)
        ev = recv_envs(s, reasm, time.time() + 5, want="error")
        errs = [e for e in ev if e["t"] == "error"]
        print("after FAIL:", [e["t"] for e in ev], errs[0].get("b") if errs else None)
        if not errs:
            print("FAIL no error envelope"); fail = 1
        else:
            print("PASS a0 error surfaced")

        print("RESULT", "PASS" if fail == 0 else "FAIL")
        return fail
    finally:
        cd.terminate()
        a0.terminate()
        try:
            cd.wait(timeout=2)
            a0.wait(timeout=2)
        except Exception:
            cd.kill(); a0.kill()


if __name__ == "__main__":
    sys.exit(main())
