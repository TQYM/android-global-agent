# tools/harmony-agent — HarmonyOS NEXT HDC 外挂式 Agent（T8 原型）

> 规划书档 2：NEXT（HarmonyOS 5.0+）不兼容 APK、三方无障碍权限不开放（T9 评估已复核），
> 设备端 App 路线不可行 → PC 侧 Agent + HDC 调试通道驱动。
> 路线已有业界先例：Open-AutoGLM PR#221（鸿蒙 HDC 驱动）、codeskyblue/uiautodev HarmonyOS Driver。

```text
PC (Mac/Win) ── hdc (USB/WiFi) ──> 鸿蒙 NEXT 设备
   │  Python（零第三方依赖）
   ├─ probe.py            真机首日命令可用性探测（先跑它！）
   ├─ hdc.py              HDC 命令封装层（命令名集中一处，探测后修正）
   └─ harmony_agent.py    决策循环：dumpLayout 节点表 → LLM → uiInput 注入 → 复验
```

## 环境准备

1. 安装 DevEco Studio / Command Line Tools，`hdc` 加入 PATH（或 `export HDC=/path/to/hdc`）；
2. NEXT 真机：设置 → 关于手机 → 连点版本号开开发者模式 → USB 调试开启 → 连接电脑；
3. 验证：`hdc list targets` 能看到设备。

## 首日流程（规划 R2 止损机制）

```bash
# ① 命令探测（约 1 分钟，出可用性表）
python3 tools/harmony-agent/probe.py --serial <SN>
python3 tools/harmony-agent/probe.py --serial <SN> --inject   # 追加注入测试（会真实点击 10,10）

# ② 探测通过后跑任务（OpenAI 兼容端点）
AGENT_API_KEY=sk-xxx python3 tools/harmony-agent/harmony_agent.py \
    --task "打开设置，搜索蓝牙" --serial <SN> --model qwen3.5-flash
```

`dumpLayout` / `uiInput` / 截图任一探测失败 → 对照 `hdc.py` 修正命令名后重探；仍不通按规划止损转"只读诊断工具"定位。

## 已知坑（真机核实清单）

| 项 | 现状 | 处置 |
|---|---|---|
| `uitest uiInput text` 中文 | 社区报错率高 | `hdc.py` 集中处理；降级链：直注 → 英文/拼音 → fail 请用户手动 |
| `dumpLayout` 输出结构 | 键名版本差异（text/clickable/bounds） | `hdc.walk_nodes` 多键名兼容，探测后固化 |
| 返回/主页键码（2050/2049） | 待核实 | probe --inject 验证 |
| 截图命令名（snapshot_display/snapshot） | 待核实 | probe 验证，`hdc.screenshot` 双命令回退 |
| 控件树需 daemon 运行 | ArkXTest 文档提示 | 失败时按错误信息启动 uitest daemon（首日探测时研究） |

## 验收门槛（规划 §9-C）

HDC 原型完成「打开设置 → 搜索 → 返回」端到端，日志留档；命令集可用性表（probe-report.json）完整。

## 文件

- `probe.py` — 命令可用性探测，输出 `probe-report.json`
- `hdc.py` — HDC 封装（dump_layout/collect_nodes/click/swipe/input_text/keyevent/screenshot/screen_size）
- `harmony_agent.py` — LLM 决策循环（SCHEMA 复用 agent-lite：tap#index + px/py + swipe + text + done/fail）
