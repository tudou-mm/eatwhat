# -*- coding: utf-8 -*-
"""后端接口冒烟测试。
用法：先启动服务，再跑本脚本。
    python smoke_test.py [port]
覆盖：健康检查 / 三端读接口 / 核心写接口 / 关键业务规则验证。
"""
import urllib.request, urllib.error, json, time, sys, re

PORT = sys.argv[1] if len(sys.argv) > 1 else '8080'
BASE = 'http://127.0.0.1:%s' % PORT

PASS, FAIL = [], []

# 鉴权后需要 token：平台端和商家端各一张（角色不能混用）
ADMIN_TOKEN = None
MERCHANT_TOKEN = None
DEMO_ADMIN = ('admin', 'admin123')
DEMO_MERCHANT = ('13800138000', '123456')


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


def token_for(path):
    if path.startswith('/api/admin'):
        return ADMIN_TOKEN
    if path.startswith('/api/merchant'):
        return MERCHANT_TOKEN
    return None


def call(method, path, body=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    headers = {'Content-Type': 'application/json; charset=utf-8'}
    tok = token_for(path)
    if tok:
        headers['Authorization'] = 'Bearer ' + tok
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
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


def login(role, account, password):
    """登录拿 token。失败直接退出 —— 后面所有断言都会连带失败，没必要跑。"""
    st, j = call('POST', '/api/auth/login',
                 {'role': role, 'account': account, 'password': password})
    if st != 200 or not isinstance(j, dict) or j.get('code') != 0:
        print('登录失败（%s/%s）：http=%s %s' % (role, account, st, j), flush=True)
        sys.exit(3)
    return j['data']['token']


def send_code(phone):
    """
    取短信验证码（客户端登录现在要真码）。
    dev 环境后端会把验证码回显在 devCode 里；被 60 秒重发限制挡住时等一等，
    这样脚本连着跑第二遍不用手动处理。
    """
    st, j = call('POST', '/api/auth/sms-code', {'phone': phone})
    if st == 429:
        m = re.search(r'(\d+)\s*秒', (j or {}).get('msg', '') if isinstance(j, dict) else '')
        if m and int(m.group(1)) <= 70:
            time.sleep(int(m.group(1)) + 1)
            st, j = call('POST', '/api/auth/sms-code', {'phone': phone})
    code = (j.get('data') or {}).get('devCode') if isinstance(j, dict) else None
    return st, code


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

    # 成功要求 HTTP 200；失败断言两种都认 —— v1.3 起错误响应的
    # HTTP 状态码 = 业务 code（401/403/400 都是真状态码），
    # 老写法「HTTP 200 里塞 code」也仍然兼容。
    if expect_code == 0:
        ok = (st == 200 and code == 0)
    else:
        ok = (code == expect_code and st in (200, expect_code))
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


def check_unauth(label, path, bad_token=None):
    """**不带** token（或带一个假 token）打受保护接口，必须被 401 拦下。

    不能复用 check()：那个会按路径自动补 token，就测不出鉴权本身。
    """
    headers = {'Content-Type': 'application/json; charset=utf-8'}
    if bad_token:
        headers['Authorization'] = 'Bearer ' + bad_token
    req = urllib.request.Request(BASE + path, method='GET', headers=headers)
    try:
        r = urllib.request.urlopen(req, timeout=10)
        st, body = r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        st, body = e.code, e.read().decode('utf-8', 'replace')[:200]
    except Exception as e:
        st, body = -1, str(e)[:160]

    ok = (st == 401)
    line = '%-46s http=%-3s %s' % ('GET ' + path, st, body[:70])
    if ok:
        PASS.append(label)
        print('  [OK]   ' + line, flush=True)
    else:
        FAIL.append(label)
        print('  [FAIL] ' + line, flush=True)


print('等待服务就绪 %s ...' % BASE, flush=True)
if not wait_ready():
    print('服务在 120s 内未就绪，放弃。', flush=True)
    sys.exit(2)
print('服务已就绪。开始测试。\n', flush=True)

print('--- 0. 鉴权 ---')
ADMIN_TOKEN = login('admin', *DEMO_ADMIN)
MERCHANT_TOKEN = login('merchant', *DEMO_MERCHANT)
print('  已拿到 admin / merchant 两张 token', flush=True)
check_unauth('无 token 打平台端应 401', '/api/admin/overview')
check_unauth('无 token 打商家端应 401', '/api/merchant/dashboard/s_001')
check_unauth('伪造 token 应 401', '/api/admin/overview',
             bad_token='eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJoYWNrZXIifQ.forged')

print('\n--- 1. 通用 ---')
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
# 客户端登录现在要真验证码：先发码，dev 环境后端会把码回显在 devCode 里
_st, _code = send_code('13900000001')
print('  sms-code 发码 → http=%s devCode=%s' % (_st, _code), flush=True)

check('login（手机号 + 真实验证码）', 'POST', '/api/login',
      {'phone': '13900000001', 'code': _code},
      want=lambda d: (bool(d.get('token')), 'token=%s' % str(d.get('token'))[:20]))
check('login 手机号非法应被拒', 'POST', '/api/login', {'phone': '123', 'code': '123456'},
      expect_code=400)
check('login 没发码就登应被拒', 'POST', '/api/login',
      {'phone': '13900000009', 'code': '123456'}, expect_code=400)

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
