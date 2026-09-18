# -*- coding: utf-8 -*-
"""
给手机试用的临时 HTTPS 入口（零安装）。

为什么需要它
------------
浏览器的定位只在**安全上下文**下可用：
    http://localhost        ✅ 特例，所以电脑上测总是好的
    http://192.168.x.x:8080 ❌ 明文 http 一律拒绝 —— 手机上就是不动
    https://...             ✅ 唯一能在真机上用定位的方式

所以「手机上打开看着正常、就是定位不动」不是 bug，是缺一个 https 地址。
本脚本用 `ssh -R` 反向隧道（localhost.run，免费、无需注册、无需安装）把
本机 8080 暴露成一个临时 https 域名。

用法
----
    1. 先起服务：  python dev.py            （后端 8080 会自己发前端）
    2. 另开一个终端：python tools/tunnel.py
    3. 终端里会打印 https://xxx.lhr.life   → 手机浏览器打开它
    4. 手机地址栏旁边「添加到主屏幕」→ 装成 App，再授权定位

⚠️ 注意事项
  · 这个地址是**临时**的，脚本一停就失效；重启会换一个新域名。
  · 流量经过第三方（localhost.run），**只适合给朋友试用**。
    正式的 HTTPS 部署请看 `docs/06-客户端交付形态.md` §3.2。
  · 首次连接会问 yes/no，本脚本已用 StrictHostKeyChecking=accept-new 跳过。
  · ⚠️ localhost.run **要求带一个 SSH 公钥**（用来认领隧道），
    没有就只给你看欢迎语、不给域名。本脚本会自动生成一个专用密钥，
    不动你已有的 `~/.ssh/id_*`。
"""
import os
import re
import subprocess
import sys
import threading
import time

PORT = sys.argv[1] if len(sys.argv) > 1 else "8080"

# localhost.run 的公开入口，走 ssh -R 反向隧道，不需要任何账号
KEY_PATH = os.path.expanduser("~/.ssh/eatwhat_tunnel_ed25519")


def ensure_key():
    """
    确保有一个可用的 SSH 私钥。

    ⚠️ 必须单独生成、**不能用用户已有的 `~/.ssh/id_rsa`**：
    一来可能不存在（实测新机器上往往只有 known_hosts），
    二来不该拿用户的通用身份密钥去连第三方服务 —— 那是越权使用。
    """
    if os.path.exists(KEY_PATH):
        return KEY_PATH
    os.makedirs(os.path.dirname(KEY_PATH), exist_ok=True)
    print("  首次运行：生成一个专用 SSH 密钥（仅用于本隧道）...")
    r = subprocess.run(
        ["ssh-keygen", "-t", "ed25519", "-N", "", "-C", "eatwhat-tunnel",
         "-f", KEY_PATH],
        capture_output=True, text=True)
    if r.returncode != 0:
        print("  [!] 密钥生成失败：" + (r.stderr or "").strip()[:200])
        return None
    print("  已生成：" + KEY_PATH)
    return KEY_PATH


URL_RE = re.compile(r"https://[a-z0-9-]+\.lhr\.life")


def main():
    print("=" * 60)
    print("  临时 HTTPS 隧道（把本机 %s 暴露成公网 https）" % PORT)
    print("=" * 60)
    print()

    key = ensure_key()
    if not key:
        return 1

    ssh_cmd = [
        "ssh", "-i", key,
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "ServerAliveInterval=30",
        "-R", "80:127.0.0.1:%s" % PORT,
        "nokey@localhost.run",
    ]

    print("  正在建立隧道...（第一次可能要 10 秒左右）")
    print()

    proc = subprocess.Popen(ssh_cmd, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True,
                            encoding="utf-8", errors="replace", bufsize=1)

    found = {"url": None}

    def reader():
        for line in proc.stdout:
            line = line.rstrip()
            m = URL_RE.search(line)
            if m and not found["url"]:
                found["url"] = m.group(0)
                print("  ✅ 隧道已就绪！")
                print()
                print("  手机访问：" + m.group(0) + "/client/feed.html")
                print()
                print("  接下来在手机上：")
                print("    1. 用浏览器打开上面这个地址")
                print("    2. 允许定位授权 → 「附近」就会按你的真实位置算距离")
                print("    3. 菜单里「添加到主屏幕」→ 以后像 App 一样打开")
                print()
                print("  ⚠️ 这个域名是临时的，Ctrl+C 后即失效。")
                print("  ⚠️ 流量经过第三方，只适合给朋友试用，别放敏感数据。")
                print()
                print("-" * 60)
            elif line and ("Permission denied" in line or "Connection refused" in line
                           or "Could not resolve" in line):
                print("  [!] " + line)

    t = threading.Thread(target=reader, daemon=True)
    t.start()

    try:
        while proc.poll() is None:
            time.sleep(0.5)
        time.sleep(0.5)          # 让 reader 把最后几行吐完
        if not found["url"]:
            print()
            print("  [x] 隧道没能建立。请检查：")
            print("      · 网络是否可用（本脚本要连 localhost.run）")
            print("      · %s 端口是否有服务在跑（先执行 python dev.py）" % PORT)
            print("      · 若提示 Permission denied，删掉 %s 重试" % KEY_PATH)
            return 1
    except KeyboardInterrupt:
        print("\n  正在关闭隧道...")
        proc.terminate()
    finally:
        try:
            proc.terminate()
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
