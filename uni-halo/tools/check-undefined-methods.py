#!/usr/bin/env python3
"""扫描「调用了但没定义」的方法/函数。两类：

1. `this.fnXxx()` 但本文件没有定义
   （2026-09-30 的 fnSubmitAsUser is not a function）。
2. 裸函数调用 `foo(...)` 但既没 import、也没在本文件定义
   （2026-10-09 的 refreshAppConfigs is not defined：home.vue 下拉刷新调用了
   utils/configRefresh.js 的方法却漏了 import，编译期不报错，下拉才炸）。

这类漏写不会在编译期报错，只在用户触发那条路径时才炸。
用法：python tools/check-undefined-methods.py [目录] [--vue-only]
"""
import os
import re
import sys

# 全局 mixin（common/mixins/index.js）与 loadMore mixin 提供的方法
MIXIN_PROVIDED = {
    'fnLoadMore', 'fnLoadMoreDone', 'fnLoadMoreNoMore', 'fnLoadMoreFail',
    'fnResetSetAniWaitIndex', 'fnSetPageTitle', 'fnToTopPage',
}
# 第三方 vendored 代码（mp-html/luch-request 等）不参与扫描
SKIP_DIRS = ('unpackage', 'node_modules', 'tm-vuetify', '.git', 'uni_modules',
             'js_sdk', 'mp-html')

# 语言关键字：`if (` / `for (` / `async (`（箭头函数的 async）这类长得像函数调用的
KEYWORDS = {
    'if', 'for', 'while', 'switch', 'catch', 'return', 'typeof', 'function', 'new',
    'delete', 'void', 'in', 'of', 'do', 'else', 'try', 'finally', 'throw', 'case',
    'default', 'break', 'continue', 'instanceof', 'yield', 'await', 'with', 'super',
    'async',
}

# 运行环境注入的全局，本来就不需要 import
GLOBAL_ALLOW = {
    # JS 内置
    'Object', 'Array', 'String', 'Number', 'Boolean', 'Promise', 'Date', 'RegExp',
    'Error', 'TypeError', 'RangeError', 'Map', 'Set', 'WeakMap', 'WeakSet', 'Symbol',
    'BigInt', 'Proxy', 'Reflect', 'Function', 'Math', 'JSON', 'Intl',
    'parseInt', 'parseFloat', 'isNaN', 'isFinite', 'encodeURIComponent',
    'decodeURIComponent', 'encodeURI', 'decodeURI', 'setTimeout', 'setInterval',
    'clearTimeout', 'clearInterval', 'requestAnimationFrame', 'queueMicrotask',
    'structuredClone', 'atob', 'btoa', 'fetch', 'alert', 'confirm', 'prompt',
    'CustomEvent', 'Event', 'EventTarget', 'AbortController', 'AbortSignal',
    'URL', 'URLSearchParams', 'Blob', 'FileReader', 'FormData', 'TextEncoder',
    'TextDecoder', 'XMLHttpRequest', 'Worker', 'Image', 'Element', 'HTMLElement',
    'Node', 'MutationObserver', 'performance',
    # 小程序 / uni-app
    'uni', 'wx', 'qq', 'tt', 'swan', 'my', 'getApp', 'getCurrentPages', 'plus',
    'require', 'define', 'exports', 'module', 'process', 'console', 'globalThis',
    'App', 'Page', 'Component', 'Behavior', 'Vue',
}

CALL_RE = re.compile(r'(?<![\w.$\\])([A-Za-z_$][\w$]*)\s*\(')
FUNC_DECL_RE = re.compile(r'function\s*\*?\s*$')
# 正则字面量出现的位置：前面是运算符/括号（`= /re/`）而不是值（`a / b`）
REGEX_PRECEDERS = set('=(,:[!&|?{};+-*%~^<>')


def script_chunks(src):
    """取出 <script> 块（含 setup）；没有 script 块的（.js）整体当脚本。"""
    blocks = re.findall(r'<script[^>]*>(.*?)</script>', src, re.S)
    return blocks if blocks else [src]


def _blank(chars, a, b):
    for k in range(a, min(b, len(chars))):
        if chars[k] != '\n':
            chars[k] = ' '


def _match_close(code, open_idx):
    """open_idx 指向 '('，返回配对的 ')' 下标；找不到返回 -1。"""
    depth = 0
    for i in range(open_idx, len(code)):
        if code[i] == '(':
            depth += 1
        elif code[i] == ')':
            depth -= 1
            if depth == 0:
                return i
    return -1


def _regex_allowed(code, i):
    """`/` 落在 i 处时判断它是正则字面量还是除号：看前一个有效记号。"""
    j = i - 1
    while j >= 0 and code[j] in ' \t\n':
        j -= 1
    if j < 0:
        return True
    if code[j] in REGEX_PRECEDERS:
        return True
    m = re.search(r'([A-Za-z_$][\w$]*)\s*$', code[:j + 1])
    return bool(m) and m.group(1) in KEYWORDS


