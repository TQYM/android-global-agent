#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""fuzzy_flow_guard.py — 模糊语义转译层门禁（免设备）。

    python3 tests/fuzzy_flow_guard.py

对应设计：docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md §11.1（FF-1 ~ FF-9）。

设计原则与 spec_ux_001_guard.py 一致：**只做能真伪判定的静态断言**。
「模型转译得准不准」无法静态判定——那由 tests/FuzzyTranslatorTest.java（桌面 JVM，
83 条断言）与真机回归负责；本脚本守的是**那些一旦被改掉就会静默出事的结构性事实**：

  · 纯逻辑层被掺进 android 依赖 → 桌面回归直接编不过，等于测试名存实亡；
  · 否定词保护被删 → 「别打开微信」会变成「打开微信」，而用户看到的是「已完成」；
  · 风险任务的确认点被删 → 可能直接替用户花钱；
  · 回退分支被删 → 转译模型一抽风，整个任务就卡死；
  · 一次往返变成两次 → 每次任务白多付一次调用与延迟（设计方案 C-1）。

退出码：0 = 无 FAIL（可有 MANUAL）；1 = 存在 FAIL。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite" / "src" / "com" / "dsh" / "agentlite"

RESULTS: list[tuple[str, str, str, str]] = []   # (id, 描述, 状态, 备注)


