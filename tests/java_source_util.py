#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""静态门禁共用的 Java 源码处理：剥离注释，行号保持不变。

为什么必须剥注释：门禁断言的是**真实代码结构**，而注释完全可以写出
`// 改造前是裸 svc.tap(n.cx, n.cy)` 这种字样——不剥离就会误判（既可能
假红，也会让"把守卫写进注释"骗过门禁的真假绿）。
块注释替换为等量换行，行注释替换为一个换行，保证行号与原文一致。
"""
import sys


def strip_java_comments(text):
    """去掉 // 行注释与 /* */ 块注释；字符串/字符字面量原样保留。"""
    out = []
    i = 0
    n = len(text)
    in_str = False
    in_char = False
    while i < n:
        c = text[i]
        if in_str or in_char:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if in_str and c == '"':
                in_str = False
            elif in_char and c == "'":
                in_char = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "'":
            in_char = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            if j < 0:
                break
            out.append("\n")
            i = j + 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            if j < 0:
                break
            out.append("\n" * text.count("\n", i, j))
            i = j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read_java(path):
    """读取 Java 源文件并剥离注释（行号不变）。"""
    return strip_java_comments(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    for p in sys.argv[1:]:
        sys.stdout.write(read_java(__import__("pathlib").Path(p)))
