"""Mock of the stick's loopback gateway + Agent Zero web API, for box tests only.

Serves exactly what methodc_peer.LocalA0Bridge / app_mux_bridge.AppMuxBridge reach:
  GET  /api/pair/status                 (Bearer)        -> {ok, paired}
  POST /api/pair/sticklink-lab {grant}                  -> {ok, token}   (lab grant redeem stand-in)
  GET  /agent-zero/api/csrf_token       (Bearer)        -> {ok, token, runtime_id}  + session cookie
  POST /agent-zero/api/message_async    (Bearer, CSRF)  -> {message, context}
       JSON {text, context, message_id}  or  multipart/form-data text/context/message_id +
       N x "attachments" files, parsed exactly like A0 v2.15 api/message.py L28-50 (@ba9b67df):
       werkzeug request.form / request.files.getlist("attachments"), helpers/security.safe_filename,
       saved under an uploads dir; user log item id=message_id, kvps={"attachments": [names]}
       (helpers/message_queue.log_user_message). Needs werkzeug (run with venv-a0mock).
  POST /agent-zero/api/poll             (Bearer, CSRF)  -> A0 state_snapshot shape (logs/log_version/log_guid/contexts/log_progress)
  POST /agent-zero/api/stop             (Bearer, CSRF)  -> {stopped}
  0.3.4 (A0 v2.15 ba9b67df semantics):
  POST /agent-zero/api/upload            multipart N x "file" -> {filenames, files}      (api/upload.py)
  POST /agent-zero/api/message_queue_add|_remove|_send                                  (helpers/message_queue.py)
  POST /agent-zero/api/chat_create {current_context} -> {ok, ctxid}                    (api/chat_create.py)
  message_async on a running context = intervention, not a new task (agent.py L269-286);
  after a normal finish the queue's next item is sent (_50_process_queue.py); a Stop skips that.
CSRF behaviour follows A0 helpers/api.py csrf_protect: 403 "CSRF token missing or invalid".
Agent: "LONG..." -> 150 kB answer; "SLOW..." -> runs ~30 s unless stopped;
"DUMP..." -> a system-details dump (for the phone-side reply filter);
"WAIT..." -> delayed reply with tool/search progress (waiting bubble);
else echo. A trailing [Phone app] instruction is stripped before classify/echo.
"""
import hashlib, http.cookies, io, json, os, re, secrets, ssl, sys, tempfile, threading, time, unicodedata, uuid
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

PORT = int(os.environ.get("MOCK_PORT", "8443"))
CERT, KEY = sys.argv[1], sys.argv[2]
LOCK = threading.Lock()
TOKENS = set()          # valid Bearer tokens (never logged)
SESS = {}               # cookie sid -> csrf token
CTX = {}                # context id -> dict
RUNTIME = "rt" + secrets.token_hex(4)
STATS = {"csrf403": 0, "bearer401": 0, "message_async": 0, "poll": 0, "stop": 0, "attachments": 0, "multipart": 0}
UPLOADS = os.environ.get("MOCK_UPLOADS") or tempfile.mkdtemp(prefix="mock-a0-uploads-")
UPLOAD_LIMIT_BYTES = 5 * 1024 * 1024 * 1024  # A0 helpers/ui_server.py L47

# --- verbatim from agent-zero helpers/security.py @ ba9b67df ---
FORBIDDEN_CHARS_RE = re.compile(r'[<>:"|?*~/\\\x00-\x1f\x7f]')
WINDOWS_RESERVED = frozenset({
    "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
})
FILENAME_MAX_LENGTH = 255


