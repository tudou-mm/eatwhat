#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校验 HTML 页面内联 <script> 的语法。

为什么需要：
前端是纯 HTML，业务脚本都内联在页面底部。改完页面若括号多了少了，
浏览器只会静默**不执行整个脚本**（控制台一条红字），页面看起来"能打开"
但所有交互都失灵 —— 比直接报错更难排查。

用法：
    python server/tools/check_inline_js.py                 # 检查全部三端页面
    python server/tools/check_inline_js.py client/feed.html  # 只查指定文件

原理：抽出最后一个 <script> 块（页面业务脚本）交给 node --check。
"""
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
NODE = r"C:\Users\PC\.workbuddy\binaries\node\versions\22.22.2-3\node.exe"

DIRS = ["client", "merchant", "admin"]


def pages():
    """收集要检查的页面：命令行给了就只查这些，否则扫三端全部。"""
    if len(sys.argv) > 1:
        return [a if os.path.isabs(a) else os.path.join(ROOT, a) for a in sys.argv[1:]]
    out = []
    for d in DIRS:
        full = os.path.join(ROOT, d)
        if not os.path.isdir(full):
            continue
        for f in sorted(os.listdir(full)):
            if f.endswith(".html"):
                out.append(os.path.join(full, f))
    return out


def inline_scripts(html):
    """取出不带 src 的 <script> 块（有 src 的是外部文件，另有校验）"""
    blocks = re.findall(r"<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)</script>", html)
    return [b for b in blocks if b.strip()]


def check(path):
    """返回 (ok, 消息)"""
    try:
        with open(path, "r", encoding="utf-8") as f:
            html = f.read()
    except Exception as e:
        return False, "读取失败：%s" % e

    blocks = inline_scripts(html)
    if not blocks:
        return True, "无内联脚本，跳过"

    # 逐个块查：出错时能定位到是第几个 script
    for i, code in enumerate(blocks, 1):
        fd, tmp = tempfile.mkstemp(suffix=".js")
        os.close(fd)
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                f.write(code)
            p = subprocess.run([NODE, "--check", tmp],
                               capture_output=True, text=True)
            if p.returncode != 0:
                err = (p.stderr or "").strip().split("\n")
                # 只留关键两行，避免刷屏
                brief = " | ".join(err[:3])
                return False, "第 %d 个 script 语法错误：%s" % (i, brief)
        finally:
            try:
                os.remove(tmp)
            except OSError:
                pass
    return True, "%d 个脚本块全部通过" % len(blocks)


def main():
    files = pages()
    if not files:
        print("没有找到页面")
        return 1

    bad = []
    for p in files:
        rel = os.path.relpath(p, ROOT).replace("\\", "/")
        ok, msg = check(p)
        if ok:
            print("  [OK]   %-28s %s" % (rel, msg))
        else:
            print("  [FAIL] %-28s %s" % (rel, msg))
            bad.append(rel)

    print()
    print("共 %d 个页面，%d 个失败" % (len(files), len(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
