# -*- coding: utf-8 -*-
"""后端接口冒烟测试。
用法：先启动服务，再跑本脚本。
    python smoke_test.py [port]
覆盖：健康检查 / 三端读接口 / 核心写接口 / 关键业务规则验证。
"""
import urllib.request, urllib.error, json, time, sys

PORT = sys.argv[1] if len(sys.argv) > 1 else '8080'
BASE = 'http://127.0.0.1:%s' % PORT

PASS, FAIL = [], []


def wait_ready(timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            r = urllib.request.urlopen(BASE + '/api/health', timeout=3)
            if r.status == 200:
                return True
        except Exception:
            time.sleep(1.5)
    return False


def call(method, path, body=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(
        BASE + path, data=data, method=method,
        headers={'Content-Type': 'application/json; charset=utf-8'})
    try:
        r = urllib.request.urlopen(req, timeout=20)
        raw = r.read().decode('utf-8', 'replace')
        try:
            return r.status, json.loads(raw)
        except Exception:
            return r.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')[:200]
    except Exception as e:
        return -1, str(e)[:160]


def brief(data):
    if isinstance(data, list):
        return 'list[%d]' % len(data)
    if isinstance(data, dict):
        ks = list(data.keys())
        return 'dict{%s}' % (','.join(ks) if len(ks) <= 7 else ','.join(ks[:7]) + ',…')
    if data is None:
        return 'null'
    return repr(data)[:70]


def check(label, method, path, body=None, expect_code=0, want=None):
    st, j = call(method, path, body)
    if isinstance(j, dict) and 'code' in j:
        code, msg, data = j.get('code'), j.get('msg'), j.get('data')
    else:
        code, msg, data = st, str(j)[:120], None

    ok = (st == 200 and code == expect_code)
    note = ''
    if ok and want is not None:
        try:
            got = want(data)
            ok = bool(got[0])
            note = got[1]
        except Exception as e:
            ok, note = False, '断言异常: %s' % e

    line = '%-46s http=%-3s code=%-4s %s' % (method + ' ' + path, st, code, brief(data))
    if note:
        line += '  | ' + note
    if ok:
        PASS.append(label)
        print('  [OK]   ' + line, flush=True)
    else:
        FAIL.append(label)
        print('  [FAIL] ' + line, flush=True)
        if msg and code not in (0, None):
            print('         msg: %s' % str(msg)[:180], flush=True)
    return data


print('等待服务就绪 %s ...' % BASE, flush=True)
if not wait_ready():
    print('服务在 120s 内未就绪，放弃。', flush=True)
    sys.exit(2)
print('服务已就绪。开始测试。\n', flush=True)

print('--- 1. 通用 ---')
check('health', 'GET', '/api/health')
check('client config', 'GET', '/api/client/config',
      want=lambda d: (isinstance(d, dict) and d.get('priceTiers'), '价格档 %d 个' % len(d.get('priceTiers') or [])))

print('\n--- 2. 客户端 ---')
check('feed nearby', 'GET', '/api/client/feed?tab=nearby',
      want=lambda d: (len(d) > 0, '%d 条' % len(d)))
check('feed random', 'GET', '/api/client/feed?tab=random',
      want=lambda d: (len(d) > 0, '%d 条' % len(d)))
check('shop detail', 'GET', '/api/client/shop/s_001')
check('shop dishes', 'GET', '/api/client/shop/s_001/dishes')
check('dish detail', 'GET', '/api/client/dish/d_001')
check('dish comments', 'GET', '/api/client/dish/d_001/comments')
check('filter (按价格档)', 'POST', '/api/client/filter', {'tiers': ['tier_mid'], 'tastes': []})
check('login', 'POST', '/api/login', {'phone': '13800138000', 'code': '123456'},
      want=lambda d: (bool(d.get('token')), 'token=%s' % str(d.get('token'))[:20]))
check('login 手机号非法应被拒', 'POST', '/api/login', {'phone': '123', 'code': '123456'},
      expect_code=400)

print('\n--- 3. 商家端 ---')
check('merchant dashboard', 'GET', '/api/merchant/dashboard/s_001')
check('merchant dishes', 'GET', '/api/merchant/dishes/s_001')
check('merchant shop info', 'GET', '/api/merchant/shop/s_001')
check('merchant comments', 'GET', '/api/merchant/comments/s_001')

print('\n--- 4. 平台端 ---')
check('admin overview', 'GET', '/api/admin/overview')
check('admin shops', 'GET', '/api/admin/shops')
check('admin ranking', 'GET', '/api/admin/ranking')
check('admin contents', 'GET', '/api/admin/contents')
check('admin users', 'GET', '/api/admin/users')
check('admin config', 'GET', '/api/admin/config')

print('\n=== 汇总 ===')
print('通过 %d 项，失败 %d 项' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：')
    for f in FAIL:
        print('  - ' + f)
sys.exit(1 if FAIL else 0)
