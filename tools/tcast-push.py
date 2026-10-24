#!/usr/bin/env python3
"""Install an APK on a TCL TV through TCL's own 多屏互动 (com.tcl.MultiScreenInteraction_TV).

Ported from Aerial (https://github.com/pengchujin/aerial-tv, tools/tcast-push.py, MIT License,
Copyright (c) 2026 pengchujin), which worked the protocol out. A local APK file is served from
this computer here instead of needing a separate web server.

TCL's firmware refuses every ADB install (INSTALL_FAILED_VERIFICATION_FAILURE), but 多屏互动 runs
as the system user and installs apps that TCL's phone app pushes to it. This speaks that phone
protocol: it asks the TV to download the APK and install it. The TV shows one prompt
(同意 / 拒绝); after 同意 it installs silently.

    python3 tools/tcast-push.py <tv-ip> <apk-file-or-url> [timeout-seconds]

With a file, it is served from this computer on port 8765 (the TV must be able to reach this
computer; allow the port through the firewall if asked). Use Lumen-guard.apk on TCL: TCL also
refuses any APK that declares itself a home screen.

The protocol, read from 多屏互动 5.30 on TCL MT5879:
- TCP port 6553; every message is a 4-byte big-endian length followed by UTF-8 text, fields split by ">>".
- "159>>1>>0" opens the session without encryption; "150>>" is the heartbeat.
- "223>>INSTALL>>package>>label>>url>>2>>versionCode>>sizeMB>>appMd5>>signatureMd5>>icon>>apkPathMd5":
  source 2 with an apkPathMd5 marks a phone-local APK, which skips the store lookup and asks the viewer.
  The TV answers "223>>INSTALL>>package>>label>>confirm_pending|denied|...>>apkPathMd5", then
  "223>>PROGRESS>>package>>label>>stage>>percent>>apkPathMd5": 1 downloading, 3 installing,
  5 installed, 2 failed.

Exits 0 once the TV reports the app installed, 1 if the viewer refused, the install failed, or
nothing happened in time.
"""
import functools
import hashlib
import http.server
import os
import socket
import struct
import sys
import threading
import time

PORT = 6553
SERVE_PORT = 8765
PACKAGE = "com.luumlabs.lumen"
LABEL = "Lumen"
VERSION = "100"  # only shown by the TV; the APK's own versionCode is what gets installed


def send(sock, text):
    data = text.encode()
    sock.sendall(struct.pack(">i", len(data)) + data)


def messages(buf):
    """Returns the complete messages already in buf, and what is left over."""
    out = []
    while len(buf) >= 4:
        n = struct.unpack(">i", buf[:4])[0]
        if len(buf) < 4 + n:
            break
        out.append(buf[4:4 + n].decode("utf-8", "replace"))
        buf = buf[4 + n:]
    return out, buf


def serve(path, tv):
    """Serves the APK's folder over HTTP and returns its URL as the TV will see it."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((tv, PORT))  # no packet is sent; this only picks the interface facing the TV
        me = s.getsockname()[0]
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=os.path.dirname(os.path.abspath(path)))
    httpd = http.server.ThreadingHTTPServer((me, SERVE_PORT), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return "http://%s:%d/%s" % (me, SERVE_PORT, os.path.basename(path))


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    tv, apk = sys.argv[1:3]
    timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 180
    url = apk if apk.startswith(("http://", "https://")) else serve(apk, tv)
    print("The TV will download", url, flush=True)

    sock = socket.create_connection((tv, PORT), timeout=5)
    sock.settimeout(1)
    send(sock, "159>>1>>0")
    path_md5 = hashlib.md5(url.encode()).hexdigest()
    send(sock, ">>".join(["223", "INSTALL", PACKAGE, LABEL, url, "2", VERSION, "20", "", "", "", path_md5]))

    buf = b""
    last_beat = time.time()
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
        except socket.timeout:
            pass
        found, buf = messages(buf)
        for msg in found:
            parts = msg.split(">>")
            if len(parts) < 5 or parts[0] != "223" or parts[2] != PACKAGE:
                continue
            if parts[1] == "INSTALL":
                if parts[4] == "confirm_pending":
                    print("The TV is asking: choose 同意 (Agree) on the TV.", flush=True)
                elif parts[4] == "denied":
                    print("Refused on the TV.", file=sys.stderr)
                    return 1
            elif parts[1] == "PROGRESS":
                if parts[4] == "5":
                    print("Installed.", flush=True)
                    return 0
                if parts[4] == "2":
                    print("The TV could not download or install the APK.", file=sys.stderr)
                    return 1
        # The TV drops a quiet session, and with it the pending install.
        if time.time() - last_beat > 5:
            send(sock, "150>>")
            last_beat = time.time()
    sock.close()
    print("Timed out waiting for the TV.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