def mask_noncode(code):
    """把注释与字符串字面量抹成空白，保留代码骨架。

    <p>必须按状态机走：早先用正则 `/*...*/` 非贪婪匹配，被代码里的 `/*`
    （字符串里的路径、正则等）带偏，会连后面的真实定义一起吞掉，反而制造误报。
    模板字符串里的 `${}` 表达式要保留 —— 那里可能有真实调用。</p>
    """
    chars = list(code)
    i, n = 0, len(code)
    while i < n:
        c = code[i]
        if c == '/' and i + 1 < n and code[i + 1] == '/':
            j = code.find('\n', i)
            j = n if j < 0 else j
            _blank(chars, i, j)
            i = j
        elif c == '/' and i + 1 < n and code[i + 1] == '*':
            j = code.find('*/', i + 2)
            j = n if j < 0 else j + 2
            _blank(chars, i, j)
            i = j
        elif c in '"\'':
            j = i + 1
            while j < n:
                if code[j] == '\\':
                    j += 2
                    continue
                if code[j] == c:
                    j += 1
                    break
                if code[j] == '\n':
                    break
                j += 1
            _blank(chars, i, j)
            i = j
        elif c == '/' and _regex_allowed(code, i):
            # 正则字面量：`/\B(?=(\d{3}))/` 里的 `B(` 不是函数调用
            j = i + 1
            in_class = False
            while j < n:
                ch = code[j]
                if ch == '\\':
                    j += 2
                    continue
                if ch == '\n':
                    break
                if ch == '[':
                    in_class = True
                elif ch == ']':
                    in_class = False
                elif ch == '/' and not in_class:
                    j += 1
                    while j < n and code[j].isalpha():
                        j += 1
                    _blank(chars, i, j)
                    break
                j += 1
            i = j if j > i else i + 1
        elif c == '`':
            lit_start = i
            j = i + 1
            closed = False
            while j < n:
                ch = code[j]
                if ch == '\\':
                    j += 2
                    continue
                if ch == '$' and j + 1 < n and code[j + 1] == '{':
                    _blank(chars, lit_start, j)
                    # 花括号配对：从 ${ 的下一个字符开始数
                    depth, k = 0, j + 1
                    while k < n:
                        if code[k] == '{':
                            depth += 1
                        elif code[k] == '}':
                            depth -= 1
                            if depth == 0:
                                break
                        k += 1
                    if k < n:
                        inner = mask_noncode(code[j + 2:k])
                        chars[j + 2:k] = list(inner)
                    lit_start = k
                    j = k + 1
                    continue
                if ch == '`':
                    _blank(chars, lit_start, j + 1)
                    closed = True
                    j += 1
                    break
                j += 1
            if not closed:
                _blank(chars, lit_start, n)
            i = j
        else:
            i += 1
    return ''.join(chars)


def imported_names(code):
    """import 语句里绑定的名字（默认导入 / 具名 / 命名空间）。"""
    names = set()
    for stmt in re.findall(r'import\s+(.*?)\s+from\s', code, re.S):
        stmt = stmt.replace('\n', ' ')
        for m in re.findall(r'([A-Za-z_$][\w$]*)', stmt):
            names.add(m)
    return names


def bare_call_issues(code):
    """裸调用里既没 import、也没在本文件别处出现过的名字。"""
    imports = imported_names(code)
    calls = {}
    for m in CALL_RE.finditer(code):
        name = m.group(1)
        if name in KEYWORDS or name in imports or name in GLOBAL_ALLOW:
            continue
        if FUNC_DECL_RE.search(code[:m.start()]):
            continue  # function foo(
        close = _match_close(code, m.end() - 1)
        if close != -1 and re.match(r'\s*\{', code[close + 1:]):
            continue  # foo(a, b) { —— 对象/类里的方法简写，是定义不是调用
        calls.setdefault(name, []).append((m.start(1), m.end(1)))

    issues = []
    for name, spans in calls.items():
        chars = list(code)
        for s, e in spans:
            chars[s:e] = ' ' * (e - s)
        remainder = ''.join(chars)
        # 抹掉调用点后这个名字还有别的出场（赋值/参数/对象键）→ 本文件认识它
        if re.search(r'(?<![\w.$])' + re.escape(name) + r'\b', remainder):
            continue
        issues.append(name)
    return sorted(set(issues))


def scan(path):
    src = open(path, encoding='utf-8', errors='ignore').read()
    miss = set()
    for chunk in script_chunks(src):
        code = mask_noncode(chunk)
        calls = set(re.findall(r'this\.(fn[A-Za-z0-9_]+)\s*\(', code))
        defs = set(re.findall(r'^\s*(?:async\s+)?(fn[A-Za-z0-9_]+)\s*\(', code, re.M))
        defs |= set(re.findall(r'^\s*(fn[A-Za-z0-9_]+)\s*:', code, re.M))
        miss |= (calls - defs - MIXIN_PROVIDED)
        miss |= {'裸调用 ' + n for n in bare_call_issues(code)}
    return sorted(miss)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    root_dir = args[0] if args else '.'
    exts = ('.vue',) if '--vue-only' in sys.argv else ('.vue', '.js')
    problems = []
    for root, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if not f.endswith(exts):
                continue
            p = os.path.join(root, f)
            miss = scan(p)
            if miss:
                problems.append((p.replace(os.sep, '/'), miss))
    print(f'发现 {len(problems)} 个文件存在「调用了但未定义」的方法:')
    for p, miss in problems:
        print(f'  {p}')
        for m in miss:
            print(f'      {m}')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
