# -*- coding: utf-8 -*-
"""
mock LLM 服务：把 App 真实的 OpenAI 兼容请求落盘，并回放在 responses/<key>.json 里
人工（=Claude 扮演 LLM）撰写的答案。

约定：
  req/<key>.req.json     —— 收到的完整请求（system+user+参数），key = sha1(user文本)[:12]
  responses/<key>.json   —— 我要回放的 assistant content（纯文本，通常是 JSON 字符串）
响应格式跟随请求里的 stream 标志：stream=true → SSE 分片；否则标准 chat.completion。

用法: python mock_llm.py [port]
"""
import hashlib
import json
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
REQ_DIR = os.path.join(ROOT, "req")
RESP_DIR = os.path.join(ROOT, "responses")
os.makedirs(REQ_DIR, exist_ok=True)
os.makedirs(RESP_DIR, exist_ok=True)

LOG_LOCK = threading.Lock()


def log(msg):
    with LOG_LOCK:
        line = f"[{threading.get_ident() % 1000:03d}] {msg}"
        print(line, flush=True)
        with open(os.path.join(ROOT, "server.log"), "a", encoding="utf-8") as f:
            f.write(line + "\n")


def key_of(user_text):
    # 归一化运行时随机 ID（blockId 等 UUID），使核验/出题等阶段的 prompt 键跨运行稳定，
    # 已撰写的答案才能被重放。章节正文本身不同，归一化后不会互相撞键。
    norm = re.sub(
        r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        "<uuid>", user_text)
    return hashlib.sha1(norm.encode("utf-8")).hexdigest()[:12]


def content_type_of(profile_hint):
    """从骨架/审计等 prompt 猜一个 stage 名，只用于日志可读性"""
    h = profile_hint or ""
    for word, name in [
        ("骨架规划", "SKELETON"), ("演进链", "CHAIN"), ("全局审计员", "AUDIT"),
        ("讲述路线", "OUTLINE"), ("因果契约", "CONTRACT"), ("JSON 修复", "REPAIR"),
        ("测验出题人", "QUIZ"), ("核验员", "VERIFY"), ("核验", "VERIFY"),
        ("小说家", "BODY"), ("正文", "BODY"),
    ]:
        if word in h:
            return name
    return "UNKNOWN"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, status, body_bytes, ctype="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body_bytes)))
        self.end_headers()
        self.wfile.write(body_bytes)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            body = json.dumps({"object": "list", "data": [{"id": "mock-llm", "object": "model"}]}).encode()
            self._send(200, body)
        else:
            self._send(404, b'{"error":{"message":"not found"}}')

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n)
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception as e:
            log(f"BAD REQUEST JSON: {e}")
            self._send(400, json.dumps({"error": {"message": f"bad request: {e}"}}).encode())
            return

        path = req.get("path", "")  # 仅诊断用
        messages = req.get("messages", [])
        system = next((m.get("content", "") for m in messages if m.get("role") == "system"), "")
        user = next((m.get("content", "") for m in messages if m.get("role") == "user"), "")
        stream = bool(req.get("stream", False))
        k = key_of(user)
        stage = content_type_of(system)

        # 落盘请求（供 Claude 阅读撰写答案）
        req_file = os.path.join(REQ_DIR, f"{k}.req.json")
        with open(req_file, "w", encoding="utf-8") as f:
            json.dump({"key": k, "stage": stage, "stream": stream,
                       "model": req.get("model"), "max_tokens": req.get("max_tokens"),
                       "temperature": req.get("temperature"), "jsonMode": bool(req.get("response_format")),
                       "system": system, "user": user}, f, ensure_ascii=False, indent=1)

        resp_file = os.path.join(RESP_DIR, f"{k}.json")
        if not os.path.isfile(resp_file):
            # 阶段兜底：核验/出题等 prompt 里嵌了 per-run 随机 ID，逐次键会变、无法精确重放。
            # 允许放一个 responses/_stage_<STAGE>.json 作为该阶段的通用答案。
            stage_file = os.path.join(RESP_DIR, f"_stage_{stage}.json")
            if os.path.isfile(stage_file):
                log(f"FALLBACK-STAGE {stage} key={k} -> {os.path.basename(stage_file)}")
                resp_file = stage_file
        if not os.path.isfile(resp_file):
            log(f"MISSING {stage} key={k} —— 还没有答案，请撰写 responses/{k}.json")
            # 明确报 500，让流程在此停下（而不是伪造内容污染数据）
            self._send(500, json.dumps({"error": {"message": f"mock: no authored response for {k} ({stage})"}}).encode())
            return

        with open(resp_file, "r", encoding="utf-8") as f:
            content = f.read()
        log(f"HIT {stage} key={k} stream={stream} len={len(content)}")

        if stream:
            self._stream(content, req)
        else:
            body = json.dumps({
                "id": "chatcmpl-mock", "object": "chat.completion",
                "model": req.get("model", "mock-llm"),
                "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                             "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 1000, "completion_tokens": len(content) // 2,
                          "total_tokens": 1000 + len(content) // 2},
            }, ensure_ascii=False).encode("utf-8")
            self._send(200, body)

    def _stream(self, content, req):
        # 分片吐出，模拟真实 SSE（每片带 delta.content）
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        size = max(200, len(content) // 40)  # ~40 片
        pieces = [content[i:i + size] for i in range(0, len(content), size)] or [""]
        try:
            for p in pieces:
                chunk = {"object": "chat.completion.chunk",
                         "choices": [{"index": 0, "delta": {"content": p}, "finish_reason": None}]}
                self._write_chunk("data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n")
            final = {"object": "chat.completion.chunk",
                     "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                     "usage": {"prompt_tokens": 1000, "completion_tokens": len(content) // 2,
                               "total_tokens": 1000 + len(content) // 2}}
            self._write_chunk("data: " + json.dumps(final, ensure_ascii=False) + "\n\n")
            self._write_chunk("data: [DONE]\n\n")
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            log("stream aborted by client (fallback?)")

    def _write_chunk(self, s):
        b = s.encode("utf-8")
        self.wfile.write(f"{len(b):x}\r\n".encode() + b + b"\r\n")
        self.wfile.flush()


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8199
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    log(f"mock LLM listening on http://127.0.0.1:{port}/v1  (base_url=.../v1)")
    srv.serve_forever()


if __name__ == "__main__":
    main()
