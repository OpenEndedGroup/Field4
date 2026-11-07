"""Command line entry point.

    python/fieldsentences "Listen if you will birdsong reflects off the walls"
    echo "one per line" | python/fieldsentences -
    python/fieldsentences --serve 9500        # keep the model warm for interactive use
"""

import argparse
import json
import sys

from . import backends
from .core import analyse


def _build_parser():
    ap = argparse.ArgumentParser(
        prog="python/fieldsentences",
        description="Dump constituency and dependency trees for a sentence as JSON.",
    )
    ap.add_argument(
        "sentence",
        nargs="*",
        help="sentence to parse; use '-' to read lines from stdin",
    )
    ap.add_argument(
        "-b", "--backend", default="stanza", choices=backends.NAMES,
        help="stanza (default, no Java) or corenlp (true Stanford Dependencies)",
    )
    ap.add_argument(
        "-o", "--output", help="write JSON here instead of stdout",
    )
    ap.add_argument(
        "--indent", type=int, default=2,
        help="JSON indent; 0 for one compact object per line",
    )
    ap.add_argument(
        "--url", default="http://localhost:9000",
        help="CoreNLP server URL (corenlp backend only)",
    )
    ap.add_argument(
        "--lang", default="en", help="language code (stanza backend only)",
    )
    ap.add_argument(
        "--download", action="store_true",
        help="fetch the stanza models and exit",
    )
    ap.add_argument(
        "--serve", metavar="PORT", type=int,
        help="run an HTTP server so the model loads once; POST or GET ?q=...",
    )
    ap.add_argument("--device", choices=("cpu", "cuda"), default="cpu",
                    help="Stanza inference device; CPU keeps VRAM free for rendering")
    ap.add_argument("--threads", type=int, default=2, help="CPU inference threads")
    return ap


def _kwargs(args):
    if args.backend == "corenlp":
        return {"url": args.url}
    return {"lang": args.lang, "device": args.device}


def _serve(port, backend, kwargs):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from urllib.parse import parse_qs, urlparse

    # Warm the model before accepting connections, so the first request is not
    # the slow one.
    analyse("Warming up.", backend=backend, **kwargs)

    # The warmed model is shared; serialize its stateful inference across HTTP workers.
    from threading import Lock
    inference = Lock()

    class Handler(BaseHTTPRequestHandler):
        def _respond(self, text):
            try:
                with inference:
                    parsed = analyse(text, backend=backend, **kwargs)
                body = json.dumps(parsed).encode("utf-8")
                status = 200
            except Exception as exc:  # a bad sentence must not kill the server
                body = json.dumps({"error": str(exc)}).encode("utf-8")
                status = 400
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            query = parse_qs(urlparse(self.path).query)
            text = (query.get("q") or [""])[0]
            self._respond(text)

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            self._respond(self.rfile.read(length).decode("utf-8"))

        def log_message(self, *a):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("listening on http://127.0.0.1:%d  (GET ?q=... or POST body)" % port,
          file=sys.stderr, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


def main(argv=None):
    args = _build_parser().parse_args(argv)

    if args.threads < 1:
        raise ValueError("--threads must be positive")
    if args.backend == "stanza" and not args.download:
        import torch
        torch.set_num_threads(args.threads)

    if args.download:
        backends.get("stanza").download(args.lang)
        return 0

    kwargs = _kwargs(args)

    if args.serve:
        return _serve(args.serve, args.backend, kwargs)

    if args.sentence == ["-"] or (not args.sentence and not sys.stdin.isatty()):
        text = sys.stdin.read()
    elif args.sentence:
        text = " ".join(args.sentence)
    else:
        _build_parser().print_help()
        return 2

    if not text.strip():
        print("[]")
        return 0

    result = analyse(text, backend=args.backend, **kwargs)

    indent = args.indent or None
    payload = json.dumps(result, indent=indent, ensure_ascii=False)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(payload + "\n")
    else:
        print(payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
