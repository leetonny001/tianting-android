#!/usr/bin/env python3
"""系统通知的 release Logcat 检查（PRD-live F-PUSH-08 规则 3、PRD-ui AC-UI-90-12）：驱动 logcheck 构建（和 release 一样的 R8、不可调试），
真实收一条带特征串的通知，看到横幅后在设备日志里搜特征串和 token。顺带证明 R8 之后推送还能工作。

用法见 scripts/push-logcat-check.sh（它负责启动测试服务端、编译、安装、卸载）。只用标准库，用 python3 -I 运行。
"""
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ADB = os.environ["ADB"].split()
PKG = "com.tianting.app.logcheck"
ADMIN_PORT = int(os.environ["TT_ADMIN_PORT"])
ADMIN_KEY = os.environ["TT_ADMIN_KEY"]
CALLER_ID = os.environ["TT_CALLER_ID"]
CALLER_SECRET = os.environ["TT_CALLER_SECRET"]


def adb(*args, check=True):
    return subprocess.run(ADB + list(args), check=check, capture_output=True).stdout


def admin(path, body):
    req = urllib.request.Request(
        f"http://127.0.0.1:{ADMIN_PORT}{path}",
        data=json.dumps(body).encode(),
        headers={"X-Admin-Key": ADMIN_KEY, "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"{}")


def signed(path, body):
    raw = json.dumps(body)
    ts = str(int(time.time() * 1000))
    nonce = base64.urlsafe_b64encode(secrets.token_bytes(16)).rstrip(b"=").decode()
    digest = hashlib.sha256(raw.encode()).hexdigest()
    to_sign = "\n".join(["POST", path, ts, nonce, digest, "e2e", "", ""])
    sig = base64.urlsafe_b64encode(hmac.new(CALLER_SECRET.encode(), to_sign.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    req = urllib.request.Request(
        f"http://127.0.0.1:{ADMIN_PORT}{path}",
        data=raw.encode(),
        headers={
            "Content-Type": "application/json",
            "X-IM-Caller": CALLER_ID,
            "X-IM-Timestamp": ts,
            "X-IM-Nonce": nonce,
            "X-IM-Operator": "e2e",
            "X-IM-Signature": sig,
        },
        method="POST",
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"{}")


def dump():
    for _ in range(5):
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        i, j = raw.find(b"<?xml"), raw.rfind(b"</hierarchy>")
        if i >= 0 and j > 0:
            return ET.fromstring(raw[i : j + len(b"</hierarchy>")])
        time.sleep(0.5)
    raise RuntimeError("uiautomator dump failed")


def find(tag=None, text=None):
    for n in dump().iter("node"):
        if n.get("package") != PKG:
            continue
        if tag and n.get("resource-id", "").split("/")[-1] == tag:
            return n
        if text and text in n.get("text", ""):
            return n
    return None


def wait(tag=None, text=None, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        n = find(tag, text)
        if n is not None:
            return n
        time.sleep(0.4)
    raise RuntimeError(f"等不到 {tag or text}")


def focused_is_ours():
    out = adb("shell", "dumpsys", "window").decode("utf-8", "replace")
    m = re.search(r"mCurrentFocus=.*", out)
    return bool(m and PKG in m.group(0))


def tap(node):
    """发 input 之前确认焦点窗口是自己的 App。"""
    if not focused_is_ours():
        raise RuntimeError("焦点窗口不是 logcheck，不发 input")
    m = re.findall(r"\d+", node.get("bounds"))
    x, y = (int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2
    adb("shell", "input", "tap", str(x), str(y))


def main():
    run = secrets.token_hex(4)
    me = f"pushlog{run}"
    secret = f"ZXQ-PUSH-LOG-{run}"
    adb("logcat", "-c")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/com.tianting.app.MainActivity")
    tap(wait("login-username"))
    time.sleep(0.4)
    if not focused_is_ours():
        raise RuntimeError("焦点窗口不是 logcheck，不发 input")
    adb("shell", "input", "text", me)
    time.sleep(0.3)
    tap(wait("login-submit"))
    wait("tab-chats", timeout=20)
    time.sleep(1)
    signed("/admin/push", {"target": "user", "userIds": [me], "type": "notice", "data": {"text": secret}, "pushId": f"log-{run}"})
    wait(tag="notice-banner", timeout=5)
    tap(wait("notice-banner"))
    wait(text=secret)
    jwt_head = admin("/admin/users/token", {"userId": me})["token"].split(".")[0]
    time.sleep(1)
    log = adb("logcat", "-d", "-b", "all", "-v", "threadtime").decode("utf-8", "replace")
    # adbd 会记录 `adb shell input text` 这条命令本身（账号名），不含特征串，这里的命中都算失败。
    hits = [ln for ln in log.splitlines() if secret in ln or (jwt_head + ".") in ln]
    for ln in hits:
        print("命中", " ".join(ln.split()[2:6]))
    if hits:
        print("失败：Logcat 里有通知内容或 token")
        return 1
    print(f"通过：release（R8）收到通知并显示，全设备 {len(log.splitlines())} 行日志搜不到特征串和 token")
    return 0


if __name__ == "__main__":
    sys.exit(main())
