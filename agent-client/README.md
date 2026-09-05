# agent-client —— 零 root 原生 Agent 客户端

原生 Android 客户端（纯 Java + 框架 API，无 Gradle、无第三方依赖），
**全部能力仅靠无障碍服务实现，不需要 root**。

## 能力映射（零 root 实现）

| 能力 | 实现 | 备注 |
|---|---|---|
| 感知语义节点 | a11y `getRootInActiveWindow` | 编号节点表供模型 index 点击 |
| 点击/长按/滑动 | a11y `dispatchGesture` | 坐标自动钳制到屏幕范围 |
| 中文输入 | a11y `ACTION_SET_TEXT` | 替换/追加，支持 Unicode |
| 截图（视觉） | a11y `takeScreenshot`（API 30+） | 自动限频间隔 ≥1.1s，失败重试，连续失败降级纯节点模式 |
| 返回/主页/最近/通知栏/快捷设置/锁屏/电源菜单 | `performGlobalAction` | |
| 边缘手势返回 | `edge_back` 动作：左/右缘向内滑动 | 返回上级三策略：key 4 → tap 左上角返回节点 → edge_back，提示词内置按序尝试 |
| 启动应用 | `getLaunchIntentForPackage` + OEM 别名 | manifest 需 `QUERY_ALL_PACKAGES`（Android 11+ 包可见性） |
| 设置直达 | `Settings.ACTION_*` ×21 | WiFi/蓝牙/显示/定位/电池… |
| WiFi/蓝牙开关 | `Settings.Panel.ACTION_WIFI` 系统面板 | 无 root 无法硬开关，面板可一键切换 |
| 亮度 | `Settings.System` | 首次引导授予「修改系统设置」 |
| 音量 | `AudioManager` | |
| 亮屏 | `PowerManager` WAKE_LOCK | |
| 输入法通道 | Agent 键盘（无键盘 IME） | 应用屏蔽无障碍时的中文输入：ACTION_SET_TEXT 被拒自动回退到广播 commitText；设置里启用并切换一次即可 |
| 语音输入 | `AudioRecord` 16kHz PCM → WAV → `/audio/transcriptions` | 原生录音，无浏览器兼容问题 |
| 防速冻保活 | `KeepAliveService` 前台服务 | 对抗 ColorOS 应用速冻（老 WebUI 方案 8081 失联的根因） |

## 决策循环

感知（节点表 + 可选截图）→ LLM（OpenAI 兼容，bigmodel 自动关 thinking，max_tokens=300）
→ 单 JSON 动作 → 执行 → 自适应等待（指纹轮询 350ms–1.6s）→ 循环。
安全阀：同动作重复检测、屏幕 6 步无变化看门狗、最大步数上限。

## 界面

任务输入 + 🎤 语音（识别后自动运行）+ 运行/停止；屏幕回显带点击标记；
**动作调试台**：感知/截图/点中心/返回/主页/最近/通知栏/WiFi面板/音量+/输中文
——无 LLM 也可一键验证全部动作链路。

## 构建

```sh
cd agent-client && sh build-mac.sh   # 仅需 macOS + Android SDK cmdline tools
adb install -r build/agent-client.apk
```

开启无障碍：设置 → 无障碍 → Agent 无障碍服务（root 设备也可
`settings put secure enabled_accessibility_services com.dsh.agent/com.dsh.agent.AgentA11yService`）。

配置（Base URL / API Key / 模型 / ASR 模型 / 步数 / 视觉开关）在 App 内「设置」面板，
存于本地 SharedPreferences。

## 纯视觉模式（应用屏蔽无障碍时的兜底）

部分应用（实测：微信 @ ColorOS 16）对**所有**无障碍服务隐藏视图层级
（连 shell 权限的 uiautomator 都只能读到 1 个空节点），但 `takeScreenshot`
仍能拿到像素。引擎检测到「连续 2 步 0 节点 + 截图非黑屏」即自动进入纯视觉模式：
提示词改为比例坐标驱动（`tap px,py` / `swipe px1,py1,px2,py2`，0~1 相对屏幕宽高），
模型看截图估计目标位置。实测任务「打开微信朋友圈给最新一条点赞」10 步完成。
输入：纯视觉模式下 ACTION_SET_TEXT 不可用时自动回退 **Agent 键盘**（IME 广播 commitText），实测微信「文件传输助手」发中文消息全链路通过。

## 文字注入三层自适应（键盘无关）

| 层 | 机制 | 适用 | 依赖 |
|---|---|---|---|
| 1 | 无障碍 ACTION_SET_TEXT | 绝大多数 App | 节点可见 |
| 2 | 缝合 FlorisBoard 广播 | 节点被屏蔽（如微信） | 装了缝合键盘即可（**不在用会自动借用、任务结束归还**） |
| 3 | 剪贴板 + 长按粘贴 | 任何 App、**任何键盘** | 无（首次写入剪贴板 ColorOS 可能弹一次授权） |

层 3 流程：文字写入剪贴板 → 长按目标输入框 → 模型看截图点「粘贴」。
切换任何常用键盘（微信键盘/搜狗/百度/豆包/Gboard/FlorisBoard）都不影响层 1/3；
层 2 只是加速器。闭源键盘不做二进制缝合（许可 + 签名风险），FlorisBoard 是
Apache-2.0 开源，缝合版构建见 floris-merge/。

兼容性：全 Android 11+ 通用，无厂商私有 API——详见 [COMPATIBILITY.zh-CN.md](COMPATIBILITY.zh-CN.md)（含无 root 装机、各 ROM 差异表）。

## 已知边界

- `Settings.ACTION_NOTIFICATION_SETTINGS` 无公开常量，用字面 action 字符串
- 无 root 下 WiFi/蓝牙只能弹系统面板而非静默开关
- ColorOS 截图限频：已内置间隔+重试+降级，极端场景视觉会自动降级为纯节点
- 与旧版 agentd 栈的无障碍服务互斥（rebind 会互相覆盖 enabled 列表），二选一使用
- 微信等应用屏蔽无障碍节点树（ColorOS 16 实测）→ 自动纯视觉模式兜底；文字输入走 Agent 键盘 IME 通道
