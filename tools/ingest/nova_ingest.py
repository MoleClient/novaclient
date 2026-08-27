#!/usr/bin/env python3
"""Collector for NovaClient movement contributions.

Binds loopback only. It is meant to sit behind a Cloudflare Tunnel, which dials out from this
machine, so there is no inbound port, no exposed home address, and nothing to port-forward.

The design rule here is that this process does as little as possible with bytes a stranger sent.
It never fully decompresses a request, never executes anything, never touches a database, and
builds file paths only out of characters it has already validated. Batches land as raw gzip
members appended to one file per session — concatenated members are a valid gzip stream, so the
whole session reads back with a plain ``gzip.open`` and nothing is ever recompressed.

Usage:
    python3 nova_ingest.py --root ~/nova-data --token nova-contrib-1
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
import shutil
import signal
import sys
import threading
import zlib
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# A real 400-row batch measured 26 KB gzipped, and the worst plausible one — full entity slots and
# a large dictionary — is under 100 KB. 1 MB is an order of magnitude of headroom and still an
# order of magnitude tighter than the 8 MB this used to allow. That matters more than the rate
# limit does: on Cloudflare's free plan a blocked IP is only held for 10 seconds, so the ceiling on
# a sustained flood is (requests allowed) x (this number), and this is the half we control.
MAX_BODY_BYTES = 1024 * 1024
# Only ever inflate enough to read the header line. Never inflate the whole body: that is what
# turns a 1 MB request into a disk-filling one.
MAX_HEADER_BYTES = 512 * 1024
# Refuse new writes once the tree reaches this, so a flood costs bounded disk instead of the box.
DEFAULT_QUOTA_GB = 200

# Path components are built only from strings matching these. The pseudonym and session id arrive
# inside attacker-controlled JSON, so they are validated rather than sanitised — anything that is
# not obviously one of ours is rejected outright instead of being repaired into a path.
SESSION_RE = re.compile(r"^[0-9a-fA-F-]{8,64}$")
PSEUDONYM_RE = re.compile(r"^[0-9a-fA-F]{8,64}$")

write_lock = threading.Lock()


class Collector:
    def __init__(self, root: Path, token: str, quota_bytes: int) -> None:
        self.root = root
        self.token = token
        self.quota_bytes = quota_bytes
        self.batches = 0
        self.bytes_stored = 0
        self.rejected = 0
        self.started = datetime.now(timezone.utc)

    def over_quota(self) -> bool:
        usage = shutil.disk_usage(self.root)
        if usage.free < 2 * 1024 * 1024 * 1024:
            return True
        return self.tree_bytes() >= self.quota_bytes

    def tree_bytes(self) -> int:
        return sum(p.stat().st_size for p in self.root.rglob("*.ndjson.gz") if p.is_file())

    def store(self, body: bytes, header: dict) -> Path:
        session = header.get("session", "")
        pseudonym = header.get("pseudonym", "")
        if not SESSION_RE.match(session) or not PSEUDONYM_RE.match(pseudonym):
            raise ValueError("bad session or pseudonym")

        day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        target = self.root / day / pseudonym / f"{session}.ndjson.gz"
        # Belt and braces: even with the regexes above, refuse anything that escaped the root.
        resolved = target.resolve()
        if not str(resolved).startswith(str(self.root.resolve())):
            raise ValueError("path escaped the data root")

        with write_lock:
            resolved.parent.mkdir(parents=True, exist_ok=True)
            with open(resolved, "ab") as handle:
                handle.write(body)
        self.batches += 1
        self.bytes_stored += len(body)
        return resolved


def read_header(body: bytes) -> dict:
    """Inflate only as far as the first newline and parse that line as the batch header."""
    decompressor = zlib.decompressobj(zlib.MAX_WBITS | 16)
    chunk = decompressor.decompress(body, MAX_HEADER_BYTES)
    newline = chunk.find(b"\n")
    if newline < 0:
        raise ValueError("no header line")
    header = json.loads(chunk[:newline].decode("utf-8"))
    if not isinstance(header, dict) or header.get("t") != "header":
        raise ValueError("first line is not a header")
    return header


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    collector: Collector

    def log_message(self, fmt: str, *args) -> None:  # noqa: A002 - stdlib signature
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _reply(self, code: int, payload: bytes = b"") -> None:
        # Errors are answered without reading the request body — that refusal is the whole point of
        # the size cap. But on a keep-alive connection the unread body is then parsed as the next
        # request line, which produces a bogus 414 locally and a 502 through the tunnel, so the
        # client never sees the real status. Closing the connection is the correct way to reject a
        # request you have not drained.
        if code >= 400:
            self.close_connection = True
        self.send_response(code)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Content-Type", "application/json")
        if code >= 400:
            self.send_header("Connection", "close")
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def do_HEAD(self) -> None:  # noqa: N802 - stdlib signature
        # Without this the base handler answers 501, which makes `curl -I` on the health
        # endpoint look like a broken tunnel rather than a working one.
        self._reply(200 if self.path == "/healthz" else 404)

    def do_GET(self) -> None:  # noqa: N802 - stdlib signature
        if self.path != "/healthz":
            self._reply(404)
            return
        c = self.collector
        self._reply(200, json.dumps({
            "ok": True,
            "batches": c.batches,
            "bytes": c.bytes_stored,
            "rejected": c.rejected,
            "since": c.started.isoformat(),
        }).encode())

    def do_POST(self) -> None:  # noqa: N802 - stdlib signature
        c = self.collector
        if self.path != "/v1/ticks":
            self._reply(404)
            return
        if self.headers.get("Authorization", "") != f"Bearer {c.token}":
            c.rejected += 1
            self._reply(401)
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._reply(411)
            return
        if length <= 0 or length > MAX_BODY_BYTES:
            c.rejected += 1
            self._reply(413)
            return
        if c.over_quota():
            # 503 rather than an error the client treats as permanent: it will spool and retry,
            # which is the behaviour we want once space is freed.
            self._reply(503)
            return

        body = self.rfile.read(length)
        if len(body) != length:
            self._reply(400)
            return

        try:
            header = read_header(body)
            path = c.store(body, header)
        except Exception as error:
            c.rejected += 1
            self._reply(400, json.dumps({"error": str(error)}).encode())
            return

        self.log_message(
            "stored %d bytes schema=%s seq=%s -> %s",
            len(body), header.get("schema"), header.get("seq"), path.name,
        )
        self._reply(204)


def main() -> int:
    parser = argparse.ArgumentParser(description="NovaClient contribution collector")
    parser.add_argument("--root", default="~/nova-data", help="where recordings are written")
    parser.add_argument("--token", default="nova-contrib-1", help="must match the jar's token")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--quota-gb", type=int, default=DEFAULT_QUOTA_GB)
    args = parser.parse_args()

    root = Path(args.root).expanduser()
    root.mkdir(parents=True, exist_ok=True)

    Handler.collector = Collector(root, args.token, args.quota_gb * 1024 ** 3)
    # Loopback only. The tunnel is the sole way in; binding 0.0.0.0 would undo that in one line.
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True

    def stop(*_):
        # shutdown() blocks until serve_forever() returns, and the handler runs on the thread
        # that is inside serve_forever() — calling it here directly deadlocks on Ctrl-C.
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    print(f"collector on 127.0.0.1:{args.port} -> {root} (quota {args.quota_gb} GB)", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
