# FlorisBoard 缝合桥（Agent 注入通道内嵌进 FlorisBoard）

把 Agent 的文字注入通道直接缝进 FlorisBoard APK：一个键盘 = 日常输入（FlorisBoard 完整体验）
+ Agent 广播注入（`com.dsh.agent.IME_COMMIT`，extras: `text`/`replace`）。
运行时不再需要借用/切换输入法——合并键盘即默认输入法。

## 原理

1. `classes2.dex`：`dev.patrickgold.florisboard.agent.AgentImeBridge extends FlorisImeService`，
   onCreate 里注册运行时广播接收器（EXPORTED + 权限 `dev.patrickgold.florisboard.agent.INJECT`，
   该权限由 com.dsh.agent 声明持有）。
2. `AndroidManifest.xml` 二进制编辑：字符串池里把 IME 服务类名
   `dev.patrickgold.florisboard.FlorisImeService` 换成桥类全名（同索引替换，重建池块）。
3. `classes.dex` 手术：FlorisImeService 是 Kotlin final 类且 onCreate/onDestroy 也是 final——
   清掉类级 + 两个方法级的 ACC_FINAL 位，重写 dex 头 SHA-1/adler32。
   （坑：class_data 里 direct/virtual 两个方法列表的 method_idx_diff 各自独立累计。）
4. 重新打包（丢弃原签名 META-INF/*），zipalign + apksigner 签名。
   签名与原版/用户自签不同 → 需先卸载再装。

## 复现步骤（本会话实际执行）

- 桥源码：`AgentImeBridge.java`（stub：`FlorisImeService.java` 仅作编译垫片，不进 dex）
- javac --release 11 编译 → d8 出 classes2.dex
- Python 二进制补丁：清单池替换 + dex final 解锁（脚本见会话记录，勿用文本工具碰二进制）
- 装机：pm install → settings enabled/default_input_method = dev.patrickgold.florisboard/.agent.AgentImeBridge
- 验证：/sdcard/Android/data/dev.patrickgold.florisboard/files/bridge.log 出现 rx 记录

## 注意

- 指纹变动：任何 APK 重打包都会让系统视为"不同应用"，FlorisBoard 的备份/恢复工具认签名。
- FlorisBoard 内部以类名引用自身服务的地方（如"是否启用"自检）会对不上子类名，属装饰性小问题。
