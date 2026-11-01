"""Test-only HTTPS admin gateway. Never accepts real credentials or logs request bodies."""

import argparse
import json
import ssl
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_POST(self):
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "https://localhost:22333/forbidden")
            self.end_headers()
            return
        if self.path == "/forbidden":
            raise AssertionError("Credential request followed a redirect")
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        authorized = (
            data.get("headers", {}).get("Authorization") == "Bearer fixture-api-token"
        )
        body = {"registration": {"value": True}, "users": {"registered": 2}}
        payload = json.dumps(
            {"status": 200 if authorized else 401, "body": body if authorized else {}}
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    server = HTTPServer(("127.0.0.1", 22333), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(args.cert, args.key)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.serve_forever()
