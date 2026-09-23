#!/usr/bin/env python3
"""Small localhost-only JSON receiver for explicit Remote Services feedback.

The public TLS endpoint is terminated by nginx. This process intentionally binds
only to 127.0.0.1 and writes one immutable JSON payload per request with mode 0600.
"""

import argparse
import ipaddress
import json
import os
import re
import secrets
import stat
import tempfile
import threading
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from socketserver import ThreadingMixIn
from typing import Any, Dict, List, Tuple
from uuid import UUID


DEFAULT_BIND = "127.0.0.1"
DEFAULT_PORT = 18765
DEFAULT_INBOX = Path("/var/lib/remote-services-feedback/inbox")
MAX_BODY_BYTES = 512 * 1024
MAX_LOGS = 500
MAX_INBOX_FILES = 1_000
MAX_INBOX_BYTES = 64 * 1024 * 1024
ALLOWED_KEYS = {"schemaVersion", "installationId", "createdAt", "description", "logs"}
EVENT_CODE = re.compile(r"^[A-Z0-9_.-]{1,64}$")
LEVELS = {"DEBUG", "INFO", "WARN", "ERROR"}
REDACTION_MARKERS = {"[REDACTED]", "[URL_REDACTED]", "[PRIVATE_IP]"}
FULL_URL = re.compile(r"(?i)(?<![a-z0-9_])https?://[^\s\"'<>]+")
SENSITIVE_ASSIGNMENT = re.compile(
    r"(?i)\b(?:authorization|cookie|set-cookie|password|passwd|secret|token|"
    r"api[-_]?key|access[-_]?token|refresh[-_]?token)\b"
    r"\s*(?:=|:)\s*(?P<value>\[(?:REDACTED|URL_REDACTED|PRIVATE_IP)\]|[^\s,;]+)"
)
PRIVATE_IPV4 = re.compile(
    r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])"
)
PRIVATE_IPV6 = re.compile(
    r"(?i)(?<![0-9a-f:])\[?(?:[0-9a-f]{0,4}:){2,7}[0-9a-f:.]{0,15}"
    r"(?:%[0-9a-z_.-]+)?\]?(?![0-9a-f:])"
)
_STORE_LOCK = threading.Lock()


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    """Threaded HTTP server compatible with the host's Python 3.6 runtime."""

    daemon_threads = True
    allow_reuse_address = True


class PayloadError(ValueError):
    """Client supplied an invalid feedback payload."""


def _contains_private_ip(value: str) -> bool:
    """Return true for raw private/local IPv4 or IPv6 literals in a string."""

    candidates = list(PRIVATE_IPV4.finditer(value)) + list(PRIVATE_IPV6.finditer(value))
    for match in candidates:
        literal = match.group(0).strip("[]").split("%", 1)[0]
        try:
            address = ipaddress.ip_address(literal)
        except ValueError:
            continue
        if address.is_private or address.is_loopback or address.is_link_local or address.is_unspecified:
            return True
    return False


def _assert_sanitized_text(value: str) -> None:
    """Reject defense-in-depth leaks even if an older client skipped sanitizing."""

    if FULL_URL.search(value):
        raise PayloadError("payload contains an unsanitized URL")
    if _contains_private_ip(value):
        raise PayloadError("payload contains an unsanitized private address")
    for match in SENSITIVE_ASSIGNMENT.finditer(value):
        if match.group("value").strip() not in REDACTION_MARKERS:
            raise PayloadError("payload contains an unsanitized credential")


def _stored_feedback_files(inbox: Path) -> List[Tuple[Path, int, int]]:
    """List only service-owned regular files without following symlinks."""

    if inbox.is_symlink():
        raise OSError("feedback inbox must not be a symlink")
    records: List[Tuple[Path, int, int]] = []
    for entry in inbox.iterdir():
        # A symlink may point outside the inbox and must never participate in
        # retention or deletion.  lstat below also avoids following a race.
        if entry.is_symlink() or entry.parent != inbox:
            continue
        if not entry.name.startswith("feedback-") or entry.suffix != ".json":
            continue
        try:
            metadata = entry.lstat()
        except FileNotFoundError:
            continue
        if not stat.S_ISREG(metadata.st_mode):
            continue
        records.append((entry, metadata.st_mtime_ns, metadata.st_size))
    return sorted(records, key=lambda item: (item[1], item[0].name))


