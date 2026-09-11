# 鸿蒙（HarmonyOS NEXT）适配环境搭建与验证指南

> 目标：在**本机无鸿蒙真机**的情况下，用官方模拟器（虚拟机）跑通 `tools/harmony-agent/` 外挂式 Agent。
> 当前状态（2026-09-11）：**代码已就绪并通过 mock 全链路验证**；模拟器环境需按本文 §1 安装。

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

## 4. 已知坑与处置（probe 会逐条暴露）

| 项 | 现象 | 处置 |
|---|---|---|
| `uitest uiInput text` 中文 | 报错/乱码 | `hdc.input_text` 已把非 ASCII 失败暴露给 LLM 换策略；必要时改剪贴板/逐字符 |
| `dumpLayout` 需 uitest daemon | 首次调用失败 | 按 stderr 提示启动 uitest 服务后重试 |
| 属性键名差异 | 解析不到节点 | `hdc.walk_nodes` 已兼容 `content-desc`/`text`/`clickable`（字符串 "true"/"false"）等 ArkXTest 形式 |
| 截图命令名 | `snapshot_display` 或 `snapshot` | `hdc.screenshot` 双命令回退 |
| 返回/主页键码 | 2050/2049 待核实 | `probe.py --inject` 实测确认 |

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
