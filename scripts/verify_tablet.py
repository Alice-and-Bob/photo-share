#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tablet bring-up & verification over USB.
Device serial is overridable via --device / $ANDROID_SERIAL / $DEVICE.
Waits for USB-debugging authorization, then:
 1) collect device specs (model / Android ver / screen / density)
 2) install app-debug.apk
 3) grant MANAGE_EXTERNAL_STORAGE + POST_NOTIFICATIONS via appops
 4) launch MainActivity and tap "启动服务器"
 5) verify /api/status via ADB-forwarded HTTP port
 6) verify FTP upload over direct Wi-Fi to the IP reported by the app
 7) pull a screenshot for the user
Logs everything to verify_tablet.log.

Note on FTP: the server uses PASV mode and advertises its real Wi-Fi IP +
a dynamic high port. A single ADB forward of port 2121 is enough for the
control channel, but the data channel must connect to that advertised IP.
Therefore this script performs the FTP upload directly over Wi-Fi using the
IP returned by /api/status.
"""
import subprocess, time, re, os, sys, argparse, xml.etree.ElementTree as ET
import json

# ---- paths derived from this script's location (portable across machines) ----
_HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(_HERE)                                   # 项目根目录（scripts/ 的父目录）

# ---- 可覆盖参数：命令行 flag 优先，其次环境变量，最后保留本机默认值 ----
def _parse_args():
    p = argparse.ArgumentParser(description="Photo Share - 平板 USB 接入与验证脚本")
    p.add_argument("--device", "-d",
                   default=os.environ.get("ANDROID_SERIAL") or os.environ.get("DEVICE") or "***REMOVED***",
                   help="目标设备序列号（默认 ***REMOVED***，或 $ANDROID_SERIAL / $DEVICE）")
    p.add_argument("--adb",
                   default=os.environ.get("ADB_BIN", r"***REMOVED***/platform-tools/adb.exe"),
                   help="adb 可执行文件路径（默认 ***REMOVED***/.../adb.exe 或 $ADB_BIN）")
    p.add_argument("--apk",
                   default=os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
                   help="待安装 APK（默认 <根目录>/app/build/outputs/apk/debug/app-debug.apk）")
    return p.parse_args()

_ARGS = _parse_args()
ADB = _ARGS.adb
DEVICE = _ARGS.device
APK = _ARGS.apk
PKG = "com.example.sony_ftp"
# 日志 / 截图默认落在脚本所在目录，避免污染项目根目录
LOG = os.path.join(_HERE, "verify_tablet.log")
UI_XML = "/sdcard/ui_tablet.xml"


def log(m):
    line = f"{time.strftime('%H:%M:%S')} {m}"
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    print(line, flush=True)


def adb(args, timeout=60):
    cmd = [ADB, "-s", DEVICE] + args
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout, r.stderr
    except Exception as e:
        return -1, "", str(e)


def authorized():
    rc, out, err = adb(["get-state"])
    return out.strip() == "device"


def current_focus():
    rc, out, err = adb(["shell", "dumpsys", "window"])
    m = re.search(r"mCurrentFocus=Window\{[^ ]+ u0 ([^}/]+)/([^} ]+)", out)
    if m:
        return m.group(1), m.group(2)
    return None, None


def in_app():
    pkg, _ = current_focus()
    return pkg == PKG


def dump_ui():
    adb(["shell", "uiautomator", "dump", UI_XML])
    rc, out, err = adb(["shell", "cat", UI_XML])
    return out


def find_bounds(xmltext, txt):
    try:
        root = ET.fromstring(xmltext)
    except Exception as e:
        log(f"  ui parse err: {e}")
        return None
    for node in root.iter("node"):
        t = node.get("text", "") or ""
        if t == txt or txt in t:
            b = node.get("bounds", "")
            m = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
            if m:
                return tuple(map(int, m.groups()))
    return None


def tap_text(txt, retries=3):
    for attempt in range(retries):
        out = dump_ui()
        bounds = find_bounds(out, txt)
        if bounds:
            x1, y1, x2, y2 = bounds
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            adb(["shell", "input", "tap", str(cx), str(cy)])
            log(f"tapped '{txt}' @ ({cx},{cy})")
            return True
        log(f"WARN: text '{txt}' not found in UI dump (attempt {attempt + 1}/{retries})")
        time.sleep(2)
    return False


def dismiss_system_dialog():
    """If a system permission/dialog window is overlaying the app, try to dismiss it."""
    pkg, activity = current_focus()
    if pkg and pkg not in (PKG, "com.miui.home", None):
        log(f"  detected overlay {pkg}/{activity}, sending BACK")
        adb(["shell", "input", "keyevent", "KEYCODE_BACK"])
        time.sleep(1)
        return True
    return False


# ---------- 0. wait for USB debugging authorization (up to 30 min) ----------
log("=== verify_tablet start (waiting for authorization) ===")
ok = False
for i in range(180):
    if authorized():
        ok = True
        log(f"device authorized after ~{i * 10}s")
        break
    if i % 6 == 0:
        log(f"still unauthorized, waiting... ({i * 10}s)")
    time.sleep(10)
if not ok:
    log("TIMEOUT: device never authorized. Stopping.")
    sys.exit(1)

# ---------- 1. specs ----------
log("=== device specs ===")
for p in ["ro.product.model", "ro.product.device", "ro.build.version.release",
          "ro.build.version.sdk", "ro.product.brand", "ro.product.manufacturer"]:
    rc, out, err = adb(["shell", "getprop", p])
    log(f"  {p} = {out.strip()}")
for c in (["wm", "size"], ["wm", "density"], ["settings", "get", "system", "user_rotation"]):
    rc, out, err = adb(["shell"] + c)
    log(f"  {' '.join(c)} => {out.strip()} {err.strip()}")

# ---------- 2. install ----------
log("=== install ===")
rc, out, err = adb(["install", "-r", "-t", APK])
log(f"install rc={rc}\n  out={out.strip()}\n  err={err.strip()}")

# ---------- 3. grants + launch ----------
log("=== grants + launch ===")
adb(["shell", "appops", "set", PKG, "MANAGE_EXTERNAL_STORAGE", "allow"])
adb(["shell", "appops", "set", PKG, "POST_NOTIFICATIONS", "allow"])
rc, out, err = adb(["shell", "am", "start", "-n", f"{PKG}/.MainActivity"])
log(f"am start rc={rc} {out.strip()} {err.strip()}")

# wait for app to come to foreground
for i in range(10):
    if in_app():
        log("app in foreground")
        break
    dismiss_system_dialog()
    time.sleep(1)
else:
    log("WARNING: app did not come to foreground; continuing anyway")

time.sleep(2)

# ---------- 4. start server ----------
log("=== start server (tap 启动服务器) ===")
if not tap_text("启动服务器", retries=5):
    log("ERROR: could not find 启动服务器 button")
    sys.exit(1)

# wait for running state
running = False
for i in range(10):
    out = dump_ui()
    if "运行中" in out:
        running = True
        log("UI reports 运行中")
        break
    time.sleep(1)
if not running:
    log("WARNING: UI did not report 运行中 within 10s")

# ---------- 5. forward HTTP port and verify ----------
log("=== port forward + HTTP/FTP checks ===")
adb(["forward", "tcp:8080", "tcp:8080"])
time.sleep(1)

import urllib.request
status_json = None
for port in (8080, 80, 18080):
    try:
        data = urllib.request.urlopen(f"http://localhost:{port}/api/status", timeout=10).read().decode()
        log(f"  /api/status (port {port}) => {data}")
        status_json = data
        break
    except Exception as e:
        log(f"  /api/status (port {port}) err: {e}")

# ---------- 6. FTP upload over direct Wi-Fi ----------
ip = None
if status_json:
    try:
        ip = json.loads(status_json).get("ip")
    except Exception as e:
        log(f"  parse status err: {e}")

if ip:
    try:
        from ftplib import FTP
        ftp = FTP()
        ftp.connect(ip, 2121, timeout=10)
        # 服务器已启用匿名登录；也可使用默认账号 1111 / 1111
        ftp.login()
        payload = (b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
                   b"\xff\xd9" + b"PhotoShare tablet test")
        ftp.storbinary("STOR tablet_test.jpg", __import__("io").BytesIO(payload))
        ftp.quit()
        log(f"  FTP upload OK to {ip}:2121 (STOR tablet_test.jpg)")
    except Exception as e:
        log(f"  FTP upload err: {e}")
else:
    log("  skipping FTP upload (no IP from /api/status)")

# re-check count after upload
if status_json is not None:
    try:
        data2 = urllib.request.urlopen("http://localhost:8080/api/status", timeout=10).read().decode()
        log(f"  /api/status after upload => {data2}")
    except Exception as e:
        log(f"  re-check status err: {e}")

# ---------- 7. screenshot ----------
log("=== screenshot ===")
adb(["shell", "screencap", "-p", "/sdcard/shot_tablet.png"])
rc, out, err = adb(["pull", "/sdcard/shot_tablet.png", os.path.join(_HERE, "shot_tablet.png")])
log(f"pull rc={rc} {out.strip()} {err.strip()}")

log("=== verify_tablet done ===")
