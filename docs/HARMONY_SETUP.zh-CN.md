# 鸿蒙（HarmonyOS NEXT）适配环境搭建与验证指南

> 目标：在**本机无鸿蒙真机**的情况下，用官方模拟器（虚拟机）跑通 `tools/harmony-agent/` 外挂式 Agent。
> 当前状态（2026-09-12）：**已在 HarmonyOS 7.0 模拟器上跑通**——probe 5/5、端到端验收任务达成
> （「打开设置 → 进入显示和亮度 → 返回上一页」4 步真实完成，节点数 32→38→30→38 佐证返回生效）。

---

## 0. 现在就能跑（无需任何鸿蒙环境）

**mock 设备全链路回归**——用模拟 hdc 设备 + 真实 LLM 验证感知/决策/注入/状态闭环：

```bash
sh tests/harmony_mock.sh
# 内部：fake_hdc.py 冒充 hdc（按 ArkXTest 真实 JSON 格式应答），真实模型决策，校验点击命中开关
# 通过标准：agent 报 done 且 mock 设备 bt_on=true
```

已验证结果（2026-09-11）：三屏场景（桌面→设置→蓝牙）4 步完成，点击轨迹全部命中目标矩形。

---

## 1. 安装鸿蒙环境（需一次性人工步骤）

