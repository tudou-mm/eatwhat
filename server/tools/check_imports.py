"""检查 Java 源码中被使用但未导入的类型名。
粗略但实用：扫描常见的本项目实体/仓库类型，若在正文出现却无 import，则报警。
"""
import os, io, re

ROOT = r"D:\WorkBuddy\2026-09-14-10-26-15\server\src\main\java"
GENERIC_OK = {
    "String","Integer","Long","Double","Boolean","Object","List","Map","Set","ArrayList",
    "HashMap","LinkedHashMap","HashSet","Arrays","Collections","Objects","Optional",
    "LocalDate","LocalDateTime","Instant","ZoneId","DateTimeFormatter","Random",
    "Logger","LoggerFactory","Value","Component","Service","Configuration","Repository",
    "RestController","RequestMapping","GetMapping","PostMapping","PutMapping","DeleteMapping",
    "RequestBody","PathVariable","RequestParam","Autowired","Entity","Table","Id","Column",
    "Transactional","SpringBootApplication","CommandLineRunner","RestControllerAdvice",
    "ExceptionHandler","Override","Get","Post","Transactional","Stream","Collectors",
    "TypeReference","ObjectMapper","JsonNode","SuppressWarnings","JpaRepository"
}

# 收集本项目的类型
own_types = {}
for dp, dn, fn in os.walk(ROOT):
    for f in fn:
        if f.endswith(".java"):
            own_types[f[:-5]] = os.path.join(dp, f)

problems = []
for name, path in sorted(own_types.items()):
    src = io.open(path, encoding="utf-8").read()
    rel = os.path.relpath(path, ROOT)
    pkg = re.search(r'^package\s+([\w.]+);', src, re.M)
    pkg = pkg.group(1) if pkg else ""

    body = re.sub(r'^package[^\n]*', '', src, flags=re.M)
    # 注意 [\w.*] 要包含 * ，否则 com.a.b.* 会被静默截断成 com.a.b
    imports = set(re.findall(r'^import\s+(?:static\s+)?([\w.*]+);', body, re.M))
    imported_simple = {i.rsplit('.', 1)[-1] for i in imports if i.rsplit('.', 1)[-1] != '*'}
    # 通配符导入的包，视为全包可用
    wildcard_pkgs = {i[:-2] for i in imports if i.endswith('.*')}

    def pkg_of(path):
        m = re.search(r'^package\s+([\w.]+);', io.open(path, encoding='utf-8').read(), re.M)
        return m.group(1) if m else ""

    same_pkg = {t for t, tp in own_types.items() if pkg_of(tp) == pkg}
    # 被通配符覆盖的类型
    covered = {t for t, tp in own_types.items() if pkg_of(tp) in wildcard_pkgs}

    body_no_import = re.sub(r'^import[^\n]*', '', body, flags=re.M)
    body_nc = re.sub(r'//[^\n]*', '', body_no_import)
    body_nc = re.sub(r'/\*.*?\*/', '', body_nc, flags=re.S)
    body_nc = re.sub(r'"(?:\\.|[^"\\])*"', '""', body_nc)

    for t in own_types:
        if t == name:
            continue
        if t in imported_simple or t in same_pkg or t in covered:
            continue
        # 出现在类型位置才算（避免方法名巧合）
        if re.search(r'(?<![\w.])' + re.escape(t) + r'(?![\w])\s*[\.\s<\[)]', body_nc) \
           or re.search(r'\bnew\s+' + re.escape(t) + r'\s*\(', body_nc):
            problems.append("%s: 引用了 %s 但未 import" % (rel, t))

print("检查 %d 个文件" % len(own_types))
if problems:
    print("\n发现 %d 个疑似缺失 import：" % len(problems))
    for x in problems:
        print("  ✗ " + x)
else:
    print("✓ 未发现缺失 import")
