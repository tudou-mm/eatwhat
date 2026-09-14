import os, re, io

ROOT = r"D:\WorkBuddy\2026-09-14-10-26-15"
pattern = re.compile(r'(<script src="\.\./assets/data/mock\.js"></script>)')
inject = '\n<script src="../assets/js/api.js"></script>'

changed = []
for sub in ("client", "merchant", "admin"):
    d = os.path.join(ROOT, sub)
    if not os.path.isdir(d):
        continue
    for fn in os.listdir(d):
        if not fn.endswith(".html"):
            continue
        p = os.path.join(d, fn)
        with io.open(p, "r", encoding="utf-8") as f:
            s = f.read()
        if "../assets/js/api.js" in s:
            continue
        if not pattern.search(s):
            continue
        s2 = pattern.sub(lambda m: m.group(1) + inject, s, count=1)
        with io.open(p, "w", encoding="utf-8", newline="") as f:
            f.write(s2)
        changed.append(sub + "/" + fn)

print("已注入适配层的页面（%d 个）：" % len(changed))
for c in changed:
    print("  " + c)
