# 异形屏幕适配与鸿蒙设备适配 —— 项目规划书

> 版本：v1.1 ｜ 日期：2026-09-11 ｜ 状态：**已评审（用户确认）**
> 关联文档：[PROJECT_OVERVIEW.md](../PROJECT_OVERVIEW.md) · [进度汇报 2026-09-06](PROGRESS_2026-09-06.zh-CN.md) · [兼容性说明](../agent-lite/COMPATIBILITY.zh-CN.md) · [豆包规格适配](DOUBAO_SPEC_ADAPTATION.zh-CN.md)
> 主改代码：`agent-lite/`（零 root 主推线）；鸿蒙外挂原型新增 `tools/harmony-agent/`

---

## 1. 背景与目标

### 1.1 背景

项目当前在两条真机链路实测通过（2026-09-06）：

| 设备 | 形态 | 分辨率/密度 | 关键差异 |
|---|---|---|---|
| 一加 13T（PKX110） | 直板手机，root | 1216×2640 @ 560 | 中置单挖孔（形态待真机采集确认） |
| 荣耀 MagicPad3 Pro（YLP-W00） | 平板，无 root | 3200×2136 @ 420 | 横屏为主，无挖孔（待确认） |

现有适配假设是「全矩形、无遮挡、竖屏手机」。当设备形态超出该假设时，存在四类真实风险：

1. **挖孔/刘海/药丸屏**：截图包含黑色挖孔区（部分 ROM 截图会把挖孔区填平，形态不一），顶部状态栏高度被拉高，模型按常规布局预估坐标会点偏；
2. **曲面/瀑布屏**：左右边缘存在物理禁触区（waterfall insets），手势注入落在该区无效且不回执；
3. **分屏/小窗/旋转/多显示**：节点坐标（窗口内绝对坐标）与截图坐标（全屏）在非全屏窗口场景错位，px/py 比例坐标按全屏估计会错位；
4. **鸿蒙设备**：HarmonyOS 2–4.x 兼容层能装 APK 但无障碍/截图行为未知；HarmonyOS NEXT（5.0+，2026 年起新机主流）**不再兼容 APK**，且三方应用**无法获得无障碍操控权限**，需要完全不同的技术路线。

### 1.2 目标与成功定义

**总目标**：让 Agent 在「任何挖孔/刘海/曲面/圆角形态、任意旋转/分屏状态的 Android 11+ 设备」上保持与直板机同等的任务成功率；并对鸿蒙设备给出可落地、已验证的适配路线。

量化验收（详见 §9）：

- **A 异形屏**：在 3 类形态真机/模拟器（中置挖孔、左挖孔+药丸、瀑布曲面）上，10 条标准任务成功率 ≥ 90%，且「状态栏区域元素点击」「全屏视频」「分屏双窗口」「旋转切换」四类专项用例全绿；
- **B 鸿蒙兼容层**：1 台 HarmonyOS 2–4.x 真机上，核心链路（无障碍开、节点采集、截图、手势、文本注入）全部可用或给出明确降级方案，兼容性矩阵更新；
- **C 鸿蒙 NEXT 外挂式**：HDC 驱动原型完成一条「打开设置 → 搜索 → 返回」的端到端任务；
- **D 结论文档**：NEXT 原生重写可行性评估报告（含政策复核证据）。

### 1.3 非目标（本期不做）

- 折叠屏折叠态/展开态动态切换的完整支持（只做静态展开态验证，动态切换列入 P2 观察）；
- 车机/电视/手表等非手持 Android 形态；
- OpenHarmony（开源鸿蒙）发行版；
- 鸿蒙侧的原生 ArkTS 完整重写（本期只出评估报告，见 §4.4）。

---

## 2. 关键事实与假设

### 2.1 已查证事实（2026-09-11 网络核查）