def safe_filename(filename):
    filename = unicodedata.normalize("NFC", str(filename))
    filename = FORBIDDEN_CHARS_RE.sub("_", filename)
    filename = filename.lstrip(" ").rstrip(". ")
    path = Path(filename)
    suffixes = ''.join(path.suffixes)
    stem = path.name[:-len(suffixes)] if suffixes else path.name
    if stem.upper() in WINDOWS_RESERVED:
        filename = f"{stem}-{suffixes}"
    if len(filename) > FILENAME_MAX_LENGTH:
        max_stem_len = FILENAME_MAX_LENGTH - len(suffixes)
        if max_stem_len > 0:
            stem = stem[:max_stem_len]
            filename = stem + suffixes
        else:
            filename = filename[:FILENAME_MAX_LENGTH]
    if not filename:
        return None
    return filename
# --- end verbatim ---


def log(m):
    print(time.strftime("%H:%M:%S"), "mock-gw:", m, flush=True)


CTX_NO = [0]


class Ctx:
    def __init__(self, cid):
        self.id, self.guid, self.logs, self.updates = cid, str(uuid.uuid4()), [], []
        self.running, self.progress, self.stop = False, "Waiting for input", False
        CTX_NO[0] += 1
        self.no, self.name = CTX_NO[0], None
        self.created_at = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + ".%06d+00:00" % (time.time_ns() // 1000 % 1000000)
        self.last_message = self.created_at
        self.queue, self.seq = [], 0

    def output(self):
        """agent.py AgentContext.output() + message_queue output_data (_sync_output)."""
        q = [{"id": i["id"], "seq": i["seq"],
              "text": i["text"][:100] + "..." if len(i["text"]) > 100 else i["text"],
              "attachments": [a.split("/")[-1] for a in i["attachments"]],
              "attachment_count": len(i["attachments"])} for i in self.queue]
        return {"id": self.id, "name": self.name, "created_at": self.created_at, "no": self.no,
                "log_guid": self.guid, "log_version": len(self.updates), "log_length": len(self.logs),
                "paused": False, "last_message": self.last_message, "type": "user",
                "running": self.running, "message_queue": q}

    def add(self, typ, content, heading="", kvps=None, id=None):
        no = len(self.logs)
        self.logs.append({"no": no, "id": id or uuid.uuid4().hex[:8], "type": typ, "heading": heading,
                          "content": content, "kvps": kvps or {}, "timestamp": time.time(), "agentno": 0})
        self.updates.append(no)
        return no

    def upd(self, no, content):
        self.logs[no]["content"] = content
        self.updates.append(no)


PHONE_INSTRUCTION = (
    "[Phone app] Reply briefly and conversationally in plain text. "
    "Do not list date/time, agent number, profile, model, or other system/runtime details "
    "unless the user explicitly asks."
)

DUMP_REPLY = (
    "Hello! Here are the details you requested:\n\n"
    "- **Current Date and Time**: 2026-11-19 17:22:27 EST\n"
    "- **Agent Information**:\n"
    "  - **Agent Number**: 0\n"
    "  - **Profile**: agent0\n"
    "  - **LLM**: llama_cpp//opt/stickllm/models/appagent/qwen2.5-7b-instruct-q4_k_m-00001-of-00002.gguf\n"
    "  - **Model**: Qwen 2.5 7B Instruct\n"
    "  - **Runtime**: llama_cpp\n"
    "  - **System info**: lab\n"
)


def unwrap(text: str) -> str:
    t = (text or "").rstrip()
    if t.endswith(PHONE_INSTRUCTION):
        return t[: -len(PHONE_INSTRUCTION)].rstrip()
    return text


def communicate(c: Ctx, text: str, attachment_paths=None):
    """agent.py AgentContext.communicate: running → intervention (no new task)."""
    c.last_message = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + "+00:00"
    if c.running:
        STATS["intervention"] = STATS.get("intervention", 0) + 1
        log(f"intervention into running ctx={c.id[:4]}…")
        return
    c.running = True
    c.stop = False
    threading.Thread(target=agent, args=(c, text, attachment_paths), daemon=True).start()


def queue_send_next(c: Ctx, source=" (from queue)"):
    """helpers/message_queue.py send_next → send_message (fresh uuid id, log_user_message)."""
    with LOCK:
        item = c.queue.pop(0) if c.queue else None
    if not item:
        return None
    names = [os.path.basename(a) for a in item["attachments"]]
    c.add("user", item["text"], kvps={"attachments": names}, id=str(uuid.uuid4()))
    local = [os.path.join(UPLOADS, n) for n in names]
    communicate(c, item["text"], local)
    STATS["queue_sent"] = STATS.get("queue_sent", 0) + 1
    log(f"queue send ctx={c.id[:4]}… files={len(names)}{source}")
    return item


def chain_end(c: Ctx):
    """extensions/python/process_chain_end/_50_process_queue.py _delayed_send."""
    waited = 0.0
    while c.running and waited < 60:
        time.sleep(0.1); waited += 0.1
    if not c.running:
        queue_send_next(c)


# 0.3.6: Agent Zero down (the StickLLM gateway's 503 agent_not_running) while this file exists.
DOWN_FILE = os.environ.get("MOCK_DOWN_FILE", "")
NO_MODEL_MESSAGE = ("No AI model is loaded on StickLLM right now, so I can't answer yet. "
                    "Open Models on the StickLLM home screen, load a model, wait until it shows Running, "
                    "then send your message again.")  # stickllm_agent.py NO_MODEL_MESSAGE (0.7.x)


def agent_down() -> bool:
    return bool(DOWN_FILE) and os.path.exists(DOWN_FILE)


def agent(c: Ctx, text: str, attachment_paths=None):
    user = unwrap(text)
    c.running = True
    c.progress = "Thinking…"
    c.add("agent", "", "A0: Thinking")
    time.sleep(0.8)
    if attachment_paths:
        # Same shape as a real A0 tool step (helpers/tool.py get_log_object): kvps=args + _tool_name.
        c.add("tool", "", "icon://construction Using tool 'vision_load'",
              kvps={"paths": [os.path.join("/a0/usr/uploads", os.path.basename(p)) for p in attachment_paths],
                    "_tool_name": "vision_load"})
        c.progress = "Using tool 'vision_load'"
        time.sleep(0.4)
        desc = []
        for p in attachment_paths:
            raw = open(p, "rb").read()
            desc.append(f"{os.path.basename(p)} ({len(raw)} B, sha256 {hashlib.sha256(raw).hexdigest()[:16]})")
        c.add("util", "loaded " + str(len(desc)) + " file(s)", "icon://info Files loaded")
        no = c.add("response", "MOCK-A0", "A0: Responding")
        time.sleep(0.3)
        c.upd(no, f"MOCK-A0 reply to: {user} | attachments: " + "; ".join(desc))
    elif user.startswith("NOMODEL"):
        # StickLLM llama relay with llama-server down: a normal response (stickllm_agent.py no_model_reply).
        no = c.add("response", NO_MODEL_MESSAGE, "No model loaded")
    elif user.startswith("SLOW"):
        for i in range(60):
            if c.stop:
                # api/stop.py: kill_process → the task dies; process_chain_end never runs.
                c.add("info", "Agent process stopped.")
                c.progress = "Waiting for input"
                c.running = False
                return
            c.progress = f"Working step {i}"
            time.sleep(0.5)
    elif user.startswith("LONG"):
        body = "".join(f"line {i:05d} the quick brown fox jumps over the lazy dog\n" for i in range(2600))
        no = c.add("response", body[:1000], "A0: Responding")
        time.sleep(0.4)
        c.upd(no, body + "END-OF-LONG")
    elif user.startswith("DUMP"):
        no = c.add("response", DUMP_REPLY[:80], "A0: Responding")
        time.sleep(0.4)
        c.upd(no, DUMP_REPLY)
    elif user.startswith("WAIT"):
        time.sleep(1.0)
        c.add("agent", "", "Using tool search_engine")
        c.progress = "Using tool search_engine"
        time.sleep(1.2)
        c.add("agent", "", "Searching the knowledge base")
        c.progress = "Searching"
        time.sleep(1.2)
        no = c.add("response", "MOCK-A0", "A0: Responding")
        time.sleep(0.3)
        c.upd(no, "MOCK-A0 reply to: WAIT")
    else:
        no = c.add("response", "MOCK-A0", "A0: Responding")  # streamed: grows in place
        time.sleep(0.4)
        c.upd(no, f"MOCK-A0 reply to: {user}")
    c.progress = "Waiting for input"
    c.running = False
    if c.queue:
        threading.Thread(target=chain_end, args=(c,), daemon=True).start()


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send_json(self, code, obj, cookie=None):
        raw = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(raw)

    def send_text(self, code, s):
        raw = s.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def bearer_ok(self):
        h = self.headers.get("Authorization", "")
        ok = h.startswith("Bearer ") and h[7:] in TOKENS
        if not ok:
            STATS["bearer401"] += 1
        return ok

    def sid(self):
        c = http.cookies.SimpleCookie(self.headers.get("Cookie", ""))
        return c["sid"].value if "sid" in c else None

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/api/pair/status":
            return self.send_json(200 if self.bearer_ok() else 401, {"ok": True, "paired": True})
        if not self.bearer_ok():
            return self.send_text(401, "unauthorized")
        if p == "/api/agent/status":
            down = agent_down()
            return self.send_json(200, {"ok": True, "state": "starting" if down else "running", "running": not down,
                                        "message": "Agent Zero is starting..." if down else "Agent"})
        if agent_down() and p.startswith("/agent-zero"):
            STATS["agent_down_503"] = STATS.get("agent_down_503", 0) + 1
            return self.send_json(503, {"ok": False, "error": "agent_not_running", "message": "Agent Zero is not running yet."})
        if p == "/agent-zero/api/csrf_token":
            sid = self.sid()
            cookie = None
            with LOCK:
                if not sid or sid not in SESS:
                    sid = secrets.token_hex(8)
                    SESS[sid] = secrets.token_urlsafe(32)
                    cookie = f"sid={sid}; HttpOnly; Path=/; SameSite=Strict"
                tok = SESS[sid]
            log("csrf_token issued (session cookie kept by bridge jar)" if cookie else "csrf_token reused")
            return self.send_json(200, {"ok": True, "token": tok, "runtime_id": RUNTIME}, cookie)
        return self.send_text(404, "not found")

    def do_POST(self):
        p = self.path.split("?")[0]
        if p == "/api/pair/sticklink-lab":
            b = self.body()
            if len(str(b.get("grant", ""))) < 32:
                return self.send_json(400, {"ok": False})
            tok = secrets.token_urlsafe(32)
            TOKENS.add(tok)
            log("lab grant redeemed -> Bearer minted")
            return self.send_json(200, {"ok": True, "token": tok})
        if not self.bearer_ok():
            return self.send_text(401, "unauthorized")
        if agent_down() and p.startswith("/agent-zero"):
            STATS["agent_down_503"] = STATS.get("agent_down_503", 0) + 1
            return self.send_json(503, {"ok": False, "error": "agent_not_running", "message": "Agent Zero is not running yet."})
        sid = self.sid()
        want = SESS.get(sid) if sid else None
        sent = self.headers.get("X-CSRF-Token")
        if not want or not sent or want != sent:
            STATS["csrf403"] += 1
            log(f"403 CSRF on {p}")
            return self.send_text(403, "CSRF token missing or invalid")
        ctype = self.headers.get("Content-Type", "")
        if p == "/agent-zero/api/message_async" and ctype.startswith("multipart/form-data"):
            return self.message_async_multipart(ctype)
        if p == "/agent-zero/api/upload":
            return self.upload_multipart(ctype)
        b = self.body()
        if p == "/agent-zero/api/message_async":
            STATS["message_async"] += 1
            cid = b.get("context") or uuid.uuid4().hex[:8]
            with LOCK:
                c = CTX.setdefault(cid, Ctx(cid))
            if unwrap(b.get("text", "")).startswith("NOMODELCFG"):
                # A0 v2.15 model gate (_10_check_chat_model.py): nothing logged, no task.
                STATS["model_not_configured"] = STATS.get("model_not_configured", 0) + 1
                return self.send_json(200, {"message": "Model not configured.", "model_not_configured": True, "context": cid})
            c.add("user", b.get("text", ""), kvps={"attachments": []}, id=b.get("message_id"))
            communicate(c, b.get("text", ""))
            log(f"message_async ctx={cid[:4]}… len={len(b.get('text',''))}")
            return self.send_json(200, {"message": "Message received.", "context": cid})
        if p == "/agent-zero/api/poll":
            STATS["poll"] += 1
            c = CTX.get(b.get("context") or "")
            if not c:
                return self.send_json(200, {"logs": [], "log_version": 0, "log_guid": "", "log_progress": 0,
                    "contexts": sorted([x.output() for x in CTX.values()], key=lambda o: o["created_at"], reverse=True)})
            start = int(b.get("log_from") or 0)
            ups = c.updates[start:]
            seen, items = set(), []
            for u in ups:
                if u not in seen:
                    items.append(dict(c.logs[u])); seen.add(u)
            return self.send_json(200, {
                "logs": items, "log_version": len(c.updates), "log_guid": c.guid,
                "log_progress": c.progress, "log_progress_active": c.running,
                "contexts": sorted([x.output() for x in CTX.values()], key=lambda o: o["created_at"], reverse=True),
            })
        if p == "/agent-zero/api/chat_create":
            cid = b.get("new_context") or uuid.uuid4().hex[:8]
            with LOCK:
                CTX.setdefault(cid, Ctx(cid))
            STATS["chat_create"] = STATS.get("chat_create", 0) + 1
            return self.send_json(200, {"ok": True, "ctxid": cid, "message": "Context created."})
        if p in ("/agent-zero/api/message_queue_add", "/agent-zero/api/message_queue_remove", "/agent-zero/api/message_queue_send"):
            c = CTX.get(b.get("context", ""))
            if not c:
                return self.send_text(404, "Context not found")
            if p.endswith("_add"):
                text = (b.get("text") or "").strip()
                atts = b.get("attachments", [])
                if not text and not atts:
                    return self.send_text(400, "Empty message")
                with LOCK:
                    c.seq += 1
                    item = {"id": b.get("item_id") or uuid.uuid4().hex, "seq": c.seq, "text": text,
                            "attachments": [a if a.startswith("/") else "/a0/usr/uploads/" + a for a in atts]}
                    c.queue.append(item)
                STATS["queue_add"] = STATS.get("queue_add", 0) + 1
                log(f"queue add ctx={c.id[:4]}… files={len(atts)} len={len(text)} queue={len(c.queue)}")
                return self.send_json(200, {"ok": True, "item_id": item["id"], "queue_length": len(c.queue)})
            if p.endswith("_remove"):
                iid = b.get("item_id")
                with LOCK:
                    c.queue = [] if not iid else [i for i in c.queue if i["id"] != iid]
                STATS["queue_remove"] = STATS.get("queue_remove", 0) + 1
                return self.send_json(200, {"ok": True, "remaining": len(c.queue)})
            STATS["queue_send_api"] = STATS.get("queue_send_api", 0) + 1
            if not c.queue:
                return self.send_json(200, {"ok": True, "message": "Queue empty"})
            item = queue_send_next(c, " (message_queue_send)")
            return self.send_json(200, {"ok": True, "sent_item_id": item["id"]})
        if p == "/agent-zero/api/stop":
            STATS["stop"] += 1
            c = CTX.get(b.get("context") or "")
            if c:
                c.stop = True
            log("stop requested")
            return self.send_json(200, {"message": "Agent process stopped.", "stopped": bool(c)})
        return self.send_text(404, "not found")


def _message_async_multipart(self, ctype):
    """A0 api/message.py Message.communicate, multipart branch (L30-50), on werkzeug's parser."""
    from werkzeug.wrappers import Request as WRequest
    n = int(self.headers.get("Content-Length") or 0)
    raw = self.rfile.read(n) if n else b""
    env = {"REQUEST_METHOD": "POST", "CONTENT_TYPE": ctype, "CONTENT_LENGTH": str(len(raw)),
           "wsgi.input": io.BytesIO(raw), "SERVER_NAME": "127.0.0.1", "SERVER_PORT": "5099",
           "wsgi.url_scheme": "http", "PATH_INFO": "/api/message_async"}
    request = WRequest(env)
    request.max_form_memory_size = UPLOAD_LIMIT_BYTES
    request.max_content_length = UPLOAD_LIMIT_BYTES
    # ---- A0 L31-50 ----
    text = request.form.get("text", "")
    ctxid = request.form.get("context", "")
    message_id = request.form.get("message_id", None)
    attachments = request.files.getlist("attachments")
    attachment_paths = []
    saved = []
    if attachments:
        os.makedirs(UPLOADS, exist_ok=True)
        for attachment in attachments:
            if attachment.filename is None:
                continue
            filename = safe_filename(attachment.filename)
            if not filename:
                continue
            save_path = os.path.join(UPLOADS, filename)
            attachment.save(save_path)
            saved.append(save_path)
            attachment_paths.append(os.path.join("/a0/usr/uploads", filename))
    # ---- end ----
    STATS["message_async"] += 1
    STATS["multipart"] += 1
    STATS["attachments"] += len(saved)
    cid = ctxid or uuid.uuid4().hex[:8]
    with LOCK:
        c = CTX.setdefault(cid, Ctx(cid))
    c.add("user", text, kvps={"attachments": [os.path.basename(x) for x in attachment_paths]}, id=message_id)
    communicate(c, text, saved)
    log(f"message_async multipart ctx={cid[:4]}… len={len(text)} files={len(saved)} bytes={len(raw)}")
    return self.send_json(200, {"message": "Message received.", "context": cid})


H.message_async_multipart = _message_async_multipart


def _upload_multipart(self, ctype):
    """A0 api/upload.py UploadFile.process on werkzeug: N x "file", safe_filename, usr/uploads."""
    from werkzeug.wrappers import Request as WRequest
    n = int(self.headers.get("Content-Length") or 0)
    raw = self.rfile.read(n) if n else b""
    env = {"REQUEST_METHOD": "POST", "CONTENT_TYPE": ctype, "CONTENT_LENGTH": str(len(raw)),
           "wsgi.input": io.BytesIO(raw), "SERVER_NAME": "127.0.0.1", "SERVER_PORT": "5099",
           "wsgi.url_scheme": "http", "PATH_INFO": "/api/upload"}
    request = WRequest(env)
    request.max_form_memory_size = UPLOAD_LIMIT_BYTES
    request.max_content_length = UPLOAD_LIMIT_BYTES
    if "file" not in request.files:
        return self.send_text(500, "No file part")
    saved = []
    os.makedirs(UPLOADS, exist_ok=True)
    for f in request.files.getlist("file"):
        if f and f.filename:
            name = safe_filename(f.filename)
            if not name:
                continue
            f.save(os.path.join(UPLOADS, name))
            saved.append(name)
    STATS["upload"] = STATS.get("upload", 0) + 1
    STATS["attachments"] += len(saved)
    log(f"upload files={len(saved)} bytes={len(raw)}")
    return self.send_json(200, {"filenames": saved, "files": [{"filename": x} for x in saved]})


H.upload_multipart = _upload_multipart


def stats_dumper():
    while True:
        time.sleep(5)
        with open(os.environ.get("MOCK_STATS", "/tmp/mock-gw-stats.json"), "w") as f:
            json.dump(STATS, f)


ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(CERT, KEY)
srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
threading.Thread(target=stats_dumper, daemon=True).start()
log(f"listening https://127.0.0.1:{PORT}")
srv.serve_forever()
