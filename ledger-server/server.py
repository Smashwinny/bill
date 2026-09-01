#!/usr/bin/env python3
import hmac
import json
import os
import sqlite3
import time
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BODY = 4 * 1024 * 1024
MAX_EVENTS = 200
CHANGE_LIMIT = 500


class LedgerStore:
    def __init__(self, database_path: str):
        self.database_path = database_path
        Path(database_path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS ledger_event(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_id TEXT NOT NULL UNIQUE,
                    transaction_id TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    received_at_ms INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS ledger_current(
                    transaction_id TEXT PRIMARY KEY,
                    payload TEXT NOT NULL,
                    seq INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS ledger_event_transaction_idx
                    ON ledger_event(transaction_id, seq);
            """)

    def connect(self):
        db = sqlite3.connect(self.database_path, timeout=15)
        db.execute("PRAGMA busy_timeout=15000")
        return db

    def sync(self, cursor: int, events: list[dict]) -> dict:
        if cursor < 0:
            raise ValueError("cursor must be non-negative")
        if len(events) > MAX_EVENTS:
            raise ValueError(f"at most {MAX_EVENTS} events per request")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            for event in events:
                event_id, transaction_id, payload = validate_event(event)
                encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
                inserted = db.execute(
                    "INSERT OR IGNORE INTO ledger_event(event_id,transaction_id,payload,received_at_ms) VALUES(?,?,?,?)",
                    (event_id, transaction_id, encoded, int(time.time() * 1000)),
                )
                if inserted.rowcount:
                    seq = db.execute("SELECT seq FROM ledger_event WHERE event_id=?", (event_id,)).fetchone()[0]
                    db.execute(
                        "INSERT INTO ledger_current(transaction_id,payload,seq) VALUES(?,?,?) "
                        "ON CONFLICT(transaction_id) DO UPDATE SET payload=excluded.payload,seq=excluded.seq",
                        (transaction_id, encoded, seq),
                    )
            rows = db.execute(
                "SELECT seq,payload FROM ledger_event WHERE seq>? ORDER BY seq LIMIT ?",
                (cursor, CHANGE_LIMIT + 1),
            ).fetchall()
            db.commit()
        self.backup_once_daily()
        has_more = len(rows) > CHANGE_LIMIT
        visible = rows[:CHANGE_LIMIT]
        return {
            "schema": "manual-ledger-sync-v1",
            "cursor": visible[-1][0] if visible else cursor,
            "has_more": has_more,
            "changes": [{"seq": seq, "payload": json.loads(payload)} for seq, payload in visible],
        }

    def backup_once_daily(self):
        if not Path(self.database_path).exists():
            return
        backup_dir = Path(self.database_path).parent / "backups"
        backup_dir.mkdir(exist_ok=True)
        target = backup_dir / f"ledger-{date.today().isoformat()}.sqlite3"
        if not target.exists():
            temporary = target.with_suffix(".next")
            with self.connect() as source, sqlite3.connect(temporary) as destination:
                source.backup(destination)
            temporary.replace(target)
        backups = sorted(backup_dir.glob("ledger-*.sqlite3"), reverse=True)
        for stale in backups[30:]:
            stale.unlink(missing_ok=True)


def validate_event(event: dict):
    if not isinstance(event, dict):
        raise ValueError("event must be an object")
    event_id = str(event.get("event_id", ""))
    transaction_id = str(event.get("transaction_id", ""))
    payload = event.get("payload")
    if not (8 <= len(event_id) <= 128 and 1 <= len(transaction_id) <= 128):
        raise ValueError("invalid event id or transaction id")
    if not isinstance(payload, dict) or payload.get("id") != transaction_id:
        raise ValueError("payload id mismatch")
    if payload.get("deleted") is not True:
        required = ("type", "amount_cents", "category", "account", "occurred_at_ms", "updated_at_ms")
        if any(name not in payload for name in required):
            raise ValueError("transaction payload is incomplete")
        if payload["type"] not in ("EXPENSE", "INCOME", "TRANSFER") or int(payload["amount_cents"]) <= 0:
            raise ValueError("invalid transaction payload")
    return event_id, transaction_id, payload


class LedgerHandler(BaseHTTPRequestHandler):
    server_version = "LocalLedger/1"
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/healthz":
            self.json_response(200, {"ok": True, "service": "local-ledger-sync"})
        elif self.path == "/":
            body = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                    "<title>本地账本同步</title><style>body{font-family:system-ui;max-width:680px;margin:12vh auto;"
                    "padding:24px;background:#f4f8f5;color:#17352e}section{background:white;padding:28px;border-radius:24px}"
                    "b{color:#0b705f}</style><section><h1>本地账本同步</h1><p><b>先存本地，再安全同步。</b></p>"
                    "<p>此服务仅接收已授权设备的加密 HTTPS 请求，不公开展示任何账目。</p></section>").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)
        else:
            self.json_response(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/v1/sync":
            return self.json_response(404, {"error": "not_found"})
        expected = self.server.sync_token
        supplied = self.headers.get("Authorization", "").removeprefix("Bearer ")
        if not supplied or not hmac.compare_digest(supplied, expected):
            return self.json_response(401, {"error": "unauthorized"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY:
                raise ValueError("invalid request size")
            request = json.loads(self.rfile.read(length))
            if request.get("schema") != "manual-ledger-sync-v1":
                raise ValueError("unsupported schema")
            response = self.server.store.sync(int(request.get("cursor", 0)), request.get("events", []))
            self.json_response(200, response)
        except (ValueError, TypeError, json.JSONDecodeError) as error:
            self.json_response(400, {"error": "invalid_request", "message": str(error)})
        except Exception:
            self.json_response(500, {"error": "server_error"})

    def json_response(self, code: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def log_message(self, format, *args):
        print(f"{self.address_string()} - {format % args}")


def main():
    token = os.environ.get("LEDGER_SYNC_TOKEN", "")
    if len(token) < 20:
        raise SystemExit("LEDGER_SYNC_TOKEN must contain at least 20 characters")
    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8790"))
    database = os.environ.get("LEDGER_DB", "/data/ledger.sqlite3")
    server = ThreadingHTTPServer((host, port), LedgerHandler)
    server.store = LedgerStore(database)
    server.sync_token = token
    print(f"local-ledger-sync listening on {host}:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
