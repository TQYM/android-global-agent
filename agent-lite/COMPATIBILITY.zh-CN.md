# 全安卓兼容性指南

本项目（agent-client + 缝合键盘方案）面向**所有 Android 11+ 设备**（API 30+，覆盖现役约 94% 设备）。
核心能力全部跑在标准 Android API 上，无任何厂商私有接口。

## 沙盒虚拟屏（root 限定）

沙盒把目标 App 关进一块独立虚拟屏：Agent 在后台操作，你的真屏完全不受影响。

**为什么必须 root（ColorOS 实测结论）**：普通应用或 MediaProjection 建的虚拟屏，
ColorOS 会把其中的任务"organize"（投射）到物理屏——`canHostTasks=false`，
App 一启动就跳上你的真屏。只有 root 进程 + `TRUSTED`(1024) + `OWN_DISPLAY_GROUP`(2048)
标志能建出真正隔离的虚拟屏。因此沙盒开关在检测到无 root 时会直接拒绝并提示。

实现：`assets/vd_daemon.jar`（源码 `vd-daemon/VdMain.java`）以 app_process 守护进程
运行，持有虚拟屏；守护进程检测到宿主 App 退出后自动销毁，不会泄漏。
截屏走 `screencap -d <SF-id>`，触控走 `input -d <displayId>`，起应用走
`am start --display`，全部经 root shell。

已知边界：
- 正在真屏前台运行的单实例应用无法被拉进沙盒（引擎会明确报错，不会乱动真屏）；
- 系统级弹窗（权限请求等）仍可能出现在真屏。

## 能力 × API 级别矩阵

| 能力 | 实现 | 最低版本 | 备注 |
|---|---|---|---|
| 点击/滑动/长按 | AccessibilityService.dispatchGesture | API 24 | 全安卓通用 |
| 节点感知 | getRootInActiveWindow | API 14 | 全安卓通用 |
| 截图（视觉模式） | a11y takeScreenshot | API 30 | 部分 ROM 限频（见下表），自动降级纯节点 |
| 文字输入 | ACTION_SET_TEXT / 广播注入 / 剪贴板粘贴 | API 18+ | 三层自适应，见 README |
| 设置直达 | Settings.ACTION_* | API 1+ | 原生 AOSP action，全通用 |
| WiFi/蓝牙面板 | Settings.Panel | API 29 | 低于 29 自动不可达（minSdk 30 已无此问题） |
| 亮度/音量 | WRITE_SETTINGS / AudioManager | API 23+ | 亮度需用户授权一次 |
| 全局按键 | performGlobalAction | API 16 | 全安卓通用 |
| 语音输入 | AudioRecord 16k WAV → ASR | API 23+ | 运行时权限 |

## 无 root 装机（任何手机）

```bash
# 1. 装 APK 后手动开无障碍：App 内「设置」按钮直达系统无障碍页
# 2. 增强权限（可选，用一次 adb 即可，无需常驻 root）：
adb shell pm grant com.dsh.agent android.permission.WRITE_SECURE_SETTINGS   # 输入法自动借用/归还
adb shell appops set com.dsh.agent WRITE_CLIPBOARD allow                     # 剪贴板注入层
```

不开这两个也能用：文字注入退化为「无障碍写入 + 剪贴板手动粘贴引导」。

## ROM 差异表（实测/已知）

| ROM | 要点 |
|---|---|
| 原生/Pixel | 全功能开箱即用，截图不限频 |
| ColorOS / 一加 / OPPO | 截图限频 ≈1 张/秒（已自动间隔+重试）；剪贴板写入默认拒绝（appops 放行）；部分应用屏蔽节点 → 纯视觉模式自动接管 |
| MIUI / 红米 | 剪贴板同样有门控（权限管理→剪贴板）；后台限制较强 → 保活服务已内置 |
| OriginOS / vivo | 无障碍偶发被系统自动关闭 → App 首页有状态条提示重新开启 |
| HarmonyOS / 荣耀 | a11y API 兼容；华为无 GMS 不影响（不依赖 GMS）——鸿蒙细分见下方「鸿蒙分档」 |
| 三星 OneUI | 全功能；侧屏幕手势可能与 edge_back 冲突，三键导航下用 key 4 |

