"""Focused offline/loopback controls; never call the public operator smoke check."""

from contextlib import redirect_stderr, redirect_stdout
import io
import socketserver
import ssl
import threading
import time
import unittest
from unittest import mock
from urllib.parse import urlsplit

# Fail closed even if a future import accidentally starts a request worker or connection.
with mock.patch("subprocess.Popen", side_effect=AssertionError("Network on import")), \
        mock.patch("socket.create_connection", side_effect=AssertionError("Network on import")):
    import verify_hsts as smoke


def wire_response(status=200, fields=()):
    lines = ["HTTP/1.1 " + str(status) + " Fixture"]
    lines += [name + ": " + value for name, value in fields]
    lines += ["Content-Length: 0", "Connection: close", "", ""]
    return "\r\n".join(lines).encode("ascii")


class WireServer:
    """Send literal header fields through the helper's real HTTP parser and transport."""

    def __init__(self):
        self.reply = wire_response()
        self.routes = {}
        self.requests = []
        self.drip = False
        self.stop = threading.Event()
        owner = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                self.request.settimeout(0.5)
                try:
                    first = self.rfile.readline(8192).split()
                    if len(first) < 2:
                        return
                    path = first[1].decode("ascii")
                    for _ in range(100):
                        if self.rfile.readline(8192) in (b"\r\n", b"\n", b""):
                            break
                    owner.requests.append(path)
                    reply = owner.routes.get(path, owner.reply)
                    if owner.drip:
                        for byte in reply:
                            if owner.stop.wait(0.03):
                                return
                            self.wfile.write(bytes([byte]))
                    else:
                        self.wfile.write(reply)
                except OSError:
                    pass  # Expected when the bounded worker closes or is killed.

        self.server = socketserver.TCPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:" + str(self.server.server_address[1])
        self.thread = threading.Thread(
            target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        if self.thread.is_alive():
            raise AssertionError("Owned fixture failed to stop")

    def fetch(self, path="/", seconds=3):
        return smoke.fetch_headers(self.url + path, seconds)


class HstsCheckTest(unittest.TestCase):
    def test_default_cli_is_offline(self):
        with mock.patch.object(smoke, "fetch_headers", side_effect=AssertionError("Default CLI network")), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(0, smoke.main([]))

    def test_https_keeps_default_ca_and_hostname_verification(self):
        contexts = []
        default_context = ssl.create_default_context

        def context():
            contexts.append(default_context())
            return contexts[-1]

        # Inspect the real HTTPSConnection/context before request I/O; no public connection.
        with mock.patch.object(smoke.ssl, "create_default_context", side_effect=context), \
                mock.patch.object(smoke.http.client.HTTPSConnection, "request", autospec=True,
                                  side_effect=RuntimeError("stop before I/O")) as request:
            with self.assertRaisesRegex(RuntimeError, "stop before I/O"):
                smoke._read_headers("https://www.kiramanga.me/")
        self.assertEqual(1, len(contexts))
        self.assertEqual(ssl.CERT_REQUIRED, contexts[0].verify_mode)
        self.assertTrue(contexts[0].check_hostname)
        self.assertEqual("www.kiramanga.me", request.call_args.args[0].host)
        self.assertIs(contexts[0], request.call_args.args[0]._context)

    def test_https_accepts_exact_policy_ows_and_error_statuses(self):
        with WireServer() as server:
            for status in (200, 301, 404, 500):
                with self.subTest(status=status):
                    server.reply = wire_response(status, [
                        ("sTrIcT-TrAnSpOrT-SeCuRiTy", "\tmax-age=86400 \t"),
                    ])
                    response = server.fetch()
                    self.assertEqual(status, response[0])
                    smoke.check_response("https://api.kiramanga.me/", *response)

    def test_response_body_is_not_consumed(self):
        with WireServer() as server:
            server.reply = wire_response(503, [("Strict-Transport-Security", smoke.POLICY)]).replace(
                b"Content-Length: 0", b"Content-Length: 1000")
            # The advertised body never arrives: a body-reading client would fail here.
            smoke.check_response("https://api.kiramanga.me/", *server.fetch())

    def test_https_rejects_missing_raw_duplicates_and_wrong_values(self):
        field = "Strict-Transport-Security"
        cases = {
            "absent": [],
            "identical duplicate": [(field, smoke.POLICY), (field, smoke.POLICY)],
            "case-varied duplicate": [(field, smoke.POLICY), (field.lower(), "max-age=0")],
            "comma-combined": [(field, smoke.POLICY + ", " + smoke.POLICY)],
            "wrong age": [(field, "max-age=31536000")],
            "extra directive": [(field, smoke.POLICY + "; includeSubDomains")],
            "preload": [(field, smoke.POLICY + "; preload")],
            "empty": [(field, "")],
        }
        with WireServer() as server:
            for name, fields in cases.items():
                with self.subTest(name=name):
                    server.reply = wire_response(200, fields)
                    response = server.fetch()
                    if "duplicate" in name:
                        self.assertEqual(2, sum(key.lower() == field.lower() for key, _ in response[1]))
                    with self.assertRaises(smoke.CheckFailure):
                        smoke.check_response("https://kiramanga.me/", *response)

    def test_missing_first_hop_policy_is_not_hidden_by_redirect(self):
        with WireServer() as server:
            server.reply = wire_response(301, [("Location", server.url + "/with-hsts")])
            server.routes["/with-hsts"] = wire_response(200, [
                ("Strict-Transport-Security", smoke.POLICY),
            ])
            response = server.fetch()
            self.assertEqual(301, response[0])
            self.assertEqual(["/"], server.requests)
            with self.assertRaisesRegex(smoke.CheckFailure, "exactly one"):
                smoke.check_response("https://www.kiramanga.me/", *response)

    def test_http_all_hosts_preserve_path_query_and_www_target(self):
        with WireServer() as server:
            for host in ("kiramanga.me", "www.kiramanga.me", "api.kiramanga.me", "admin.kiramanga.me"):
                with self.subTest(host=host):
                    destination = "kiramanga.me" if host == "www.kiramanga.me" else host
                    server.reply = wire_response(301, [
                        ("Location", "https://" + destination + smoke.HTTP_PROBE_PATH),
                    ])
                    response = server.fetch(smoke.HTTP_PROBE_PATH)
                    smoke.check_response("http://" + host + smoke.HTTP_PROBE_PATH, *response)
            self.assertEqual([smoke.HTTP_PROBE_PATH] * 4, server.requests)

    def test_http_rejects_hsts_wrong_status_target_and_duplicate_location(self):
        correct = "https://kiramanga.me" + smoke.HTTP_PROBE_PATH
        cases = {
            "plaintext HSTS": (301, [("Location", correct), ("Strict-Transport-Security", smoke.POLICY)]),
            "wrong status": (302, [("Location", correct)]),
            "insecure target": (301, [("Location", correct.replace("https:", "http:"))]),
            "www not canonical": (301, [("Location", correct.replace("//kira", "//www.kira"))]),
            "lost path/query": (301, [("Location", "https://kiramanga.me/")]),
            "changed encoding": (301, [("Location", correct.replace("%2F", "/"))]),
            "duplicate location": (301, [("Location", correct), ("location", correct)]),
        }
        with WireServer() as server:
            for name, (status, fields) in cases.items():
                with self.subTest(name=name):
                    server.reply = wire_response(status, fields)
                    response = server.fetch(smoke.HTTP_PROBE_PATH)
                    with self.assertRaises(smoke.CheckFailure):
                        smoke.check_response("http://www.kiramanga.me" + smoke.HTTP_PROBE_PATH, *response)

    def test_slow_drip_cannot_extend_total_request_deadline(self):
        with WireServer() as server:
            server.drip = True
            workers = []
            real_popen = smoke.subprocess.Popen

            def observe_worker(*args, **kwargs):
                worker = real_popen(*args, **kwargs)
                workers.append(worker)
                return worker

            started = time.monotonic()
            with mock.patch.object(smoke.subprocess, "Popen", side_effect=observe_worker):
                with self.assertRaisesRegex(smoke.CheckFailure, "^Total request deadline exceeded$"):
                    server.fetch(seconds=1)
            self.assertLess(time.monotonic() - started, 2)
            self.assertEqual(1, len(workers))
            self.assertIsNotNone(workers[0].returncode)
            self.assertTrue(workers[0].stdout.closed)

    def test_transport_failure_is_not_a_header_pass(self):
        with WireServer() as server:
            server.reply = b"not an HTTP response\r\n\r\n"
            with self.assertRaisesRegex(smoke.CheckFailure, "Transport failed"):
                server.fetch()

    def test_explicit_cli_checks_all_eight_hops_even_after_failure(self):
        for fail_first in (False, True):
            with self.subTest(fail_first=fail_first):
                def response(url, seconds):
                    target = urlsplit(url)
                    if fail_first and url == "https://kiramanga.me/":
                        raise smoke.CheckFailure("fixture transport failure")
                    if target.scheme == "https":
                        return 503, [("Strict-Transport-Security", smoke.POLICY)]
                    host = "kiramanga.me" if target.hostname == "www.kiramanga.me" else target.hostname
                    return 301, [("Location", "https://" + host + smoke.HTTP_PROBE_PATH)]

                with mock.patch.object(smoke, "fetch_headers", side_effect=response) as fetch, \
                        redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    self.assertEqual(int(fail_first), smoke.main(["--check"]))
                urls = [call.args[0] for call in fetch.call_args_list]
                expected = [scheme + "://" + host + path
                            for host in ("kiramanga.me", "www.kiramanga.me", "api.kiramanga.me", "admin.kiramanga.me")
                            for scheme, path in (("https", "/"), ("http", smoke.HTTP_PROBE_PATH))]
                self.assertEqual(expected, urls)


if __name__ == "__main__":
    unittest.main()
