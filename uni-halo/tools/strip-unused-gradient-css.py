# -*- coding: utf-8 -*-
"""
主包体积优化：删掉 tm-vuetify 主题矩阵里的 .border-bg-gradient-* 死规则。

为什么可以删：
  这批类名（渐变描边）在**全仓零引用**——pages / pagesA / components / tm-vuetify 组件里
  都搜不到字面量，也没有 `'border-bg-gradient-' + x` 这类动态拼接
  （tm 组件内部拼的是 `border-<color>-a-1` 和 `bg-gradient-<color>-<variant>`，不是这个前缀）。
  每个调色板约 11KB × 9 个 = 约 103KB，全部进了 common/main.wxss（小程序主包）。

删完复检：
  python tools/strip-unused-gradient-css.py --check
  重新出包后 node tools/check-miniapp-package.cjs build

用法：
  python tools/strip-unused-gradient-css.py            # 干跑，只统计
  python tools/strip-unused-gradient-css.py --write    # 真正落盘
"""

import io
import os
import re
import sys

UNI = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # 本脚本在 uni-halo/tools/ 下

TARGETS = [
    os.path.join(UNI, 'tm-vuetify', 'mian.min.css'),
    os.path.join(UNI, 'common', 'styles', 'skins.generated.scss'),
]
for name in ('amber', 'blue', 'blue-grey', 'green', 'indigo', 'orange', 'red', 'teal',
             'primary', 'brown', 'cyan', 'deep-orange', 'deep-purple', 'light-blue',
             'light-green', 'lime', 'pink', 'purple', 'yellow'):
    TARGETS.append(os.path.join(UNI, 'tm-vuetify', 'scss', 'theme', name + '.css'))

NEEDLE = 'border-bg-gradient'


def rule_chunks(text):
    """把 CSS 按 `选择器{声明}` 切片（这些文件都是扁平规则，无嵌套）。"""
    return re.split(r'(?<=\})', text)


def strip(text):
    kept = []
    removed = 0
    removed_rules = 0
    for chunk in rule_chunks(text):
        m = re.match(r'\s*([^{]*)\{', chunk)
        if m and NEEDLE in m.group(1):
            removed += len(chunk)
            removed_rules += 1
            continue
        kept.append(chunk)
    return ''.join(kept), removed, removed_rules


def main():
    write = '--write' in sys.argv
    total_removed = 0
    for path in TARGETS:
        if not os.path.isfile(path):
            continue
        with io.open(path, encoding='utf-8') as fh:
            src = fh.read()
        if NEEDLE not in src:
            continue
        if '@media' in src:
            print('  ! %s 含 @media，请人工确认切片结果' % os.path.relpath(path, UNI))
        out, removed, rules = strip(src)
        if not rules:
            continue
        total_removed += removed
        print('%-58s -%6.1fKB (%d 条) %6.1fKB -> %6.1fKB'
              % (os.path.relpath(path, UNI), removed / 1024, rules,
                 len(src.encode('utf-8')) / 1024, len(out.encode('utf-8')) / 1024))
        if write:
            with io.open(path, 'w', encoding='utf-8', newline='') as fh:
                fh.write(out)
    print('\n合计可省 %.1fKB %s' % (total_removed / 1024, '(已写入)' if write else '(干跑)'))


if __name__ == '__main__':
    main()
