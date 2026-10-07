"""Self-test of rpo_probe.py against a fake ledger API (no stack needed): python -m unittest tools/gameday/test_rpo_probe.py

Simulates an unplanned failover: the writer runs, the "primary" goes away (503s), the promoted side has lost the
last acknowledged writes; the checker must report exactly that loss and the unavailability window.
"""
import io
import json
import sys
import tempfile
import threading
import unittest
import urllib.parse
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rpo_probe  # noqa: E402


class FakeLedger:
    def __init__(self):
        self.refs: list[str] = []
        self.down = False
        self.lock = threading.Lock()

    def handler(self):
        fake = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def reply(self, status, body):
                raw = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])) or b"{}")
                if fake.down:
                    return self.reply(503, {"code": "cell_unavailable"})
                if self.path == "/v1/journal-entries":
                    with fake.lock:
                        fake.refs.append(body["reference"])
                return self.reply(201, {"id": "x" + str(len(fake.refs))})

            def do_GET(self):
                q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
                ref = q["reference"][0]
                return self.reply(200, {"data": [{"reference": ref}] if ref in fake.refs else []})

        return H


class RpoProbeTest(unittest.TestCase):
    def test_reports_lost_acknowledged_writes_and_unavailability(self):
        fake = FakeLedger()
        server = ThreadingHTTPServer(("127.0.0.1", 0), fake.handler())
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_address[1]}"
        with tempfile.TemporaryDirectory() as d:
            state = Path(d) / "probe.json"
            with redirect_stdout(io.StringIO()):
                rpo_probe.setup(SimpleNamespace(state=str(state), base=base, tenant=None))
                rpo_probe.write(SimpleNamespace(state=str(state), base=None, rate=200.0, duration=0.3, timeout=2))
            acked = len(fake.refs)
            self.assertGreater(acked, 5)
            fake.down = True                                  # region lost
            with redirect_stdout(io.StringIO()):
                rpo_probe.write(SimpleNamespace(state=str(state), base=None, rate=200.0, duration=0.2, timeout=2))
            fake.down = False
            del fake.refs[-3:]                                # promoted replica misses the last 3 commits
            out = io.StringIO()
            with redirect_stdout(out), self.assertRaises(SystemExit):
                rpo_probe.check(SimpleNamespace(state=str(state), base=None, margin=5))
            report = json.loads(out.getvalue())
            self.assertEqual(report["last_acknowledged"], acked)
            self.assertEqual(report["last_present"], acked - 3)
            self.assertEqual(report["lost_acknowledged_writes"], 3)
            self.assertGreater(report["rpo_seconds"], 0)
            self.assertIn("still_failing_since", report)
            self.assertEqual(report["holes_in_last_20"], [])

        server.shutdown()


if __name__ == "__main__":
    unittest.main()