## 屏幕形态矩阵（异形屏适配，2026-09-11 新增）

agent-lite 内置 `DisplaySpec` 屏幕形态采集（零 root，基于 WindowMetrics/DisplayCutout），
感知每步把「挖孔位置、曲面禁触区、状态栏/导航区、安全触控区」注入模型提示，
手势落点自动 clamp 进安全区（挖孔/曲面边缘/系统栏点击会被向内修正，避免被系统吞掉）。

| 屏幕形态 | 采集 | 行为 | 状态 |
|---|---|---|---|
| 直板无挖孔（矩形） | 全字段为 0/默认 | 与旧版完全一致，注入/clamp 退化为空操作 | ✅ 实测（荣耀平板） |
| 中置/左/右挖孔、药丸、刘海 | cutout rects 精确采集 | 挖孔区触点自动下移回避；提示词标注挖孔位置与「该区域触摸无效」 | ✅ 已实现，待真机矩阵回归 |
| 瀑布/曲面屏 | WaterfallInsets（API 30 公开通道 DisplayCutout.getWaterfallInsets） | 左右禁触区落点向内 clamp；提示词标注禁触区宽度 | ✅ 已实现，待曲面真机验证 |
| **圆形表盘（手表）** | `Configuration.isScreenRound()`（公开 API 23+） | safe 取内接正方形；clampTouch 专用径向修正（超出内切圆留 12dp 沿半径拉回，圆上下窄条保留可点）；提示词注入「四角无显示、px/py 禁止四角与边缘」 | ✅ **实测（OPPO Watch OWW261，466×466@320dpi，Android 11）**：spec=圆形 R=233、safe [69,69,397,397]、单测 7/7、端到端任务跑通（圆形提示正确注入 LLM） |
| 手势导航 | navBar 高度 + framework 资源探测 | 底部保底 24dp 手势区不注入手势 | ✅ 已实现 |
| 三键导航 | navBar insets | 底部导航区不注入手势 | ✅ 已实现 |
| 分屏/小窗 | 最上层应用窗口 bounds 实时记录 | 提示词注入窗口区域（左/右/上/下半），px/py 按全屏计 | ✅ 已实现，待平板分屏实测 |
| 旋转（横竖切换） | 形态指纹变化检测 | 旧截图/旧节点编号作废告警，强制重新观察 | ✅ 已实现，待实测 |
| 多显示（外接/折叠外屏） | getWindowsOnAllDisplays 统计 | 明确只操作主屏，其他屏报「不支持」而非乱点 | ✅ 已实现 |
| 折叠屏动态切换 | — | 本期不做（规划 P2 观察项） | ⏸ 规划中 |

**手表（Wear 类设备）UI 适配**（极简主页版 commit 8211f68；2026-09-11 实测）：
- **形态自动切换**：`isWatchUi()` = `UI_MODE_TYPE_WATCH` ∥ `isScreenRound()` ∥ 最小边<320dp → 使用 `activity_main_watch.xml`（手机/平板不受影响，走原布局 `activity_main.xml`，已回归验证）
- **主页极简（圆/方两档）**：仅 6 个控件——状态文字、语音大钮、设置入口、打字输入框、运行按钮。
  日志、截图、历史、记忆、省电、授权**均不在主页**（日志与截图手表端不提供，adb logcat / engine.log 可查）
- **设置页分离**（`WatchSettingsActivity` + `activity_watch_settings.xml`）：模型配置、历史记录、
  全局记忆、省电策略、预授权管理、权限与授权（无障碍直达/电池白名单）、设备形态与校准
- **圆形表盘档**（`values-round/dimens.xml`，OWW261 466×466@320dpi 实测）：
  - 几何依据：R=233px，内接方形半边 164.7px → 水平边距 24dp
  - 实测入圆：语音钮 [113,72][241,200] 完全在圆内 ✓、设置钮 [261,90][353,182] 完全在圆内 ✓、
    输入框 [48,212][418,280] 完全在圆内 ✓、运行钮仅 1px 角裁（中心可点）✓
  - 设计要点：语音钮与设置钮同排放在**圆最宽处**（避免右上角圆弧裁切）；状态文字居中窄条
