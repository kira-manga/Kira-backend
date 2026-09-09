#!/usr/bin/env python3
"""Explicit, read-only server3 HSTS smoke check; Python 3.10+, standard library only."""

import argparse
import http.client
import json
from pathlib import Path
import ssl
import subprocess
import sys
import time
from urllib.parse import urlsplit


HOSTS = ("kiramanga.me", "www.kiramanga.me", "api.kiramanga.me", "admin.kiramanga.me")
POLICY = "max-age=86400"
HTTP_PROBE_PATH = "/__kira_hsts_probe__/part%2Fone?first=1&second=a%2Bb"


class CheckFailure(Exception):
    pass


def _read_headers(url):
    """Runs only in the disposable worker; HTTPConnection never follows redirects."""
    target = urlsplit(url)
    if (target.scheme not in ("http", "https") or not target.hostname
            or target.username is not None or target.password is not None or target.fragment):
        raise ValueError("Expected a credential-free HTTP(S) URL without a fragment")
    path = target.path or "/"
    if target.query:
        path += "?" + target.query
    if target.scheme == "https":
        connection = http.client.HTTPSConnection(
            target.hostname, target.port, context=ssl.create_default_context())
    else:
        connection = http.client.HTTPConnection(target.hostname, target.port)
    try:
        connection.request("GET", path, headers={
            "Connection": "close", "User-Agent": "kira-server3-hsts-smoke/1",
        })
        with connection.getresponse() as response:
            if response.status < 200:
                raise CheckFailure("Expected a final HTTP response")
            if response.headers.defects:
                raise CheckFailure("Malformed response headers")
            # Do not merge fields, take only a first value, or consume a response body.
            # getresponse() also returns 4xx/5xx headers without a health-status assertion.
            return response.status, list(response.headers.raw_items())
    finally:
        connection.close()


def _worker(url):
    try:
        status, headers = _read_headers(url)
        result = {"status": status, "headers": headers}
        code = 0
    except (OSError, ValueError, http.client.HTTPException, CheckFailure) as failure:
        # Never print response bodies, cookies, other raw headers, or exception messages.
        result = {"error": type(failure).__name__}
        code = 1
    print(json.dumps(result))
    return code


def fetch_headers(url, seconds=10.0):
    """Bound the whole request, including DNS and worker teardown, not socket idle time."""
    if not 1 <= seconds <= 60:
        raise ValueError("Deadline must be between 1 and 60 seconds")
    deadline = time.monotonic() + seconds
    cleanup_reserve = min(0.5, seconds / 5)
    try:
        process = subprocess.Popen(
            [sys.executable, "-I", "-B", str(Path(__file__).resolve()), "--_request", url],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
    except OSError as failure:
        raise CheckFailure("Cannot start request worker (" + type(failure).__name__ + ")") from failure
    try:
        output, _ = process.communicate(
            timeout=max(0.0, deadline - cleanup_reserve - time.monotonic()))
    except subprocess.TimeoutExpired as failure:
        raise CheckFailure("Total request deadline exceeded") from failure
    finally:
        # A socket timeout/aborted thread cannot reliably interrupt DNS. Kill this owned
        # process instead, and charge bounded reap/pipe cleanup to the SAME deadline.
        # Deliberately avoid Popen's context manager, whose exit can wait without a bound.
        if process.poll() is None:
            process.kill()
        try:
            process.wait(timeout=max(0.0, deadline - time.monotonic()))
        except subprocess.TimeoutExpired as failure:
            raise CheckFailure("Request-worker cleanup deadline exceeded") from failure
        finally:
            process.stdout.close()
    try:
        result = json.loads(output)
    except (ValueError, UnicodeError) as failure:
        raise CheckFailure("Request worker returned no valid result") from failure
    if process.returncode != 0:
        raise CheckFailure("Transport failed (" + result.get("error", "worker failure") + ")")
    return result["status"], result["headers"]


def check_response(url, status, headers):
    def values(name):
        return [value.strip(" \t") for field, value in headers if field.lower() == name]

    sts = values("strict-transport-security")
    target = urlsplit(url)
    if target.scheme == "https":
        if len(sts) != 1:
            raise CheckFailure("Expected exactly one HSTS field; received " + str(len(sts)))
        if sts[0] != POLICY:
            raise CheckFailure("HSTS must be exactly " + POLICY)
    elif target.scheme == "http":
        if sts:
            raise CheckFailure("Plain HTTP must not contain HSTS")
        host = "kiramanga.me" if target.hostname == "www.kiramanga.me" else target.hostname
        location = "https://" + host + (target.path or "/")
        if target.query:
            location += "?" + target.query
        if status != 301 or values("location") != [location]:
            raise CheckFailure("Expected one 301 HTTPS redirect preserving the host policy and path/query")
    else:
        raise CheckFailure("Unsupported response scheme")


def _seconds(value):
    try:
        seconds = float(value)
    except ValueError as failure:
        raise argparse.ArgumentTypeError("Deadline must be between 1 and 60 seconds") from failure
    if not 1 <= seconds <= 60:
        raise argparse.ArgumentTypeError("Deadline must be between 1 and 60 seconds")
    return seconds


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="explicitly contact all four installed public hosts (after authorized installation)")
    parser.add_argument("--timeout", type=_seconds, default=10.0, metavar="SECONDS",
                        help="total deadline per request, including cleanup (1–60; default: 10)")
    args = parser.parse_args(argv)
    if not args.check:
        parser.print_help()
        return 0
    failed = False
    for host in HOSTS:
        for scheme, path in (("https", "/"), ("http", HTTP_PROBE_PATH)):
            url = scheme + "://" + host + path
            try:
                status, headers = fetch_headers(url, args.timeout)
                check_response(url, status, headers)
            except CheckFailure as failure:
                print("FAIL " + url + ": " + str(failure), file=sys.stderr)
                failed = True
            else:
                print("PASS " + url + " (HTTP " + str(status) + ")")
    return 1 if failed else 0


if __name__ == "__main__":
    # Internal worker entry point, not a second operator mode. Import/default CLI are offline.
    if len(sys.argv) == 3 and sys.argv[1] == "--_request":
        raise SystemExit(_worker(sys.argv[2]))
    raise SystemExit(main())
