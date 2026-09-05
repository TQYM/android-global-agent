# 全安卓兼容性指南

本项目（agent-client + 缝合键盘方案）面向**所有 Android 11+ 设备**（API 30+，覆盖现役约 94% 设备）。
核心能力全部跑在标准 Android API 上，无任何厂商私有接口。

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
| HarmonyOS / 荣耀 | a11y API 兼容；华为无 GMS 不影响（不依赖 GMS） |
| 三星 OneUI | 全功能；侧屏幕手势可能与 edge_back 冲突，三键导航下用 key 4 |

## 键盘适配策略（全设备通用）

不绑定任何特定键盘：
1. 无障碍直写覆盖大多数场景
2. 缝合 FlorisBoard（可选增强）：开源键盘内嵌注入桥，任何设备装同一个 APK 即可
3. 剪贴板+长按粘贴：终极兜底，与键盘型号完全无关

## 不通用、有意排除的

- Android 10 及以下（a11y 截图 API 不存在；如需支持可关视觉模式，minSdk 可降至 26，代价是失去视觉感知）
- Google Play 分发：QUERY_ALL_PACKAGES 与无障碍自动化属受限权限，本项目定位侧载/自用