def prune_inbox(inbox: Path, max_files: int = MAX_INBOX_FILES, max_bytes: int = MAX_INBOX_BYTES) -> None:
    """Delete oldest generated payload files until both retention caps hold."""

    if max_files < 1 or max_bytes < 1:
        raise ValueError("retention caps must be positive")
    records = _stored_feedback_files(inbox)
    total_bytes = sum(item[2] for item in records)
    while len(records) > max_files or total_bytes > max_bytes:
        path, _mtime_ns, size = records.pop(0)
        try:
            # unlink never follows a symlink; the initial scan also excludes
            # symlinks, so a replacement can at worst remove that link itself.
            path.unlink()
        except FileNotFoundError:
            pass
        total_bytes = max(0, total_bytes - size)


def validate_payload(raw: bytes) -> Dict[str, Any]:
    if len(raw) > MAX_BODY_BYTES:
        raise PayloadError("payload too large")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PayloadError("invalid JSON") from exc
    if not isinstance(payload, dict):
        raise PayloadError("payload must be an object")
    if set(payload) != ALLOWED_KEYS:
        raise PayloadError("unexpected or missing fields")
    if isinstance(payload["schemaVersion"], bool) or not isinstance(payload["schemaVersion"], int) or payload["schemaVersion"] != 1:
        raise PayloadError("unsupported schema version")
    try:
        UUID(str(payload["installationId"]))
    except (ValueError, TypeError, AttributeError) as exc:
        raise PayloadError("invalid installation id") from exc
    if not isinstance(payload["createdAt"], str) or len(payload["createdAt"]) > 80:
        raise PayloadError("invalid timestamp")
    if not isinstance(payload["description"], str) or len(payload["description"]) > 2_048:
        raise PayloadError("invalid description")
    _assert_sanitized_text(payload["description"])
    logs = payload["logs"]
    if not isinstance(logs, list) or len(logs) > MAX_LOGS:
        raise PayloadError("invalid logs")
    for log in logs:
        if not isinstance(log, dict):
            raise PayloadError("each log must be an object")
        required = {"timestamp", "level", "eventCode", "message", "context"}
        if not required.issubset(log) or not set(log).issubset(required | {"errorType"}):
            raise PayloadError("invalid log fields")
        if not isinstance(log["timestamp"], str) or len(log["timestamp"]) > 80:
            raise PayloadError("invalid log timestamp")
        if log["level"] not in LEVELS:
            raise PayloadError("invalid log level")
        if not isinstance(log["eventCode"], str) or not EVENT_CODE.fullmatch(log["eventCode"]):
            raise PayloadError("invalid event code")
        if not isinstance(log["message"], str) or len(log["message"]) > 2_048:
            raise PayloadError("invalid log message")
        _assert_sanitized_text(log["message"])
        if not isinstance(log["context"], dict) or len(log["context"]) > 32:
            raise PayloadError("invalid log context")
        for key, value in log["context"].items():
            if not isinstance(key, str) or len(key) > 64 or not isinstance(value, str) or len(value) > 256:
                raise PayloadError("invalid log context value")
            _assert_sanitized_text(value)
        if "errorType" in log and not isinstance(log["errorType"], str):
            raise PayloadError("invalid error type")
        if "errorType" in log:
            _assert_sanitized_text(log["errorType"])
    return payload


