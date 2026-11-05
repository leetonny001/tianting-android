#!/usr/bin/env python3
"""release Logcat 检查（PRD-ui 3.6 节 AC-UI-90-12）：驱动 logcheck 构建（和 release 一样的 R8、不可调试）收发特征串，
然后在设备日志里搜特征串和 token。顺带证明 R8 之后 SDK（序列化、WebSocket、本地库）还能正常工作。

用法见 scripts/release-logcat-check.sh（它负责启动测试服务端、编译、安装、卸载）。只用标准库，用 python3 -I 运行。
"""
import base64
import json
import os
import re
import secrets
import socket
import struct
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ADB = os.environ["ADB"].split()
PKG = os.environ.get("TT_PKG", "com.tianting.app.logcheck")
PORT = int(os.environ["TT_PORT"])
ADMIN_PORT = int(os.environ["TT_ADMIN_PORT"])
ADMIN_KEY = os.environ["TT_ADMIN_KEY"]


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


class Ws:
    """最小的 WebSocket 客户端：只够对方账号发一帧文本。"""

    def __init__(self, port):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=10)
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        self.s.sendall(
            f"GET /ws?platform=web HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode()
        )
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.s.recv(4096)
        if b" 101 " not in buf.split(b"\r\n")[0]:
            raise RuntimeError(buf[:200])

    def send(self, obj):
        data = json.dumps(obj).encode()
        mask = secrets.token_bytes(4)
        n = len(data)
        head = b"\x81" + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n))
        self.s.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def recv_text(self):
        h = self._read(2)
        n = h[1] & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._read(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._read(8))[0]
        body = self._read(n)
        return body.decode() if (h[0] & 0x0F) == 1 else None

    def _read(self, n):
        out = b""
        while len(out) < n:
            c = self.s.recv(n - len(out))
            if not c:
                raise RuntimeError("socket closed")
            out += c
        return out

    def request(self, cmd, data):
        rid = secrets.token_hex(4)
        self.send({"id": rid, "cmd": cmd, "data": data})
        end = time.time() + 10
        while time.time() < end:
            t = self.recv_text()
            if t is None:
                continue
            f = json.loads(t)
            if f.get("id") == rid:
                if f.get("error"):
                    raise RuntimeError(f"{cmd}: {f['error'].get('code')}")
                return f.get("data")
        raise RuntimeError(f"{cmd}: timeout")


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
    adb("exec-out", "screencap", "-p")
    raise RuntimeError(f"等不到 {tag or text}")


def tap(node):
    m = re.findall(r"\d+", node.get("bounds"))
    x, y = (int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2
    adb("shell", "input", "tap", str(x), str(y))


def type_into(tag, text):
    tap(wait(tag))
    time.sleep(0.4)
    adb("shell", "input", "text", text)
    time.sleep(0.3)


def main():
    run = secrets.token_hex(4)
    msg_out, msg_in, apply_msg = f"ZXQ-UI-LOG-1-{run}", f"ZXQ-UI-LOG-3-{run}", f"ZXQ-UI-LOG-2-{run}"
    me, peer = f"logme{run}", f"logpeer{run}"
    admin("/admin/users/import", {"users": [{"userId": peer}]})
    peer_token = admin("/admin/users/token", {"userId": peer})["token"]

    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/com.tianting.app.MainActivity")
    type_into("login-username", me)
    tap(wait("login-submit"))
    wait("tab-chats", timeout=20)
    # 真实 token 的特征：JWT 头部。服务端给每个账号签的 token 都以同样的头开始。
    jwt_head = admin("/admin/users/token", {"userId": me})["token"].split(".")[0]

    conv = f"c2c:{min(me, peer)}:{max(me, peer)}"
    link = "tianting://chat/" + urllib.parse.quote(conv, safe="")
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", f"'{link}'", PKG)
    type_into("composer-input", msg_out)
    tap(wait("send-button"))
    wait(text=msg_out)
    time.sleep(1)
    if find("failed-icon") is not None:
        raise RuntimeError("release 构建发送失败（R8 问题？）")

    ws = Ws(PORT)
    ws.request("auth", {"token": peer_token})
    ws.request("send", {"convType": "c2c", "to": me, "clientMsgId": f"py-{run}", "type": "text", "body": {"text": msg_in}})
    wait(text=msg_in)  # 收到了
    # 发一条好友申请（附言是特征串）
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", f"tianting://contacts/apply/{peer}", PKG)
    tap(wait("apply-message"))
    time.sleep(0.4)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(40):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    adb("shell", "input", "text", apply_msg)
    time.sleep(0.3)
    tap(wait("apply-send"))
    time.sleep(2)
    try:
        ws.request("socialSync", {})
    except RuntimeError:
        pass

    pid = adb("shell", "pidof", PKG).decode().strip()
    log = adb("logcat", "-d", "-b", "all", "-v", "threadtime").decode("utf-8", "replace")
    needles = (msg_out, msg_in, apply_msg, peer_token, jwt_head + ".")
    hits = [ln for ln in log.splitlines() if any(s in ln for s in needles)]
    def pid_of(ln):
        f = ln.split()
        return f[2] if len(f) > 2 else ""
    mine = [ln for ln in hits if len(ln.split()) > 5 and ln.split()[5] != "adbd"]
    # adbd 会记录 `adb shell input text` 这条命令本身（本脚本造成的），不算失败；其他任何进程命中都算。
    for ln in hits:
        print("命中", "本 App" if pid_of(ln) == pid else "其他进程", ln.split(":", 3)[-1][:40] if pid_of(ln) == pid else " ".join(ln.split()[2:6]))
    if mine:
        print("失败：Logcat 里有敏感内容")
        return 1
    print(f"通过：全设备 {len(log.splitlines())} 行日志，除 adbd 记录的 adb 命令本身（{len(hits)} 行）外搜不到特征串和 token")
    return 0


if __name__ == "__main__":
    sys.exit(main())
