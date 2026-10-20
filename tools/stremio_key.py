"""Signs in to Stremio once and saves your session key as stremio_key in config.properties (for "Continue watching").
Your password goes only to api.strem.io and is never saved or printed. Usage: python3 tools/stremio_key.py"""
import getpass
import json
import os
import re
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config.properties")

email = input("Stremio email: ").strip()
password = getpass.getpass("Stremio password (not shown): ")
body = json.dumps({"type": "Login", "email": email, "password": password, "facebook": False}).encode()
req = urllib.request.Request("https://api.strem.io/api/login", body, {"Content-Type": "application/json"})
with urllib.request.urlopen(req, timeout=20) as r:
    res = json.load(r)
if "error" in res:
    raise SystemExit("Stremio said: " + res["error"].get("message", "error"))
key = res["result"]["authKey"]

text = open(CONFIG, encoding="utf-8").read() if os.path.exists(CONFIG) else ""
line = "stremio_key=" + key
if re.search(r"^stremio_key=.*$", text, re.M):
    text = re.sub(r"^stremio_key=.*$", line, text, flags=re.M)
else:
    text = text.rstrip("\n") + "\n\n# Stremio session key for Continue watching (tools/stremio_key.py)\n" + line + "\n"
with open(CONFIG, "w", encoding="utf-8") as f:
    f.write(text)
print("Saved. Now rebuild and install the app.")