def store_payload(
    payload: Dict[str, Any],
    inbox: Path,
    *,
    max_files: int = MAX_INBOX_FILES,
    max_bytes: int = MAX_INBOX_BYTES,
) -> Path:
    """Atomically store a validated payload with a server-generated safe filename."""

    if max_files < 1 or max_bytes < 1:
        raise ValueError("retention caps must be positive")
    serialized = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    serialized_size = len(serialized.encode("utf-8")) + 1
    if serialized_size > max_bytes:
        raise PayloadError("payload exceeds inbox size cap")
    with _STORE_LOCK:
        if inbox.is_symlink():
            raise OSError("feedback inbox must not be a symlink")
        inbox.mkdir(mode=0o700, parents=True, exist_ok=True)
        if inbox.is_symlink() or not inbox.is_dir():
            raise OSError("feedback inbox must be a directory")
        os.chmod(inbox, 0o700)
        now = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        destination = inbox / f"feedback-{now}-{secrets.token_hex(8)}.json"
        fd, temporary_name = tempfile.mkstemp(prefix=".feedback-", suffix=".tmp", dir=inbox)
        temporary = Path(temporary_name)
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
                stream.write(serialized)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            # chmod is applied after closing so the same code can run in the
            # Windows-only local test harness, while Linux enforces 0600 on disk.
            os.chmod(temporary, 0o600)
            os.replace(temporary, destination)
            os.chmod(destination, 0o600)
            prune_inbox(inbox, max_files=max_files, max_bytes=max_bytes)
            return destination
        except Exception:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass
            raise


class FeedbackHandler(BaseHTTPRequestHandler):
    server_version = "RemoteServicesFeedback/1"
    sys_version = ""

    @property
    def inbox(self) -> Path:
        return self.server.inbox  # type: ignore[attr-defined]

    def _reply(self, status: int, message: str) -> None:
        body = json.dumps({"ok": status < 400, "message": message}, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/feedback":
            self._reply(HTTPStatus.NOT_FOUND, "not found")
            return
        content_type = self.headers.get("Content-Type", "")
        if content_type.split(";", 1)[0].strip().lower() != "application/json":
            self._reply(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "content type must be application/json")
            return
        if self.headers.get("Content-Encoding"):
            self._reply(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "content encoding is not supported")
            return
        try:
            content_length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            content_length = -1
        if content_length < 0 or content_length > MAX_BODY_BYTES:
            self._reply(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "invalid content length")
            return
        raw = self.rfile.read(content_length)
        if len(raw) != content_length:
            self._reply(HTTPStatus.BAD_REQUEST, "incomplete request")
            return
        try:
            payload = validate_payload(raw)
            path = store_payload(payload, self.inbox)
        except PayloadError as exc:
            self._reply(HTTPStatus.BAD_REQUEST, str(exc))
            return
        except OSError:
            self._reply(HTTPStatus.INTERNAL_SERVER_ERROR, "storage failure")
            return
        self._reply(HTTPStatus.CREATED, path.name)

    def do_GET(self) -> None:  # noqa: N802
        self._reply(HTTPStatus.NOT_FOUND, "not found")

    def do_PUT(self) -> None:  # noqa: N802
        self._reply(HTTPStatus.METHOD_NOT_ALLOWED, "method not allowed")

    def do_DELETE(self) -> None:  # noqa: N802
        self._reply(HTTPStatus.METHOD_NOT_ALLOWED, "method not allowed")

    def log_message(self, format: str, *args: Any) -> None:
        # Keep operational logs on stderr; request bodies are never logged.
        super().log_message(format, *args)


class FeedbackServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, bind: str, port: int, inbox: Path):
        super().__init__((bind, port), FeedbackHandler)
        self.inbox = inbox


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default=DEFAULT_BIND)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--inbox", type=Path, default=DEFAULT_INBOX)
    args = parser.parse_args()
    if args.bind not in {"127.0.0.1", "::1"}:
        raise SystemExit("feedback receiver must bind to localhost")
    if not (1 <= args.port <= 65535):
        raise SystemExit("invalid port")
    os.umask(0o077)
    with FeedbackServer(args.bind, args.port, args.inbox) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
