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

## 已知边界

- `Settings.ACTION_NOTIFICATION_SETTINGS` 无公开常量，用字面 action 字符串
- 无 root 下 WiFi/蓝牙只能弹系统面板而非静默开关
- ColorOS 截图限频：已内置间隔+重试+降级，极端场景视觉会自动降级为纯节点
- 与旧版 agentd 栈的无障碍服务互斥（rebind 会互相覆盖 enabled 列表），二选一使用
