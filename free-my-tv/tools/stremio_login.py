#!/usr/bin/env python3
"""Signs in to your Stremio account and writes its session key into config.properties (stremio_auth_key), for the
home screen's Continue Watching row (Stremio's library: the episode and where you stopped).

Run it in your own terminal: it asks for your Stremio e-mail and password there and sends them only to Stremio
(api.strem.io). Neither is kept; only the key Stremio answers with goes into config.properties. Build again after."""
import getpass
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config.properties")


def main():
    if not os.path.exists(CONFIG):
        sys.exit("config.properties is missing: run ./build.sh once, then this again.")
    email = input("Stremio e-mail: ").strip()
    password = getpass.getpass("Stremio password (not shown): ")
    if not email or not password:
        sys.exit("Nothing sent.")
    body = json.dumps({"email": email, "password": password}).encode()
    req = urllib.request.Request("https://api.strem.io/api/login", data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            reply = json.load(r)
    except Exception as e:  # network, HTTP
        sys.exit(f"Stremio didn't answer: {e}")
    if "error" in reply:
        sys.exit("Stremio refused: " + reply["error"].get("message", "unknown error"))
    key = reply.get("result", {}).get("authKey", "")
    if not key:
        sys.exit("Stremio answered without a session key.")
    with open(CONFIG, encoding="utf-8") as f:
        text = f.read()
    line = f"stremio_auth_key={key}"
    if re.search(r"^\s*stremio_auth_key\s*=.*$", text, flags=re.M):
        text = re.sub(r"^\s*stremio_auth_key\s*=.*$", line, text, flags=re.M)
    else:
        text = text.rstrip("\n") + "\n\n# Stremio session key for Continue Watching (tools/stremio_login.py). Keep it private.\n" + line + "\n"
    with open(CONFIG, "w", encoding="utf-8") as f:
        f.write(text)
    print("Saved the Stremio key in config.properties. Build and install again: ./build.sh && adb install -r FreeMyTV.apk")


if __name__ == "__main__":
    main()
