# -*- coding: utf-8 -*-
"""下载并解压 JDK 17 + Maven 到 WorkBuddy 隔离目录（不污染系统环境）。
只在需要编译验证后端时跑一次。
可用源（2026-09 实测）：
  JDK  华为云 openjdk  | Azul cdn
  Maven Apache dlcdn
清华/阿里镜像对大文件返回 403/404，不可用。"""
import urllib.request, os, zipfile, time, shutil

BASE = r'C:\Users\PC\.workbuddy\binaries'
DL = os.path.join(BASE, '_dl')
os.makedirs(DL, exist_ok=True)

targets = [
    ('jdk17.zip',
     'https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip',
     os.path.join(BASE, 'java')),
    ('maven.zip',
     'https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip',
     os.path.join(BASE, 'maven')),
]

for name, url, dest in targets:
    path = os.path.join(DL, name)
    if not os.path.exists(path) or os.path.getsize(path) < 100000:
        t0 = time.time()
        print('[dl] %s ...' % name, flush=True)
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
        with urllib.request.urlopen(req, timeout=300) as r, open(path, 'wb') as f:
            while True:
                chunk = r.read(262144)
                if not chunk:
                    break
                f.write(chunk)
        print('[dl]   %.1f MB / %.0fs' % (os.path.getsize(path) / 1048576, time.time() - t0), flush=True)
    else:
        print('[dl] cached %s' % name, flush=True)

    print('[unzip] %s -> %s' % (name, dest), flush=True)
    if os.path.exists(dest):
        shutil.rmtree(dest, ignore_errors=True)
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(path) as z:
        z.extractall(dest)
    print('[unzip]   ok', flush=True)

print('=== RESULT ===', flush=True)
for d, exe in [(os.path.join(BASE, 'java'), 'javac.exe'),
               (os.path.join(BASE, 'maven'), 'mvn.cmd')]:
    hit = []
    for root, dirs, files in os.walk(d):
        if exe in files:
            hit.append(os.path.join(root, exe))
    print('%s -> %s' % (exe, hit if hit else 'NOT FOUND'), flush=True)

print('ALL DONE', flush=True)