def read(path: pathlib.Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except Exception:
        return ""


def check(cid: str, desc: str, ok: bool, note: str = "") -> None:
    RESULTS.append((cid, desc, "PASS" if ok else "FAIL", note))


def manual(cid: str, desc: str, note: str = "") -> None:
    RESULTS.append((cid, desc, "MANUAL", note))


def body_of(java: str, signature: str) -> str:
    """取方法体：从签名后的第一个 `{` 起做**词法感知**的括号配对。

    必须跳过字符串、字符字面量与注释——朴素地数括号会被源码里的
    `'{'`、`"}"` 或注释中的引号骗到，配对错位后返回空串，表现为
    「找不到方法体」的假失败（实测踩过：给 interpret 加了一句
    `indexOf('{')` 判断，本门禁立刻误报）。
    """
    i = java.find(signature)
    if i < 0:
        return ""
    j = java.find("{", i)
    if j < 0:
        return ""
    depth = 0
    k = j
    n = len(java)
    state = None          # None | "str" | "chr" | "line" | "block"
    while k < n:
        c = java[k]
        nxt = java[k + 1] if k + 1 < n else ""
        if state is None:
            if c == "/" and nxt == "/":
                state = "line"; k += 2; continue
            if c == "/" and nxt == "*":
                state = "block"; k += 2; continue
            if c == '"':
                state = "str"
            elif c == "'":
                state = "chr"
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return java[j:k + 1]
        elif state == "line":
            if c == "\n":
                state = None
        elif state == "block":
            if c == "*" and nxt == "/":
                state = None; k += 2; continue
        elif state == "str":
            if c == "\\":
                k += 2; continue
            if c == '"':
                state = None
        elif state == "chr":
            if c == "\\":
                k += 2; continue
            if c == "'":
                state = None
        k += 1
    return ""


fuzzy = read(SRC / "FuzzyFlow.java")
normalizer = read(SRC / "TextNormalizer.java")
translator = read(SRC / "SemanticTranslator.java")
engine = read(SRC / "AgentEngine.java")

# 三个纯逻辑文件都必须存在，否则后面所有断言都没有意义
missing = [n for n, s in (("FuzzyFlow.java", fuzzy), ("TextNormalizer.java", normalizer),
                          ("SemanticTranslator.java", translator)) if not s]
if missing:
    check("FF-0", "转译层三个源文件存在", False, f"缺失：{missing}")

# ---------------------------------------------------------------- FF-4（先行）
# 纯逻辑层零 android / org.json 依赖 —— 这是「可桌面回归」的前提。
# 一旦有人在这里 import android.*，FuzzyTranslatorTest 就编不过，测试会名存实亡。
for name, src in (("FuzzyFlow", fuzzy), ("TextNormalizer", normalizer),
                  ("SemanticTranslator", translator)):
    bad = re.findall(r"^\s*import\s+(android\.[\w.]+|org\.json[\w.]*);", src, re.M)
    check("FF-4", f"{name} 保持纯逻辑（零 android / org.json 依赖）", not bad,
          f"引入了：{bad}" if bad else "")

# ---------------------------------------------------------------- FF-1
# 风险任务必须有确认点：这是「谁来决定能不能替用户花钱」的那道闸。
validate_body = body_of(fuzzy, "public static List<String> validate(Spec s)")
check("FF-1", "风险任务缺确认点即判不合法（risk != none ⇒ 需 ask 或 must_ask_before）",
      bool(validate_body) and "风险任务缺少确认点" in validate_body
      and "GUARD_ASK" in validate_body,
      "FuzzyFlow.validate 里的风险确认点分支不见了" if validate_body else "找不到 validate 方法体")
check("FF-1", "意图与风险等级均为受限表（未知值必须归一，不能放行）",
      "INTENTS.contains" in fuzzy and "RISKS.contains" in fuzzy)
check("FF-1", "包名形态校验存在（挡明显不是包名的东西）",
      "looksLikePackage" in fuzzy)

# ---------------------------------------------------------------- FF-2
# 回退：转译失败绝不能卡住主流程（设计方案 C-3）
plan_body = body_of(engine, "private String planTask(Prefs prefs, String task)")
check("FF-2", "转译失败返回 null 走原路径（planText 为空即回退）",
      bool(plan_body) and "r.planText == null" in plan_body and "return null" in plan_body,
      "planTask 里的回退分支不见了" if plan_body else "找不到 planTask 方法体")
check("FF-2", "转译异常被兜住（catch Exception → 返回 null）",
      bool(plan_body) and "catch (Exception" in plan_body)
check("FF-2", "主循环对 plan 为空仍能正常执行（有 plan 才追加）",
      bool(re.search(r"if \(plan != null\)\s*firstMsg\.append", engine)))
check("FF-2", "解析/校验不过时丢弃半成品（不把 JSON 原文喂给引擎）",
      "planText == null" in translator or "return r;" in translator)

# ---------------------------------------------------------------- FF-8
# 单次往返（C-1）：转译不得在既有规划调用之外再加一次请求
chat_calls = plan_body.count("planner.chat(")
check("FF-8", "转译只发一次模型请求（不新增往返）", chat_calls == 1,
      f"planTask 内出现 {chat_calls} 次 planner.chat(" if chat_calls != 1 else "")

# ---------------------------------------------------------------- FF-6
# 包名白名单：模型最爱编包名，不挡就会拿着不存在的包名启动失败再兜圈子
interp_body = body_of(translator, "public static Result interpret(String reply, Set<String> allowedPkgs)")
# interpret 已按「归一化文本是否已知」拆成两个重载（2026-09-19：记忆库要流程骨架）：
# 判断逻辑在带 normalized 的主实现里，短签名那个只是转发。取两个方法体的并集，
# 免得"换个重载签名门禁就假失败"——门禁判的应当是**有没有做白名单过滤**，不是签名长什么样。
interp_body += body_of(translator,
                       "public static Result interpret(String reply, Set<String> allowedPkgs, String normalized)")
check("FF-6", "interpret 对包名做白名单过滤（不在表内即清除）",
      bool(interp_body) and "allowedPkgs.contains" in interp_body,
      "interpret 里没有白名单判断" if interp_body else "找不到 interpret 方法体")
check("FF-6", "白名单来源包含已装应用与常用应用表",
      "allowedPackages()" in engine and "APP_NAMES.values()" in engine
      and "installedApps()" in engine)

# ---------------------------------------------------------------- FF-5
# 否定词保护：删掉「别」= 把任务反过来（代价最高的单点错误）
strip_body = body_of(normalizer, "public static String stripPrefixFillers(String s)")
check("FF-5", "剥填充词前先判否定词（命中即整体放弃删词）",
      bool(strip_body) and "hasNegation" in strip_body,
      "stripPrefixFillers 里没有否定词保护" if strip_body else "找不到 stripPrefixFillers")
check("FF-5", "否定词表非空且含「别」「不要」",
      "NEGATIONS" in normalizer and '"别"' in normalizer and '"不要"' in normalizer)
check("FF-5", "整句都是填充词时不清空（宁可执行也不丢任务）",
      bool(strip_body) and "rest.isEmpty()" in strip_body)

# ---------------------------------------------------------------- FF-7
# 路径建议不是脚本：必须显式声明，否则模型会盲从预设路径而无视屏幕实况（守则 §3）
compile_body = body_of(fuzzy, "public static String compile(Spec s)")
check("FF-7", "编译产物显式声明「路径建议仅供参考、以屏幕实况为准」",
      bool(compile_body) and "不是命令" in compile_body,
      "compile 里少了那句免责声明" if compile_body else "找不到 compile 方法体")
check("FF-7", "编译产物有长度上限（不挤占屏幕节点预算）",
      "COMPILE_MAX_CHARS" in fuzzy and "COMPILE_MAX_CHARS" in compile_body)

# ---------------------------------------------------------------- FF-3
# 用户可见文案零技术词（SPEC-UX-001 UX-2 的延伸：转译层新增的可见文案同样要守）
progress_texts = re.findall(r'ov\.progress\("([^"]+)"\)', engine)
BANNED = ("API", "Base URL", "base_url", "endpoint", "RAM", "token", "JSON", "模型名", "schema")
hits = sorted({t for t in progress_texts for b in BANNED if b in t})
check("FF-3", "转译阶段新增的用户可见文案零技术词", not hits,
      f"命中：{hits}" if hits else "")
manual("FF-3", "澄清卡片的文案零技术词（P1 落地后由卡片守卫覆盖）",
       "P0 未做澄清卡片，本条待 P1")

# ---------------------------------------------------------------- 落地完整性
check("FF-9", "转译层已接入主循环入口（runLoop 调用 planTask）",
      bool(re.search(r"String plan = planTask\(prefs, task\);", engine)))
check("FF-9", "桌面 JVM 回归存在（tests/FuzzyTranslatorTest.java）",
      (ROOT / "tests" / "FuzzyTranslatorTest.java").exists())
check("FF-9", "Makefile 已挂载本门禁与桌面回归",
      "fuzzy_flow_guard.py" in read(ROOT / "Makefile")
      and "FuzzyTranslatorTest" in read(ROOT / "Makefile"))

# ---------------------------------------------------------------- 输出
width = 78
print("=" * width)
print("模糊语义转译层门禁（设计方案 §11.1）")
print("=" * width)
fails = 0
for cid, desc, state, note in RESULTS:
    line = f"  {state:6} [{cid}] {desc}"
    # 备注只在「失败」与「待后续」时展示：PASS 行挂一句失败文案只会误导读者
    if note and state != "PASS":
        line += f" — {note}"
    print(line)
    if state == "FAIL":
        fails += 1
print("-" * width)
print(f"结果：{sum(1 for r in RESULTS if r[2] == 'PASS')} 通过 / {fails} 失败 / "
      f"{sum(1 for r in RESULTS if r[2] == 'MANUAL')} 待后续阶段")
if fails == 0:
    print("模糊语义转译层（可静态判定部分）通过 ✅")
sys.exit(1 if fails else 0)
