#!/usr/bin/env python3
"""扫描 .vue 文件里「调用了 this.fnXxx() 但本文件没有定义」的方法。

这类漏写不会在编译期报错，只在用户点到那个按钮时才炸
（例如 2026-09-30 的 fnSubmitAsUser is not a function）。
"""
import os
import re
import sys

# 全局 mixin（common/mixins/index.js）与 loadMore mixin 提供的方法
MIXIN_PROVIDED = {
    'fnLoadMore', 'fnLoadMoreDone', 'fnLoadMoreNoMore', 'fnLoadMoreFail',
    'fnResetSetAniWaitIndex', 'fnSetPageTitle', 'fnToTopPage',
}
SKIP_DIRS = ('unpackage', 'node_modules', 'tm-vuetify', '.git', 'uni_modules')


def scan(path):
    src = open(path, encoding='utf-8', errors='ignore').read()
    calls = set(re.findall(r'this\.(fn[A-Za-z0-9_]+)\s*\(', src))
    defs = set(re.findall(r'^\s*(?:async\s+)?(fn[A-Za-z0-9_]+)\s*\(', src, re.M))
    defs |= set(re.findall(r'^\s*(fn[A-Za-z0-9_]+)\s*:', src, re.M))
    return sorted(calls - defs - MIXIN_PROVIDED)


def main():
    root_dir = sys.argv[1] if len(sys.argv) > 1 else '.'
    problems = []
    for root, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if not f.endswith('.vue'):
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