| # | 事实 | 来源 | 影响 |
|---|---|---|---|
| F1 | HarmonyOS 5.0（NEXT）是「纯血鸿蒙」分水岭，**不再兼容/安装 APK**；2.0–4.x 保留 AOSP 兼容层，可直装 APK（部分机型有「卓易通」等容器） | [华为开发者问答](https://developer.huawei.com/consumer/cn/forum/topic/0202202686633827516) · [cnblogs 分水岭梳理](https://www.cnblogs.com/zdt168/p/19818103) | 鸿蒙适配必须分轨：兼容层走 APK 验证，NEXT 走外挂式 |
| F2 | HarmonyOS NEXT **不给三方应用开放无障碍（Accessibility）权限**，Accessibility Kit 主要面向「应用自身被读/自适配」，不是三方操控 | [itying 无障碍权限讨论](https://bbs.itying.com/topic/69689ae76f4d61004c83a9d4) · [Accessibility Kit 简介](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/accessibilitykit-overview) | 设备端原生 Agent App 路线在 NEXT 上**不可行**；只能走 HDC 调试通道（开发者模式） |
| F3 | NEXT 上通过 HDC 可导出控件树（`uitest dumpLayout`）、注入输入（`uitest uiInput`）、抓屏（snapshot 类命令）；DevEco Testing 系列工具即基于此 | 华为开发者工具公开文档（命令名待真机核实） | 外挂式原型的技术底座成立，但命令集需实测 |
| F4 | Android 28+ 提供 `DisplayCutout`（挖孔/刘海边界）、29+ 提供瀑布屏 `WaterfallInsets`；30+ `WindowMetrics.getWindowInsets()` 可拿到含 cutout 的窗口 insets | Android 官方 API | DisplaySpec 采集可完全零 root 实现 |
| F5 | 现有代码已做两处异形屏准备：覆盖层 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` + `FLAG_LAYOUT_NO_LIMITS`（[OverlayController.java:75-80](../agent-lite/src/com/dsh/agentlite/OverlayController.java)）；胶囊移至底部避开顶栏挖孔 | 仓库代码 | 覆盖层基本无额外工作，重点在感知/执行层 |

### 2.2 待真机验证的假设（每项均挂入测试矩阵）

- **H1**：A11y 节点 `boundsInScreen` 与 `takeScreenshot()` 截图在全屏场景坐标一致（AOSP 语义如此，但厂商 ROM 可能有偏移 bug）——用「截图视觉位置 vs 节点 bounds」对照用例验证；
- **H2**：一加 13T 挖孔区在 ColorOS 截图中呈现为黑块，且挖孔下方状态栏高度 > 常规 24dp——采集后注入提示词即可缓解；
- **H3**：荣耀平板在分屏/小窗模式下 `getWindows()` 返回的窗口 bounds 准确——若不准，分屏场景降级为纯视觉+全屏坐标；
- **H4**：兼容层鸿蒙（2–4.x）上 `AccessibilityService.takeScreenshot`（API 30+ 依赖）行为与 AOSP 一致——若缺失则降级节点模式或 root/HDC 截图；
- **H5**：HDC `uitest uiInput click/tap` 在 NEXT 上可注入全局点击（非仅测自家应用）——若受限，外挂式路线降级为「只读 + 人工执行」报告模式。

---

## 3. 现状盘点（基于 2026-09-06 代码）

### 3.1 已具备的能力（无需重做，仅回归）

| 能力 | 位置 | 说明 |
|---|---|---|
| 比例坐标 | `AgentEngine.java:573-574,1197-1201` | `tap/longpress` 用 `px,py∈[0,1]`，`swipe` 用 `px1..py2`，执行时乘截图宽高——天然分辨率无关 |
| 截图降采样 | `AgentEngine.java:429,1371-1378` | ≤640px JPEG；平板 3200px 实测可跑 |
| 节点优先点击 | `AgentA11yService` `tapNode()` | ACTION_CLICK 爬可点祖先 ≤8 层，平板「点不动搜索栏」已修复 |
| 全窗口节点合并 | `AgentA11yService.collectNodes` | `getWindows()` 合并 TYPE_APPLICATION、排除本包名、按 layer 降序 |
| 覆盖层异形屏准备 | `OverlayController` | SHORT_EDGES cutout 模式 + 底部灵动胶囊（已规避顶栏挖孔） |
| 多显示感知基础 | `getWindowsOnAllDisplays()`（agent-client）/ 全窗口（agent-lite） | 采集侧有底子，执行侧未适配 |

### 3.2 缺口清单（本规划要补的）

| # | 缺口 | 后果 | 优先级 |
|---|---|---|---|
| G1 | 无 `DisplaySpec` 抽象：代码零散地取 `getDisplayMetrics()`，没有 cutout/waterfall/状态栏/导航栏/旋转/导航模式的统一采集与缓存 | 异形屏信息无法进提示词、无法做边缘 clamp | P0 |
| G2 | 感知提示词只有「屏幕宽/高」，没有安全区、挖孔位置、状态栏高度、当前窗口 bounds | 模型在异形屏/分屏下坐标预估偏差大 | P0 |
| G3 | px/py 落点无 clamp：落在瀑布边缘/挖孔区不处理 | 曲面屏边缘点击静默失效 | P0 |
| G4 | 无坐标一致性自检：截图尺寸 vs `DisplayMetrics.real`、节点 bounds vs 截图视觉位置未校验 | 厂商偏移 bug 不可见，表现为「随机点偏」 | P0 |
| G5 | 分屏/小窗：截图是全屏、操作窗口是局部，比例坐标按全屏估算错位；无「当前焦点窗口 bounds」提示 | 平板分屏任务必翻车 | P1 |
| G6 | 旋转：节点表与截图在旋转瞬间可能过期（旧尺寸缓存） | 横竖屏切换后第一步点偏 | P1 |
| G7 | 鸿蒙兼容层行为零数据；NEXT 完全空白 | 鸿蒙设备不可用 | P2 |
| G8 | 兼容性文档无「异形屏/鸿蒙」章节 | 使用者不知道哪些设备能跑 | P1/P2 |

---

## 4. 技术方案

### 4.1 异形屏幕适配（主线，改动全部落在 `agent-lite/`）

#### 4.1.1 DisplaySpec —— 设备形态统一抽象（新增文件）

新增 `agent-lite/src/com/dsh/agentlite/DisplaySpec.java`：

```text
字段（全部零 root 采集）：
- realW/realH/densityDpi        → WindowManager.getCurrentWindowMetrics().getBounds() + DisplayMetrics
- rotation                      → getRotation()（0/1/2/3）
- cutoutRects[]                 → getDisplayCutout().getBoundingRects()（API 28+；空数组=无挖孔）
- waterfallL/R                  → getWaterfallInsets()（API 29+；0=直屏）
- statusBarH / navBarH          → getWindowInsets().getInsets(STATUS_BAR/NAVIGATION_BAR)
- navMode                       → 手势 or 三键（通过 `config_navBarInteractionMode` 资源探测，包一层 try/catch 兼容各 ROM）
- safeRect[L,T,R,B]             → 可安全触控区 = 全屏区 − cutout − waterfall − 系统栏重叠区
- displayId / 是否多屏 / 是否折叠展开态（窗口宽高比启发式 + 用户可在配置页手改）
- fingerprint                   → 上述字段哈希，变化即触发「形态变更」事件
```

采集时机：A11y 服务 `onAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)` 节流刷新（500ms）+ 每步 perceive 前比对 fingerprint；`MainActivity` 配置页新增「设备形态」只读展示卡（便于真机核对）。

#### 4.1.2 感知注入 —— 把形态告诉模型

`AgentEngine.perceive()` 的视觉/节点提示块在现有「屏幕宽=.. 高=..」基础上追加（保持简洁，≤120 字）：

```text
屏幕形态：状态栏高 {statusBarH}px｜导航区高 {navBarH}px｜安全触控区 [L,T,R,B]｜
挖孔位于 {左|中|右}（{rect}）｜{曲面屏：左右边缘 {waterfallL/R}px 为禁触区}｜
当前操作窗口占屏幕 {左半/右半/上半/全屏}（分屏/小窗时）
```

视觉截图如带挖孔黑块，黑块即挖孔、不是 UI 元素（防模型把黑块当目标）。

#### 4.1.3 落点安全 —— 边缘 clamp 与禁触区回避

`AgentEngine` 执行 `px/py → 绝对坐标` 时统一走新方法 `DisplaySpec.clampTouch(x,y)`：

1. 落点不在 `safeRect` 内 → 向安全区中心方向夹紧（clamp）；
2. 落点在 waterfall 禁触区 → 平移至 `waterfallL/R` 内侧 8px；
3. clamp 后偏移 > 40px 时打日志 `clampTouch(偏离40px)` 并在下一步提示词中告知模型「上次点击被安全区修正」，避免模型陷入重试循环。

仅对**纯视觉比例坐标**生效；`tap#index` 节点点击天然有效（系统保证节点可点），不改。

#### 4.1.4 坐标一致性自检（G4）

双通道校验，发现异常即日志 + 注入告警：

- **尺寸校验**（每次截图）：截图 Bitmap 宽高 vs `DisplaySpec.realW/realH`，不等（旋转/多屏/降采样外）→ 告警并触发 DisplaySpec 强制重采集；
- **节点-视觉校验**（诊断页手动触发，`MainActivity` 设备形态卡里加「校准」按钮）：取 4 个已知锚点节点（状态栏时钟、导航返回键等），比对节点 bounds 与截图视觉位置，偏移均值 > 阈值 → 记录「本 ROM 存在坐标偏移 Δ」存入 DisplaySpec 全局修正量，后续节点坐标执行前统一加 Δ。

#### 4.1.5 分屏/旋转/多显示（G5/G6）

- 感知时检测焦点窗口 bounds（`getWindows()` 里取 active window），非全屏 → 注入「当前操作窗口仅占屏幕 {区域}」，并建议模型该步优先用 `tap#index`；纯视觉模式下要求模型给出**相对全屏**的比例坐标（截图是全屏，语义不变）；
- 旋转：DisplaySpec fingerprint 变化 → 该步感知结果标记「形态已变更」，强制丢弃上一步节点表缓存（现有「节点编号每步重新分配」规则外再加「屏幕旋转后旧截图失效」）；
- 多显示（外接屏/折叠外屏）：本期只保证默认屏正确、其他屏明确报「不支持」而非乱点（agent-client 已有 `getWindowsOnAllDisplays` 底子，agent-lite 沿用其思路）。

#### 4.1.6 回归保护

所有注入/clamp 改动默认开启但可通过 `Prefs` 关闭（`spec_inject`/`spec_clamp`），保证一加 13T、荣耀平板两条已通过链路可一键回退对比。

### 4.2 鸿蒙适配 · 档 1 —— 兼容层（HarmonyOS 2–4.x，低成本）

APK 直装验证，产出兼容性矩阵增量。验证清单（依次执行）：

1. 安装与启动（`hdc install` 或文件管理器侧载）；
2. 无障碍服务能否在设置中开启、是否被系统自动回收（鸿蒙兼容层对无障碍有额外管控，需观察 30 分钟保活）；
3. `takeScreenshot()` 可用性（API 30+ 依赖，兼容层若缺失 → 验证节点模式 + 降级提示）；
4. `dispatchGesture()` / `tapNode()` 有效性（H4 的节点 bounds 一致性一并验证）；
5. `ACTION_SET_TEXT` 中文注入与默认输入法行为；
6. 覆盖层（TYPE_ACCESSIBILITY_OVERLAY）与胶囊显示；
7. 截图色彩空间（部分鸿蒙机型 P3 截图 → 模型视觉识别是否受影响，必要时转 sRGB）。

前置条件：**需要 1 台 HarmonyOS 2–4.x 真机**（见 §10 待确认事项）。

### 4.3 鸿蒙适配 · 档 2 —— NEXT 外挂式（HDC 驱动，中成本）

因 F2（NEXT 不给三方无障碍权限），设备端 App 路线不可行；改为 **PC 侧 Agent + HDC 通道**：

```text
PC (Mac/Win) ── hdc (USB/WiFi) ──> 鸿蒙 NEXT 设备
   │  Python/Go 原型（新增 tools/harmony-agent/）
   ├─ dump : hdc shell uitest dumpLayout          → 控件树 JSON（文本/坐标/可点性）
   ├─ shot : hdc shell snapshot_display + file recv → 截图（命令名以真机为准）
   ├─ exec : hdc shell uitest uiInput click/swipe/text
   └─ LLM 循环：复用 agent-lite 的 SCHEMA 思路（tap#index + px/py 比例坐标 + done/fail）
```

- 语言选 **Python**（与 `tests/agent_test.py` 同栈，快速验证；稳定后如需常驻再考虑 Go 复用 `agentd-go` 的循环逻辑）；
- 输出物：`tools/harmony-agent/harmony_agent.py`（CLI：`--task "打开设置搜索蓝牙" --serial xxx`）+ `README.md`（hdc 环境、开发者模式开启步骤）；
- 验收门槛：一条端到端任务 + 记录命令集可用性表（哪个命令失败、替代方案是什么）。

### 4.4 鸿蒙适配 · 档 3 —— NEXT 原生重写评估（仅报告，不实施）

本期只产出一页评估报告（放入 `docs/`），内容：F2 政策复核（含 2026 年最新版本文档链接）、Accessibility Kit 三方权限现状、若未来放开权限的工程量估算（ArkTS + ArkUI 感知/执行层与现有 Java 逻辑的映射表）、替代形态（无障碍场景合法用途版、远程协助版）。**触发实施的条件**：华为放开三方无障碍权限或用户明确要求投入原生重写。

---

## 5. 任务分解与排期

> 排期为单人全职估计；P0 完成即达到「异形屏可用」，P2 依赖鸿蒙真机到位。

| 编号 | 任务 | 改动点/产出 | 验收 | 优先级/估时 |
|---|---|---|---|---|
| T1 | DisplaySpec 实现 | 新增 `DisplaySpec.java`；A11y 服务节流采集；MainActivity 设备形态展示卡 | 3 台设备上字段与真机实测一致 | P0 / 0.5d |
| T2 | 感知注入 + clamp | `AgentEngine.perceive` 形态注入；`clampTouch`；日志与重试提示 | 模拟器挖孔/瀑布形态下注入文本与 clamp 日志正确 | P0 / 0.5d |
| T3 | 坐标一致性自检 | 尺寸校验 + 锚点校准（诊断页） | 人为注入偏移能检出并修正 | P0 / 0.5d |
| T4 | 分屏/旋转/多显示处理 | 焦点窗口 bounds 注入；形态变更清缓存；默认屏外报「不支持」 | 平板分屏、手机旋转用例通过 | P1 / 1d |
| T5 | 异形屏真机/模拟器矩阵回归 | 见 §6 用例清单；一加/荣耀全量回归 + 模拟器 cutout 模拟 | §9-A 达标 | P1 / 1d |
| T6 | 兼容性文档增补 | `COMPATIBILITY.zh-CN.md` 新增「屏幕形态矩阵」「鸿蒙分档」章节 | 文档含实测数据非猜测 | P1 / 0.25d |
| T7 | 鸿蒙兼容层验证 | §4.2 七项清单 + 修复 | §9-B 达标；矩阵更新 | P2 / 1d（需真机） |
| T8 | HDC 外挂原型 | `tools/harmony-agent/`（Python） | §9-C 达标；命令集可用性表 | P2 / 1.5d（需真机） |
| T9 | NEXT 原生评估报告 | `docs/HARMONY_NEXT_EVAL.zh-CN.md` | 政策复核含 2026 最新链接；结论明确 | P2 / 0.5d（可无真机） |
| T10 | 总结汇报 + 更新 PROJECT_OVERVIEW/进度文档 | 文档更新 | 与用户核对后定稿 | P2 / 0.25d |

依赖关系：T1→T2→T3→T5（T4 可与 T3 并行）；T7/T8 依赖鸿蒙真机；T9 可随时做。

---

## 6. 测试矩阵

### 6.1 设备清单

| 设备 | 形态特征 | 用途 | 状态 |
|---|---|---|---|
| 一加 13T | 中置挖孔，ColorOS 16，root | 挖孔主线回归 | 已有 |
| 荣耀 MagicPad3 Pro | 大屏平板、横屏、无挖孔 | 平板/分屏/旋转 | 已有 |
| Android 模拟器（API 34+，开启 cutout 模拟：中置/左挖孔/药丸 + waterfall） | 可控形态 | T2/T3 快速迭代 + 禁触区用例 | 待建 |
| 华为 HarmonyOS 2–4.x 真机（如 MatePad 11 / Nova 9 系） | 兼容层 | T7 | **需采购/借用** |
| 华为 NEXT 真机（Mate 60/Pura 70/MatePad Pro 13.2 任一台，升级至 NEXT 5+） | 纯血鸿蒙 | T8 | **需采购/借用** |

### 6.2 标准任务集（10 条，每台设备全跑）

1. 打开设置 → 搜索「蓝牙」→ 返回主页
2. 打开 B 站 → 搜索「鸿蒙」→ 读第一个视频标题
3. 打开相册 → 打开第一张图 → 返回
4. 下拉通知栏（模拟）→ 上滑关闭
5. 打开计算器 → 算 123×45 → 读结果
6. 打开浏览器 → 输入网址 → 返回
7. 打开微信 → 找到搜索入口（节点屏蔽时视觉定位）
8. 切换深色模式往返
9. 旋转横竖屏往返 → 打开设置定位「显示」
10. 打开两个 App 分屏 → 在下半窗口完成一步操作

### 6.3 异形屏专项用例

- **S1 挖孔区**：状态栏正下方元素点击（如设置页返回箭头位于挖孔旁）；
- **S2 药丸/灵动岛区**：目标恰在药丸下方 20px 内；
- **S3 瀑布曲面**：px/py 指向屏幕最左 3% 区域 → 验证 clamp 生效且任务成功；
- **S4 截图一致性**：截图尺寸 = real 尺寸；节点 bounds 与截图视觉位置偏差 ≤ 5px（锚点校准验证 H1）；
- **S5 分屏**：任务 10 + 焦点窗口 bounds 注入断言；
- **S6 旋转**：任务 9 + 「形态已变更」日志断言（G6）；
- **S7 色彩**：深色模式 + P3 屏截图下视觉识别正确（鸿蒙兼容层重点验证）。

### 6.4 鸿蒙专项

- 兼容层：§4.2 七项清单全部出结论（支持/不支持/降级方案）；
- NEXT：HDC 命令集可用性表（dumpLayout / uiInput / 截图 / 文本输入），至少一条端到端任务视频或日志留档。

---

## 7. 里程碑

| 里程碑 | 内容 | 出口条件 | 状态 |
|---|---|---|---|
| M0（本次） | 规划书评审通过 | 用户确认方案与设备采购安排 | ✅ 2026-09-11 |
| M1 | 异形屏代码落地（T1–T4） | 模拟器全用例绿 + 两台已有真机回归通过 | 🔶 代码完成（T1–T4 已提交），模拟器矩阵绿，**真机回归待用户连接设备** |
| M2 | 异形屏矩阵验证（T5/T6） | §9-A 达标，兼容性文档更新 | 🔶 T6 文档已更新；T5 真机部分待做 |
| M3 | 鸿蒙兼容层（T7） | 矩阵结论产出（依赖真机） | ⏸ 挂起（用户确认等设备） |
| M4 | NEXT 外挂原型 + 评估报告（T8/T9） | §9-C 达标（依赖真机；T9 不依赖） | 🔶 **T9 已完成**（`docs/HARMONY_NEXT_EVAL.zh-CN.md`）；**T8 原型已先行开发**（`tools/harmony-agent/`：hdc 封装 + LLM 循环 + 首日探测，单元验证过，待 NEXT 真机命令探测与端到端验收） |
| M5 | 收尾（T10） | PROJECT_OVERVIEW 与进度文档更新，规划书状态改「已完成」 | ⏳ |

### M1 模拟器矩阵实测记录（2026-09-11，AVD Agent_API_34 / Pixel 7 / Android 14）

| 用例 | 结果 |
|---|---|
| 双挖孔采集（cutout emulation.double） | ✅ 顶部药丸 [351,0][729,84] + 底部药丸 [351,2316][729,2400] 精确采集 |
| 安全触控区计算 | ✅ [0,84,1080,2253]（扣状态栏+导航区+挖孔） |
| clampTouch 六项单元断言（app_process 直跑 DisplaySpec） | ✅ 挖孔/状态栏/导航区/瀑布区修正正确，正常落点零扰动 |
| 旋转形态变化检测 | ✅ 竖→横 fingerprint 变化告警，全字段重采集（挖孔 rect 随旋转正确重映射） |
| 瀑布屏回填（waterfall emulation，L53/R53） | ✅ 覆盖层窗口 insets 回填通道生效，安全区正确扣除，与 dumpsys 一致 |
| 手势导航探测 | ✅ 手势模式识别（framework 资源 + 启发式） |
| 端到端任务（内置 key，qwen3.5-omni-plus） | ✅ 「open_settings」13 步完成：规划→感知→截图→直点/滑动→完成卡片 |
| 截图尺寸一致性校验 | ✅ 全程无告警（截图=spec 尺寸） |

**模拟器验证暴露并已修复**：无窗口进程（A11y 服务）的 `WindowMetrics` 不暴露 waterfall side insets → 增补覆盖层窗口 `OnApplyWindowInsets` 回填通道（commit a50503a）。

---

## 8. 风险与回滚

| 风险 | 概率/影响 | 缓解 | 回滚 |
|---|---|---|---|
| R1 鸿蒙真机无法到位 | 中/高 | 立即启动 T9（无真机可做）；兼容层验证委托远程机/云真机（华为云真机平台可跑 NEXT 与老鸿蒙）；用户协助采购 | 档 2/3 降级为「方案 + 模拟报告」 |
| R2 HDC uitest 在 NEXT 上受限（H5 不成立） | 中/高 | 原型第一天先做命令可用性探测（0.5d 探针），不通即止损转报告模式 | 外挂式降级为「只读诊断工具」，不承诺执行 |
| R3 厂商 ROM 节点-截图偏移 bug（H1 不成立） | 低/中 | T3 自检 + Δ 修正量；无法修正的设备进入兼容矩阵「受限」档 | Δ 修正关闭即回原行为 |
| R4 注入/clamp 干扰现有两台设备已通过链路 | 中/中 | 全部改动带 `Prefs` 开关 + 每台设备回归标准任务集 | 一键关闭开关恢复 9-06 行为 |
| R5 NEXT 政策再变化（华为收紧 hdc/uitest） | 低/中 | T9 报告中留政策复核时间戳与链接；原型与政策解耦 | 不影响档 1 与异形屏主线 |
| R6 回归中发现已有功能被破坏 | 低/高 | 每完成一个 P0 任务立即构建 + 两台真机冒烟（不攒批） | git 逐任务提交，可单点 revert |

**通用回滚原则**：P0/P1 每个任务独立提交；M1/M2 完成后在既有两台真机上跑 §6.2 全量 10 任务作为发布门禁，失败即冻结该批次。

---

## 9. 验收标准（汇总）

- **A 异形屏**：M2 出口时，3 类形态设备上标准任务集成功率 ≥ 90%（10 条中 ≥ 9 条），S1–S7 专项全绿；既有两台真机回归通过率 100%；
- **B 鸿蒙兼容层**：M3 出口时，七项清单全部有「支持/不支持+降级」明确结论，兼容性矩阵含实测数据；
- **C NEXT 外挂**：M4 出口时，HDC 原型完成「打开设置 → 搜索 → 返回」端到端，日志留档；命令集可用性表完整；
- **D 评估报告**：NEXT 原生路线给出带 2026 年证据链的「做/不做/何时做」结论；
- **文档**：`COMPATIBILITY.zh-CN.md`、`PROJECT_OVERVIEW.md`、进度文档同步更新。

---

## 10. 已确认决策（2026-09-11 用户评审）

1. **实施顺序**：异形屏主线（M1–M2，T1–T6）立即开工；鸿蒙验证（M3–M4）**挂起**，待设备到位后按规划启动（T9 评估报告可在 M4 前随时做）。
2. **鸿蒙真机**：暂无；到位后按 §6.1 清单补充（1 台 HarmonyOS 2–4.x 兼容层 + 1 台 NEXT）。
3. **模拟器**：可用 Android Studio 模拟器（cutout/瀑布屏模拟）做 T2/T3 快速迭代，真机只做最终回归。
4. **Git 节奏**：按任务逐条提交（每任务一个 commit，可单点 revert）。
5. 首次提交前需先梳理 `.gitignore` 与工作区未跟踪文件。

---

*附：外部参考资料*
- [HarmonyOS 5.0 兼容分水岭梳理（cnblogs）](https://www.cnblogs.com/zdt168/p/19818103)
- [华为开发者问答：升级后 APK 不兼容](https://developer.huawei.com/consumer/cn/forum/topic/0202202686633827516)
- [Accessibility Kit 简介（华为官方）](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/accessibilitykit-overview)
- [NEXT 三方无障碍权限现状讨论（itying）](https://bbs.itying.com/topic/69689ae76f4d61004c83a9d4)
- Android 官方：DisplayCutout / WindowInsets / WindowMetrics
