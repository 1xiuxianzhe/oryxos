"""极简 webhook 接收器 —— 只用来验证 OryxOS 的 notify 工具推得出消息。

用法（Windows / Linux 都一样，只需 Python 3.8+，无第三方依赖）：
    python tools/webhook_sink.py            # 默认监听 127.0.0.1:9099
    python tools/webhook_sink.py 9100       # 换端口

行为：
  * POST /webhook（以及 POST /）→ 打印 content、追加到 .oryxos/webhook-inbox.log，返回 200 {"ok":true}
  * 其它路径 → 404
  * Ctrl+C 退出

为什么必须返回 2xx：OryxOS 侧 WebhookNotifyAdapter 只把 2xx 当成功，
非 2xx 会被记成 NotifySendException，tool_invocations 里就是一条 FAILED。
"""

import json
import pathlib
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 日志落在仓库根的 .oryxos 下，和应用自身的数据目录保持一致
LOG_FILE = pathlib.Path(__file__).resolve().parent.parent / ".oryxos" / "webhook-inbox.log"


class WebhookHandler(BaseHTTPRequestHandler):
    server_version = "OryxSink/1.0"

    def do_POST(self) -> None:
        if self.path.split("?")[0] not in ("/webhook", "/"):
            self.send_error(404, "only /webhook is accepted")
            return

        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace") if length else ""

        # notify 发的是 {"content": "..."}；解析失败就原样展示，不吞掉任何东西
        try:
            content = json.loads(raw).get("content", raw)
        except (ValueError, AttributeError):
            content = raw

        stamp = time.strftime("%Y-%m-%d %H:%M:%S")
        line = "[%s] %s" % (stamp, content)
        print(line, flush=True)

        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        with LOG_FILE.open("a", encoding="utf-8") as log:
            log.write(line + "\n")
            log.write("    raw: %s\n" % raw)

        body = b'{"ok":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args) -> None:
        """默认访问日志太吵，静音；消息本身已经打印过了。"""


def main() -> int:
    # Windows 控制台默认代码页可能打不出中文，显式切 UTF-8
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9099
    server = ThreadingHTTPServer(("127.0.0.1", port), WebhookHandler)
    print("webhook sink listening on http://127.0.0.1:%d/webhook  (Ctrl+C 退出)" % port, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
