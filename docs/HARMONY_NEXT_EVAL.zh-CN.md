# HarmonyOS NEXT 原生重写可行性评估报告（T9）

> 日期：2026-09-11 ｜ 结论：**设备端原生 Agent App 路线在 NEXT 上不可行（当前政策）；HDC 外挂式是唯一已验证的自动化通道；原生重写仅当华为放开三方无障碍权限后才启动**
> 关联：[异形屏与鸿蒙适配规划书](PLAN_DISPLAY_HARMONY_2026-09-11.zh-CN.md) §4.4

---

## 1. 政策与权限现状复核（2026 年证据链）

| # | 结论 | 证据（2026 年） |
|---|---|---|
| P1 | HarmonyOS 5.0（NEXT）是"纯血鸿蒙"分水岭，**不兼容/不安装 APK**；2.0–4.x 保留 AOSP 兼容层可直装 APK | [华为开发者问答：升级后 APK 不兼容](https://developer.huawei.com/consumer/cn/forum/topic/0202202686633827516) · [cnblogs 分水岭梳理](https://www.cnblogs.com/zdt168/p/19818103) |
| P2 | NEXT **不给三方应用开放无障碍（Accessibility）服务权限**：三方集成了无障碍扩展的应用安装后"已安装的服务显示灰色，显示无服务"；无障碍 Kit 面向"应用自身适配无障碍属性"，非三方操控 | [itying 三方无障碍灰色/无服务](https://bbs.itying.com/topic/68c576712cb460013cc160e3) · [华为开发者问答](https://developer.huawei.com/consumer/cn/forum/topic/0202203356179437036) · [读屏软件纯血鸿蒙适配说明](https://mm.dmrjkj.com/social/newforum/topic/?id=626009) |
| P3 | 因此 Android 版的核心底座（AccessibilityService 节点树 + dispatchGesture + takeScreenshot）在 NEXT 上**全部无对应能力**：读屏、自动点击类应用（含本项目的形态）在 NEXT 生态整体受限，非本项目独有 | 同上；[Accessibility Kit 简介（华为官方）](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/accessibilitykit-overview) |
| P4 | 自动化通道在 NEXT 上事实标准是 **HDC（HarmonyOS Device Connector）调试通道**：`uitest dumpLayout`（控件树）、`uitest uiInput`（点击/滑动/输入）、截图抓取，需开发者模式 | [ArkXTest 官方指南（Gitee docs 镜像）](https://gitee.com/enxue/docs/blob/master/zh-cn/application-dev/application-test/arkxtest-guidelines.md) · [itying hdc uitest 讨论](https://bbs.itying.com/topic/688e7cda2cb460013cc0f88e) |
| P5 | **该路线已被业界开源项目验证**：智谱 Open-AutoGLM 合入"支持鸿蒙OSNEXT的HDC"驱动（PR #221）；codeskyblue/uiautodev 提供 HarmonyOS Driver——HDC 驱动的鸿蒙 Agent 是成熟形态 | [Open-AutoGLM PR #221](https://github.com/zai-org/Open-AutoGLM/pull/221) · [uiautodev HarmonyOS Driver](https://deepwiki.com/codeskyblue/uiautodev/3.4-harmonyos-driver) |

**结论**：F2（规划书）在 2026 年持续成立且证据更强。设备端 App 路线冻结；HDC 外挂式（规划档 2）从"待验证方案"升级为**业界已验证路线**，可直接参考 Open-AutoGLM/uiautodev 的命令封装实现原型。

## 2. HDC 通道能力盘点（T8 原型输入）

| 能力 | HDC 命令（待真机逐一核实） | 对应 Android 侧 |
|---|---|---|
| 控件树 | `hdc shell uitest dumpLayout -p <json路径>` 或 `-i/-a`（ArkXTest 文档） | uiautomator dump / A11y 节点 |
| 点击/滑动 | `hdc shell uitest uiInput click x y` / `swipe x1 y1 x2 y2 [speed]` | input tap / dispatchGesture |
| 文本输入 | `hdc shell uitest uiInput text <text>`（中文/特殊字符有报错案例，需转义或拼音方案） | input text / ACTION_SET_TEXT |
| 按键 | `hdc shell uitest uiInput keyEvent <code>` | input keyevent |
| 截图 | `hdc shell snapshot_display -f <路径>` + `hdc file recv`（命令名待核实） | screencap |
| 应用管理 | `hdc shell aa start/stop -a <ability> -b <bundle>` | am start/force-stop |
| 设备信息 | `hdc shell hidumper` / `param get` 系列 | getprop/dumpsys |

已记录的坑（社区，原型首日探测验证）：`uiInput text` 对中文报错率高（[itying 案例](https://bbs.itying.com/topic/688e7cda2cb460013cc0f88e)）；`dumpLayout` 需 daemon 运行时执行。原型文本注入需准备降级链（拼音/剪贴板/键盘事件）。

## 3. 原生 ArkTS 重写评估（假设政策放开时的工程量）

若未来华为向三方开放无障碍（或用户明确要求投入），需重写模块及与现有 Java 逻辑的映射：

| 现有模块（agent-lite） | ArkTS 对应 | 复用度 | 工作量估计 |
|---|---|---|---|
| AgentA11yService（感知/手势/截图/输入） | Accessibility Kit 扩展 Ability（`accessibilityExtensionAbility`）| 逻辑可移植，API 全换 | 5–8 人日 |
| AgentEngine 决策循环/SCHEMA/动作解析 | 纯逻辑，几乎直译 | **高（80%+ 直译）** | 3–5 人日 |
| LlmClient（OpenAI 兼容 HTTP） | `@ohos.net.http` 直译 | **高** | 1–2 人日 |
| OverlayController 胶囊/光晕/卡片 | ArkUI 组件 + 悬浮窗能力 | 低（UI 全重写） | 4–6 人日 |
| AuthStore/SensitiveGuard/EcoPolicy/TaskHistory/MemoryStore | 纯逻辑直译 | **高** | 2–3 人日 |
| MainActivity + 4 管理页 | ArkUI 页面 | 低 | 3–4 人日 |
| RootShell/沙盒 | NEXT 无 root 生态 → 整块裁剪 | 无 | — |
| **合计** | | | **18–28 人日（约 1–1.5 人月）** |

结论：工程量可控（引擎/策略层复用度高），但 **P2 权限墙使收益为零**——扩展服务即使能装也不会被系统激活（"灰色/无服务"）。在政策放开前投入是纯沉没成本。

## 4. 结论与决策

1. **档 3（原生重写）本期不做**，触发条件：华为放开三方无障碍权限（以华为开发者文档/公告为准，复核带时间戳）或用户明确要求投入。
2. **档 2（HDC 外挂式）升格为鸿蒙主线**：有业界先例（Open-AutoGLM PR #221、uiautodev），T8 原型可直接参考其命令封装；需 1 台 NEXT 真机（或确认华为云真机开放 hdc 直连）做首日命令探测。
3. **档 1（兼容层 2–4.x）维持原规划**：低成本、与 Android 同链路，1 台旧机即可验证。
4. 原型建议：Python（与 tests/agent_test.py 同栈），`tools/harmony-agent/harmony_agent.py`，CLI 风格对齐现有测试脚本；首日先做「命令可用性探测表」再写循环。

---

*附：复核时间 2026-09-11；政策类结论建议每季度复核一次（规划 R5 风险项）。*
