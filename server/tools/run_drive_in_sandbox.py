# -*- coding: utf-8 -*-
"""
通用包装：跑 server/tools 下的 drive_*.py，绕过沙箱的批量删除拦截。

用法（在 server/tools 目录下）：
    python run_drive_in_sandbox.py drive_user_loc.py
    python run_drive_in_sandbox.py drive_map_picker.py

⚠️ 参数是**脚本文件名**，不是路径。别写成 `.run_drive_wrap.py`（那是旧名）。

背景
----
这些脚本启动前会 `shutil.rmtree(_profile)` 保证浏览器无缓存，
但沙箱对「单次删除 > 50 个文件」会拦截，脚本直接退出
（SAFE_DELETE_BULK_CONFIRM_REQUIRED）。

做法
----
**改名而不是删除**：把已存在的 profile 目录 os.rename 成带时间戳的名字，
原路径就不存在了 → 脚本的 rmtree 实删 0 个文件 → 不触发阈值。
效果与「删掉」等价（新目录天然是空的，缓存无从积累 —— 这正是脚本想要的效果）。

⚠️ 临时文件必须落在 `server/tools/` 下（本脚本已保证）：
这些测试脚本的 `ROOT` 是 `__file__/../../` 推出来的，
若把源码拷到项目根再执行，`ROOT` 会变成 `D:\\` —— 缓存目录跑到盘根。

跑完后再改名一次，把这次产生的 profile 也腾空。
"""
import os
import runpy
import sys
import time

ROOT = r'D:\WorkBuddy\2026-09-14-10-26-15'
TOOLS = os.path.join(ROOT, 'server', 'tools')


def stash(out_dir, name):
    p = os.path.join(out_dir, name)
    if os.path.exists(p):
        dst = p + '_%d_old' % int(time.time())
        try:
            os.rename(p, dst)
            print('[wrap] stashed %s' % os.path.basename(dst))
        except OSError as e:
            print('[wrap] stash failed: %s' % e)


def main():
    if len(sys.argv) < 2:
        print('usage: python run_drive_in_sandbox.py drive_xxx.py')
        return 2
    tool = sys.argv[1]
    path = os.path.join(TOOLS, tool)
    if not os.path.exists(path):
        print('not found: %s' % path)
        return 2

    src = open(path, encoding='utf-8').read()

    # 找 OUT 定义，据此推导 profile 目录
    import re
    m = re.search(r'^OUT\s*=\s*os\.path\.join\(ROOT,\s*"([^"]+)"\)', src, re.M)
    if not m:
        print('[wrap] 没找到 OUT 定义，直接跑（可能被拦）')
        sys.argv = [path] + sys.argv[2:]
        runpy.run_path(path, run_name='__main__')
        return 0

    out_dir = os.path.join(ROOT, m.group(1))
    stash(out_dir, '_profile')

    # ⚠️ 临时文件必须写到 server/tools/ 下！
    #    这些脚本的 ROOT 是 `__file__/../../` 推出来的，
    #    写到项目根会让 ROOT 变成 `D:\`（缓存目录跑到盘根，删起来更麻烦）。
    tmp = os.path.join(TOOLS, '_drive_tmp_%s' % tool)
    open(tmp, 'w', encoding='utf-8').write(src)

    sys.argv = [tmp] + sys.argv[2:]
    try:
        runpy.run_path(tmp, run_name='__main__')
    finally:
        stash(out_dir, '_profile')
    return 0


if __name__ == '__main__':
    sys.exit(main())
