"""Java 源码粗检 v2：括号/花括号配平 + 常见笔误扫描。

关键修正：必须「先剥离字符串和字符字面量，再去注释」。
因为像 "https://picsum.photos/..." 这种 URL 字面量里含 //，
若先去注释会被误当成行注释截断，造成大量误报。
"""
import os, io, re

ROOT = r"D:\WorkBuddy\2026-09-14-10-26-15\server\src\main\java"

# 按顺序剥离：块注释 → 行注释 → 字符串 → 字符字面量
# 但注释符号也可能出现在字符串里，所以正确做法是「扫描式」剥离。
def strip_java(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        # 行注释
        if c == '/' and i + 1 < n and src[i+1] == '/':
            while i < n and src[i] != '\n':
                i += 1
            continue
        # 块注释
        if c == '/' and i + 1 < n and src[i+1] == '*':
            i += 2
            while i + 1 < n and not (src[i] == '*' and src[i+1] == '/'):
                i += 1
            i += 2
            continue
        # 字符串（含 text block """）
        if c == '"':
            if src[i:i+3] == '"""':
                i += 3
                while i + 2 < n and src[i:i+3] != '"""':
                    i += 1
                i += 3
            else:
                i += 1
                while i < n:
                    if src[i] == '\\':
                        i += 2
                        continue
                    if src[i] == '"':
                        i += 1
                        break
                    i += 1
            out.append('""')
            continue
        # 字符字面量
        if c == "'":
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == "'":
                    i += 1
                    break
                i += 1
            out.append("''")
            continue
        out.append(c)
        i += 1
    return ''.join(out)


problems = []
files = []
for dp, dn, fn in os.walk(ROOT):
    for f in fn:
        if f.endswith(".java"):
            files.append(os.path.join(dp, f))

for p in sorted(files):
    src = io.open(p, encoding="utf-8").read()
    rel = os.path.relpath(p, ROOT)
    s = strip_java(src)

    for op, cl, name in [("{", "}", "花括号"), ("(", ")", "圆括号")]:
        if s.count(op) != s.count(cl):
            problems.append("%s: %s不配平 (%d 开 vs %d 闭)" % (rel, name, s.count(op), s.count(cl)))

    # 包名与路径一致性
    m = re.search(r'^package\s+([\w.]+);', src, re.M)
    if m:
        expect = m.group(1).replace('.', os.sep)
        if os.path.basename(os.path.dirname(p)) != m.group(1).rsplit('.', 1)[-1]:
            problems.append("%s: 包名 %s 与所在目录不一致" % (rel, m.group(1)))

    # 类名与文件名一致性
    if not re.search(r'\b(class|interface|enum|record)\s+' + re.escape(os.path.basename(p)[:-5]) + r'\b', src):
        problems.append("%s: 未找到与文件名同名的类/接口声明" % rel)

print("共扫描 %d 个 Java 文件" % len(files))
if problems:
    print("\n发现问题 %d 个：" % len(problems))
    for x in problems:
        print("  ✗ " + x)
else:
    print("✓ 全部通过：括号配平、包名与目录一致、类名与文件名一致")