- **方形表档**（`values/dimens.xml`）：边距收窄至 8dp/10dp、语音钮 72dp，全部控件可用
- 滚动内容（设置页）经过顶部/底部弧区时部分超圆——滚动视口固有特性，滚至中央即完全入圆

**手表已知限制 / 运维要点**：
- adb 起任务：`am start -n com.dsh.agentlite/.MainActivity --es task <t> --ez auto_run true`（任务文本避免空格，adb shell 会吃掉引号）
- OPPO 桌面不提供 a11y 节点树（感知 0 节点）→ 纯视觉模式自动接管（正常工作）
- force-stop 后绑定丢失且 stopped 阻止自启：先 `am start` 清 stopped，再 `settings delete` + `settings put`
- 反复 force-stop/toggle 后 OPPO 框架可能进入 Crashed 且无法再绑定（无 Java 异常栈）→ **卸载重装即恢复**（0cd53b9 期间实测）
- OPPO Wear ROM 上手机布局的 etTask 实测宽 42px（weight 行测量怪癖）→ 手表专用布局已绕过该问题

**回滚开关**（SharedPreferences，默认开）：`spec_inject`（形态注入）、`spec_clamp`（落点 clamp），
关掉即回到 2026-09-06 行为。设备形态可在 App 配置页「设备形态」卡实时核对，「坐标校准」
按钮执行根窗口 bounds + 节点越界自检（诊断厂商 ROM 坐标偏移用）。

## 鸿蒙分档（2026-09-11 更新）

| 档位 | 系统 | APK 支持 | Agent 路线 | 状态 |
|---|---|---|---|---|
| 兼容层 | HarmonyOS 2.0–4.x | ✅ 可直装 | 与 Android 同链路，需逐项验证（无障碍保活/截图/手势/输入/色彩空间） | 🔶 待真机验证（规划 M3） |
| NEXT 纯血 | HarmonyOS 5.0+ | ❌ 不兼容 APK | 三方无障碍权限不开放 → 设备端 App 不可行；改为 HDC 外挂式（`uitest dumpLayout/uiInput` + 截图，PC 侧 Agent） | 🔶 原型规划中（规划 M4） |
| OpenHarmony | 各发行版 | ❌ | 本期不做 | — |

详见 [异形屏与鸿蒙适配规划书](../docs/PLAN_DISPLAY_HARMONY_2026-09-11.zh-CN.md)。
兼容层真机到位后按七项清单验证并回填本表；HDC 命令集可用性以真机为准。

## Root = 可选加速器（有则自动启用，无则全功能可用）

检测到 su 可用时自动升级以下路径（KernelSU/Magisk 首次会弹一次授权）：

| 路径 | 零 root | 有 root |
|---|---|---|
| 截图 | takeScreenshot，ColorOS 限频 ~1 张/秒 | `screencap` 直读，无限频，视觉任务快 2-3 倍 |
| 手势 | dispatchGesture（异步、可被取消） | `input tap/swipe` 同步直达 |
| 剪贴板粘贴 | 长按弹菜单 + 模型点「粘贴」 | `input keyevent 279` 直接粘贴，确定性完成 |
| 权限配置 | 需 adb 授权一次 | 运行时自给自足 |

root 不能解决的：应用级无障碍节点屏蔽（如微信）只能视觉兜底，这与 root 无关。

## 键盘适配策略（全设备通用）

不绑定任何特定键盘：
1. 无障碍直写覆盖大多数场景
2. 缝合 FlorisBoard（可选增强）：开源键盘内嵌注入桥，任何设备装同一个 APK 即可
3. 剪贴板+长按粘贴：终极兜底，与键盘型号完全无关

## 不通用、有意排除的

- Android 10 及以下（a11y 截图 API 不存在；如需支持可关视觉模式，minSdk 可降至 26，代价是失去视觉感知）
- Google Play 分发：QUERY_ALL_PACKAGES 与无障碍自动化属受限权限，本项目定位侧载/自用
