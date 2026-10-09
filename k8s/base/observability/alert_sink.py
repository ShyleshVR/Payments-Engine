"""Receives Alertmanager webhook notifications and logs one JSON line per alert.

Stands in for a pager / chat integration in the local cluster:
  kubectl -n payflow logs deploy/alert-sink
"""
import json
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):  # readiness probe
        self.send_response(200)
        self.end_headers()

    def do_POST(self):
        notification = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        received = datetime.now(timezone.utc).isoformat(timespec="seconds")
        lines = []
        for alert in notification.get("alerts", []):
            lines.append(json.dumps({
                "received": received,
                "status": alert.get("status"),
                "alertname": alert.get("labels", {}).get("alertname"),
                "severity": alert.get("labels", {}).get("severity"),
                "labels": alert.get("labels", {}),
                "summary": alert.get("annotations", {}).get("summary"),
                "runbook": alert.get("annotations", {}).get("runbook"),
                "startsAt": alert.get("startsAt"),
                "endsAt": alert.get("endsAt"),
            }))
        with lock:  # one write per batch, so concurrent notifications never interleave
            sys.stdout.write("".join(line + "\n" for line in lines))
            sys.stdout.flush()
        self.send_response(200)
        self.end_headers()


ThreadingHTTPServer(("", 9095), Handler).serve_forever()