| 步骤 | 操作 | 说明 |
|---|---|---|
| 1 | 注册/登录**华为开发者账号** | DevEco Studio 与模拟器镜像均需登录后下载（[DevEco Studio 发布页](https://developer.huawei.com.cn/consumer/cn/forum/topic/0201223554149712811?fid=0104164651529951067)） |
| 2 | 下载 **DevEco Studio**（macOS Apple Silicon 版）并安装 | 官网下载页需登录；安装后首次启动会引导下载 SDK |
| 3 | 启动 DevEco → **Device Manager → Local Emulator** 下载一个 HarmonyOS NEXT 模拟器镜像 | 镜像数 GB，需在 IDE 内登录账号 |
| 4 | 创建模拟器实例并启动 | 也可命令行启动（见 §2） |
| 5 | 配置 `hdc` 到 PATH | 随 SDK 安装，通常位于 `~/Library/Huawei/Sdk/*/toolchains/hdc`（以实际安装为准） |

> 说明：`hdc` 是鸿蒙的设备连接与调试工具（作用等同 adb），官方命令参考
> [hdc 调试命令文档](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/hdc)。

安装完成后自检：

```bash
hdc list targets            # 应列出模拟器（形如 127.0.0.1:5555 或 emulator-xxxx）
hdc shell "echo ok"         # 通道连通性
```

## 2. 启动模拟器

**方式 A（推荐，IDE）**：DevEco Studio → Device Manager → 选中实例 → ▶ 启动。

**方式 B（命令行）**：模拟器启动器随 SDK 提供，可用（路径以实际安装为准）：

```bash
# 示例：列出/启动本地模拟器实例（不同 DevEco 版本参数略有差异）
"$HOME/Library/Huawei/Sdk/emulator/emulator" -list-avds
"$HOME/Library/Huawei/Sdk/emulator/emulator" -avd <实例名>
```

命令行启动方式的社区参考：[HarmonyOS Next 命令行方式启动模拟器](https://bbs.itying.com/topic/68f677ed61dd0500413ce9cc)。
启动后 `hdc list targets` 能看到设备即可。

## 3. 首日验证流程（模拟器/真机到位后）

```bash
export HDC=$(command -v hdc)          # 或 export HDC=/path/to/hdc

# ① 命令可用性探测（0.5d 探针，规划 R2 止损机制）
python3 tools/harmony-agent/probe.py
#    预期：list_targets / screen_size / dumpLayout / collect_nodes / screenshot = 5/5
#    任一失败 → 按 probe 提示修正 tools/harmony-agent/hdc.py 里的命令名（命令集中一处）

# ② 端到端任务验收（规划 §9-C：打开设置 → 搜索 → 返回）
AGENT_API_KEY=<key> python3 tools/harmony-agent/harmony_agent.py \
    --task "打开设置并搜索蓝牙" --serial <SN> --max-steps 20
```

## 4. 实机校准结果（HarmonyOS 7.0 模拟器实测，2026-09-12）

模拟器全自动获取（无需 IDE 界面，只需 DevEco Studio 已安装，镜像可命令行下载）：

```bash
EMU=/Applications/DevEco-Studio.app/Contents/tools/emulator/Emulator
HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
$EMU -license accept                                            # 自动接受许可
$EMU -imageList                                                 # 列出可用镜像（phone/tablet/foldable/2in1/wearable/tv…）
$EMU -install -deviceType phone -osVersion "HarmonyOS 7.0.0(26.0.0)"   # 下载镜像（实测 2.31GB）
$EMU -create HarmonyPhone -deviceType phone -osVersion "HarmonyOS 7.0.0(26.0.0)"
$EMU -start HarmonyPhone -noWindow                              # headless 启动
$HDC list targets                                               # → 127.0.0.1:5555
```

| 能力 | 实测正确形式 | 踩坑 |
|---|---|---|
| 屏幕尺寸 | `hidumper -s RenderService -a screen` → `render resolution=1256x2760` | `WindowManagerService` 通道无输出 |
| 控件树 | `uitest dumpLayout -p <file>`；顶层**单对象**、属性**驼峰**（`longClickable`）、**空串即 false** | 原假设的连字符键名不存在 |
| 截图 | `snapshot_display -f <file>`，**后缀必须 `.jpeg`**（`.png` 报 invalid） | 且需校验设备端文件真的生成（曾假阳性） |
| 点击/滑动 | `uitest uiInput click <x> <y>` / `swipe <x1> <y1> <x2> <y2> <speed>` | 点击生效但**页面切换需 2–4s**（`--settle 3`），1.2s 会读到旧屏误判"点击无效" |
| 返回/主页 | `uitest uiInput keyEvent Back` / `keyEvent Home`（**键名**） | 数字键码（2 / 2050 / 2049）**实测无效** |
| 文本输入 | `uitest uiInput text <英文>` 可用；**中文失败** | 失败时 agent 已能按指引降级（实测自动改英文成功） |
| 启动应用 | `aa start -b <bundle> -a <ability>` | — |

**鸿蒙控件树的结构性差异（适配核心）**：可点容器（`Row`/`Column`）自身 `text` 为空，标签在子 `Text` 节点。
平铺解析会让模型看到十几个空容器而无法决策 → `hdc.walk_nodes` 已对可点无文本节点做**子树文本聚合**
（修复前 `Y '' Row`×13；修复后 `语言和地区 / 地区: 中国`、`输入法`、`重置` …）。

**页面上下文**：`dumpLayout` 的 `bundleName`/`abilityName` 提取到 `hdc.LAST_WINDOW` 并写入 prompt
（`当前页面: com.huawei.hmos.settings / MainActivity`），修「模型不知道自己在哪一页」；
注意子页 `pagePath` 可能与主页相同，不能仅靠它判断层级。

**滑动方向**：prompt 已明确 `py1 > py2` = 看更下方、`py1 < py2` = 回滚看更上方；
并把「目标看不到时先向下回滚检查页面上方」写入守则（修掉反复上滑越滑越远的死循环）。

## 5. 文件一览

| 文件 | 作用 |
|---|---|
| `tools/harmony-agent/hdc.py` | HDC 命令封装层（命令名集中一处，探测后修正） |
| `tools/harmony-agent/harmony_agent.py` | LLM 决策循环（tap#index / px,py / swipe / text / done / fail） |
| `tools/harmony-agent/probe.py` | 真机/模拟器首日命令可用性探测 → `probe-report.json` |
| `tools/harmony-agent/fake_hdc.py` | 模拟 hdc 设备（无环境时验证全链路；三屏交互场景） |
| `tests/harmony_mock.sh` | mock 回归脚本（真实 LLM + 模拟设备，校验点击命中） |

## 6. 路线依据

NEXT 上三方无障碍服务不开放 → 设备端原生 App 路线冻结，改用 **PC 侧 Agent + HDC 通道**驱动，
业界已有成熟先例（Open-AutoGLM PR#221、codeskyblue/uiautodev HarmonyOS Driver）。
详见 `docs/HARMONY_NEXT_EVAL.zh-CN.md`。

---

## 7. 鸿蒙手表（wearable）验证记录（2026-09-12）

手表镜像同样可命令行获取：`$EMU -install -deviceType wearable -osVersion "HarmonyOS 7.0.0(26.0.0)"`（954MB），
`$EMU -create HarmonyWatch -deviceType wearable ...` → `$EMU -start HarmonyWatch -noWindow`。
两台模拟器可同时在线（phone 5555 / watch 5557），用 `--serial` 区分。

**屏幕形态：圆形表盘 466×466**（与 Android OPPO Watch 同几何）。判据（截图像素分析）：
四角亮度 0、四边中点亮度 229-232、**对角线首个亮像素 68px**，与理论值 `466/2×(1−1/√2)=68.3px` 精确吻合。

| 能力 | 手表实测 |
|---|---|
| probe 5/5 | ✅ list_targets / screen_size(466×466) / dumpLayout / collect_nodes / screenshot 全通过 |
| 应用内节点 | ✅ 有文本（`设置/WLAN/已关闭/系统`），子树聚合生效（`'WLAN / 已关闭'`） |
| 布局特征 | 弧形列表 `ArcList`，项沿圆弧排列（y=232→369→446，x 居中 233） |
| 启动应用 | ⚠️ 需精确 bundle+ability：`aa start -b com.huawei.hmos.settings -a com.huawei.hmos.settings.MainAbility`<br>（`-a EntryAbility` 报 ability 不存在；`watch.intent.action.hybrid` 隐式启动报不存在） |
| **表盘/桌面节点** | ❌ **无文本**（仅图形图标，9 个空节点）→ 纯节点模式在手表桌面不可用，**需要视觉模式** |
| 滑动 | 设置"系统"页仅"重新启动"一项（无可滚内容，swipe 无变化属正常）；命令返回一条 i18n 权限 I/O 提示（不影响返回码） |

**手表端到端任务实测**：
- ✅ 「进入 WLAN 页面查看开关状态，然后返回设置主页」3 步完成（tap WLAN → 读状态"已关闭" → back → 真实返回）
- ⚠️ 「进入系统页面，看看有哪些选项并告诉我，然后返回」失败（该页只有"重新启动"一项，模型无"看完"信号 → 卡死）。
  结论：**信息型任务的完成判定需在 prompt 里明确"读完即 done"**，否则模型会找不到终止条件。

**下一步（手表方向）**：① 视觉模式（截图 → 多模态模型，解决表盘无文本）；② 手表应用启动映射表（bundle+ability 白名单）；
③ 信息型任务的终止条件提示。

---

## 8. 鸿蒙手机标准任务集实测（2026-09-12）

`tests/harmony_task.py`（对标 Android 侧 `watch_task.py` 的鸿蒙版）：

```bash
export HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
python3 tests/harmony_task.py 127.0.0.1:5555          # 全部 8 条
```

| # | 任务 | 结果 | 耗时 | 考察点 |
|---|---|---|---|---|
| 1 | 打开设置 | ✅ | 3.1s | 基础导航 |
| 2 | 在当前页面开启全天深色模式 | ✅ | 5.0s | 状态切换（Toggle 本体 + 状态复核） |
| 3 | 进入 WLAN 页面查看开关状态，然后返回上一页 | ✅ | 96.9s | 多级导航 + 状态读取 + 返回 |
| 4 | 打开设置里的系统页面，然后返回设置主页 | ✅ | 19.4s | 子页导航 + 返回 |
| 5 | 找到并打开关于本机（需滚动），然后返回 | ❌ | 229.2s | 滚动查找（**该模拟器无此入口**，agent 正确报告"滚动多次确认不存在"） |
| 6 | 回到桌面，然后打开图库 | ✅ | 20.0s | 跨应用 |
| 7 | 打开设置，然后回到桌面 | ✅ | 9.4s | 跨应用往返 |
| 8 | 查看当前界面可点设置项，列出前三个，然后返回 | ✅ | 42.1s | 信息读取（模型正确读出 #3/#5/#8 及坐标） |

**结论：7/8 通过**（唯一失败为任务目标在模拟器不存在，非能力缺陷）。

### 本轮手机适配的关键修复（均由实机踩坑驱动）

1. **开关状态感知**（最关键）：感知层原不输出 `checked/selected` → 模型切换开关后无法判断成功，
   反复点同一节点直至步数耗尽（甚至实际已完成却报 fail）。现输出 `【可点·Toggle·未选中】` 标记。
2. **可点性约束**：模型曾反复点击不可点的标题文本（如 `'深色模式' Text`）→ 现 prompt 明确
   "只有【可点】节点能被 tap"，且本地拦截不可点点击（不发设备，直接纠正）。
3. **开关本体规则**：实机验证「点 Row 整行**不生效**，必须点 Toggle 本体」→ 写入守则第 8 条。
4. **状态复核规则**：改状态类操作必须复核（守则第 9 条），修掉幻觉 done。
