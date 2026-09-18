# -*- coding: utf-8 -*-
"""
本地开发一键启动：后端(8080) + 前端静态服务(5173)

用法：
    python dev.py            启动两个服务
    python dev.py --api-only 只启动后端
    python dev.py --web-only 只启动前端

⚠️ v1.9 之后**后端自己也会发前端页面**了，所以 5173 不再是必需品：
      后端发的前端   http://127.0.0.1:8080/client/feed.html
      5173 单独起的  http://127.0.0.1:5173/client/feed.html

  · 用 8080：一个地址搞定，**同源没有跨域**，最接近真机部署
  · 用 5173：改前端文件后刷新即可，不依赖后端；但要跨域（白名单已配好）

两者数据完全一样，随你习惯。**要给手机试的话用 8080**（手机记一个地址就够）。

JDK / Maven 查找顺序：
    环境变量 JAVA_HOME  →  WorkBuddy 隔离目录  →  PATH
所以换电脑后只要装了 JDK 17，本脚本照样能跑。
"""
import os
import sys
import socket
import platform
import shutil
import subprocess

ROOT = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(ROOT, 'server')
IS_WIN = platform.system() == 'Windows'

BACKEND_PORT = 8080
WEB_PORT = 5173


def _first_existing(*paths):
    for p in paths:
        if p and os.path.exists(p):
            return p
    return None


def find_java_home():
    """返回 JDK 根目录（其下应有 bin/java）"""
    exe = 'java.exe' if IS_WIN else 'java'

    jh = os.environ.get('JAVA_HOME')
    if jh and os.path.exists(os.path.join(jh, 'bin', exe)):
        return jh

    # WorkBuddy 装 JDK 的隔离目录
    base = os.path.expanduser(r'~\.workbuddy\binaries\java') if IS_WIN \
        else os.path.expanduser('~/.workbuddy/binaries/java')
    if os.path.isdir(base):
        for d in sorted(os.listdir(base), reverse=True):
            p = os.path.join(base, d)
            if os.path.exists(os.path.join(p, 'bin', exe)):
                return p

    j = shutil.which('java')
    if j:
        return os.path.dirname(os.path.dirname(j))
    return None


def find_maven():
    j = shutil.which('mvn')
    if j:
        return j

    base = os.path.expanduser(r'~\.workbuddy\binaries\maven') if IS_WIN \
        else os.path.expanduser('~/.workbuddy/binaries/maven')
    if os.path.isdir(base):
        for d in sorted(os.listdir(base), reverse=True):
            p = os.path.join(base, d, 'bin', 'mvn.cmd' if IS_WIN else 'mvn')
            if os.path.exists(p):
                return p
    return None


def find_settings():
    p = os.path.expanduser(r'~\.workbuddy\binaries\maven-settings.xml') if IS_WIN \
        else os.path.expanduser('~/.workbuddy/binaries/maven-settings.xml')
    return p if os.path.exists(p) else None


def port_busy(port):
    s = socket.socket()
    s.settimeout(1)
    try:
        return s.connect_ex(('127.0.0.1', port)) == 0
    finally:
        s.close()


def start_backend():
    if port_busy(BACKEND_PORT):
        print('  后端已在 %d 运行，跳过。' % BACKEND_PORT)
        return None

    java_home = find_java_home()
    mvn = find_maven()
    if not java_home:
        print('  [x] 找不到 JDK 17。装好后设置 JAVA_HOME 再试。')
        return None
    if not mvn:
        print('  [x] 找不到 Maven。装好后确保 mvn 在 PATH 里，或用 IDEA 打开 server/ 直接跑。')
        return None

    cmd = [mvn, '-B', '-ntp']
    settings = find_settings()
    if settings:
        cmd += ['-s', settings]
    cmd += ['spring-boot:run', '-Dspring-boot.run.arguments=--server.port=%d' % BACKEND_PORT]

    env = os.environ.copy()
    env['JAVA_HOME'] = java_home
    # 某些环境会注入 SERVER__PORT 覆盖 yml 配置，这里显式清掉
    for k in list(env):
        if k.upper().replace('_', '') == 'SERVERPORT':
            env.pop(k, None)

    print('  启动后端 → http://127.0.0.1:%d' % BACKEND_PORT)
    print('  JDK: %s' % java_home)
    return subprocess.Popen(cmd, cwd=SERVER, env=env)


def start_web():
    if port_busy(WEB_PORT):
        print('  前端静态服务已在 %d 运行，跳过。' % WEB_PORT)
        return None
    print('  启动前端 → http://127.0.0.1:%d' % WEB_PORT)
    return subprocess.Popen(
        [sys.executable, '-m', 'http.server', str(WEB_PORT), '--bind', '127.0.0.1'],
        cwd=ROOT)


def main():
    args = sys.argv[1:]
    procs = []

    print('== 吃什么 · 本地开发 ==')
    if '--web-only' not in args:
        p = start_backend()
        if p:
            procs.append(p)
    if '--api-only' not in args:
        p = start_web()
        if p:
            procs.append(p)

    base = 'http://127.0.0.1:%d' % WEB_PORT
    api = 'http://127.0.0.1:%d' % BACKEND_PORT
    print()
    print('  ── 入口 A：后端直出（推荐，同源无跨域，手机也用这个）──')
    print('    客户端  %s/client/feed.html?api=1' % api)
    print('    商家端  %s/merchant/login.html?api=1' % api)
    print('    平台端  %s/admin/login.html?api=1' % api)
    print('    （根路径 %s/ 会自动转到客户端首页）' % api)
    print()
    print('  ── 入口 B：5173 静态服务（改前端刷新即可，但要跨域）──')
    print('    总览    %s/index.html' % base)
    print('    客户端  %s/client/feed.html?api=1' % base)
    print('    商家端  %s/merchant/login.html?api=1' % base)
    print('    平台端  %s/admin/login.html?api=1' % base)
    print()
    print('  ── 手机预览（需要 HTTPS，见 docs/06-客户端交付形态.md）──')
    print('    cloudflared tunnel --url http://127.0.0.1:%d' % BACKEND_PORT)
    print('    拿到临时 https 地址后用手机打开，即可安装到主屏并授权定位')
    print()
    print('  Ctrl+C 结束（只会结束本脚本启动的进程）')

    if not procs:
        print('  没有需要启动的进程。')
        return
    try:
        for p in procs:
            p.wait()
    except KeyboardInterrupt:
        print('\n正在停止...')
        for p in procs:
            try:
                p.terminate()
            except Exception:
                pass


if __name__ == '__main__':
    main()
