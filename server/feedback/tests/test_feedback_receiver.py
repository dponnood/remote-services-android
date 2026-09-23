import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from feedback_receiver import PayloadError, store_payload, validate_payload


def valid_payload():
    return {
        "schemaVersion": 1,
        "installationId": "00000000-0000-0000-0000-000000000001",
        "createdAt": "2026-09-22T00:00:00Z",
        "description": "test",
        "logs": [
            {
                "timestamp": "2026-09-22T00:00:00Z",
                "level": "ERROR",
                "eventCode": "WEB_LOAD_FAILED",
                "message": "load failed",
                "context": {"route": "WAN"},
            }
        ],
    }


class FeedbackReceiverTest(unittest.TestCase):
    def test_source_remains_compatible_with_server_python36(self):
        """The production host intentionally uses the system Python 3.6 runtime."""

        source = (Path(__file__).resolve().parents[1] / "feedback_receiver.py").read_text(encoding="utf-8")
        self.assertNotIn("from __future__ import annotations", source)
        self.assertNotIn("missing_ok=", source)
        self.assertNotIn("from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer", source)
        self.assertNotRegex(source, r"\b(?:list|dict|tuple|set)\[")

    def test_valid_payload_is_accepted_and_stored_private(self):
        payload = validate_payload(json.dumps(valid_payload()).encode())
        with tempfile.TemporaryDirectory() as directory:
            path = store_payload(payload, Path(directory) / "inbox")
            self.assertTrue(path.name.startswith("feedback-"))
            if os.name != "nt":
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["schemaVersion"], 1)

    def test_unknown_field_is_rejected(self):
        payload = valid_payload()
        payload["filename"] = "../../escape"
        with self.assertRaises(PayloadError):
            validate_payload(json.dumps(payload).encode())

    def test_invalid_level_and_non_object_log_are_rejected(self):
        payload = valid_payload()
        payload["logs"][0]["level"] = "TRACE"
        with self.assertRaises(PayloadError):
            validate_payload(json.dumps(payload).encode())
        payload = valid_payload()
        payload["logs"] = ["raw log"]
        with self.assertRaises(PayloadError):
            validate_payload(json.dumps(payload).encode())

    def test_size_limit_is_rejected(self):
        payload = valid_payload()
        payload["description"] = "x" * 2_049
        with self.assertRaises(PayloadError):
            validate_payload(json.dumps(payload).encode())

    def test_unredacted_urls_credentials_and_private_addresses_are_rejected(self):
        cases = [
            ("description", "open https://example.test/luci"),
            ("description", "password=hunter2"),
            ("description", "Cookie: session=abc"),
            ("message", "Authorization: Bearer abc"),
            ("message", "token=abc"),
            ("message", "private 192.168.1.2"),
            ("message", "loopback ::1"),
            ("message", "ula fc00::1"),
            ("message", "link-local [fe80::1]"),
        ]
        for field, value in cases:
            payload = valid_payload()
            if field == "description":
                payload["description"] = value
            else:
                payload["logs"][0]["message"] = value
            with self.subTest(field=field, value=value), self.assertRaises(PayloadError):
                validate_payload(json.dumps(payload).encode())

    def test_redaction_placeholders_are_accepted(self):
        payload = valid_payload()
        payload["description"] = "url=[URL_REDACTED] password=[REDACTED] ip=[PRIVATE_IP]"
        payload["logs"][0]["message"] = "Authorization=[REDACTED] from [PRIVATE_IP]"
        self.assertEqual(validate_payload(json.dumps(payload).encode()), payload)

    def test_inbox_retention_keeps_newest_files_under_both_caps(self):
        with tempfile.TemporaryDirectory() as directory:
            inbox = Path(directory) / "inbox"
            first = store_payload(valid_payload(), inbox, max_files=10, max_bytes=10_000)
            second = store_payload(valid_payload(), inbox, max_files=10, max_bytes=10_000)
            first_size = first.stat().st_size
            os.utime(first, (1, 1))
            third = store_payload(valid_payload(), inbox, max_files=2, max_bytes=first_size * 2)
            files = sorted(inbox.glob("feedback-*.json"))
            self.assertEqual(len(files), 2)
            self.assertNotIn(first, files)
            self.assertIn(third, files)
            self.assertLessEqual(sum(path.stat().st_size for path in files), first_size * 2)
            self.assertTrue(second.exists())

    def test_inbox_retention_does_not_follow_or_delete_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inbox = root / "inbox"
            outside = root / "outside.json"
            outside.write_text("keep", encoding="utf-8")
            inbox.mkdir()
            link = inbox / "feedback-outside.json"
            try:
                link.symlink_to(outside)
            except (OSError, NotImplementedError) as exc:
                self.skipTest(f"symlink unavailable: {exc}")
            store_payload(valid_payload(), inbox, max_files=1, max_bytes=10_000)
            self.assertTrue(link.is_symlink())
            self.assertTrue(outside.exists())


if __name__ == "__main__":
    unittest.main()
