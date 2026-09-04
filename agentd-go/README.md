# agentd — 设备端常驻 Android Agent（Go 单二进制）

不连电脑的本地 Agent 模块：以 root 常驻手机，内置 WebUI（手机浏览器打开
`http://127.0.0.1:8080`），用户自定义填写 LLM API（默认智谱 GLM，兼容
OpenAI 风格网关），通过 root 驱动「语义感知 → LLM 决策 → 注入执行 → 复验」
循环完成自然语言任务。

## 架构

```text
手机浏览器 ── http://127.0.0.1:8080 ──> agentd (root 常驻进程)
                                         │
        ┌────────────────────────────────┼───────────────┐
        ▼                                ▼               ▼
  uiautomator dump                  input tap/swipe   curl → LLM API
  (语义节点表)                       /key/text/app     (GLM / OpenAI 兼容)
  screencap (截图给 WebUI)
```

- **感知**：`uiautomator dump` → 可交互节点表（文本/坐标/可点击性）
- **决策**：LLM 每步输出一个 JSON 动作（tap/swipe/scroll/key/text/app/back/home/done）
- **执行**：root 直接 exec 系统命令；启动应用用 `cmd package resolve-activity` + `am start`
- **状态**：配置/日志/截图/节点表经 REST API 暴露给 WebUI

## 构建（交叉编译）

```sh
GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
  go build -ldflags="-s -w" -o build/agentd .
```

## 部署

```sh
adb push build/agentd /data/local/tmp/agentd_bin
adb shell "su -c 'mkdir -p /data/local/tmp/agentd && \
  mv /data/local/tmp/agentd_bin /data/local/tmp/agentd/agentd && \
  chmod 755 /data/local/tmp/agentd/agentd && \
  nohup /data/local/tmp/agentd/agentd </dev/null \
  > /data/local/tmp/agentd/agentd.log 2>&1 &'"
```

数据目录：`/data/local/tmp/agentd/`（config.json / ui.xml / screen.png / 日志）。

**agentd-apk 防冻结（ColorOS 必做）**：无障碍服务进程空闲约半小时后会被
ColorOS 应用速冻（`do_freezer_trap`）挂起，8081 随之失联。部署后执行：

```sh
adb shell "su -c 'dumpsys deviceidle whitelist +com.dsh.agentd && \
  am set-standby-bucket com.dsh.agentd active'"
```

**注意**：白名单挡不住 ColorOS 自己的应用速冻（约 15 分钟空闲后仍会冻）。
agentd 任务中发现 8081 不可达会**自动强制重绑**（移除再写回服务条目），
无需人工干预；手动重绑命令：`settings put secure
enabled_accessibility_services ""` 后再写回 `com.dsh.agentd/.AgentA11yService`。

## 使用

1. 手机浏览器打开 `http://127.0.0.1:8080`
2. 「LLM API 配置」填 Base URL / API Key / 模型 → 保存
   - 智谱：`https://open.bigmodel.cn/api/paas/v4`，模型如 `glm-4.6`
   - 其他 OpenAI 兼容网关：Base URL 填到 `/v1` 前缀
3. 输入任务 → 运行；也可以点「🎤 语音输入」说话（录 16kHz WAV 走
   `/api/asr` → 智谱 glm-asr 识别，识别模型可在配置里改）
4. 实时看日志 / 屏幕截图 / 语义节点表；agent 每次点击会在截图上显示
   蓝色脉冲标记；「测试感知」可独立自检感知通道

### 动作空间（v2）

- **直达（小布/小爱式，优先）**：`setting` 直达设置页（wifi/bluetooth/
  display/apps/battery…21 个页面）、`app` 启动应用、`open_url` 打开
  scheme/链接（alipay:// weixin:// tel: https://）、`wifi`/`bluetooth`
  直接开关、`brightness`、`volume`、`statusbar`（通知栏/快捷设置）、`wake`
- **界面操作（兜底）**：`tap`（优先按节点 index）、`longpress`、`swipe`、
  `scroll`、`key`、`text`（中文走 a11y）、`wait`、`back`/`home`、`done`
- 任务期间**不再关闭系统动画**；点击后自适应等待页面变化（通常 ~0.5s，
  上限 1.6s），a11y 服务被 ColorOS 冻结时自动强制重绑恢复

## 已知问题（OnePlus 13T / ColorOS 16 实测）

- **ColorOS 会静默掐断 uiautomator 的 a11y 桥**：报
  `null root node returned by UiTestAutomationBridge`（stderr 报错但退出码
  可能为 0 且不落盘，判定必须查文件内容）。锁屏/解锁、pkill 均无法恢复，
  **重启手机恢复**。疑似触发条件：屏幕转场动画期间执行 dump。agentd 已内置
  防护：首选 a11y 服务通道（不经该桥）、null-root 时 4s 大退避、连续失败后
  进入快速盲模式（不再每步烧 20-50s 重试）。
- **ColorOS 桌面永远等不到 idle**（`could not get idle state`）：桌面无法
  dump 属预期；agentd 降级为「盲操作模式」，LLM 只用 app/key/back/home 等
  无坐标动作，进入应用后感知自然恢复。
- **Go 静态二进制在 Android 上无 /etc/resolv.conf**：DNS 失败时自动经系统
  解析器（ping）取 IP 钉住拨号，TLS SNI 仍用域名，证书校验不受影响。
- **陈旧 keep-alive 连接会挂到超时**：已禁用连接复用，每步请求新建连接。
- **TLS 根证书**：启动时把 Go 的 x509 指到 conscrypt APEX 的系统 CA 目录。

## 路线图

- [ ] KernelSU/Magisk 模块打包，开机自启
- [x] AccessibilityService APK 替代 uiautomator 做感知（根治 a11y 桥被掐）
      —— v1.1 起 APK 同时提供 `POST /settext`（ACTION_SET_TEXT 中文输入）
- [x] 视觉多模态：截图喂给 GLM-4V 系模型，语义+视觉双通道
      —— 配置里开 `vision`，截图降采样为 ≤768 宽 JPEG 随每步感知一起发送
- [ ] 任务队列与历史

## v1.1 变更（OnePlus 13T / ColorOS 16 实测通过）

- **agentd-apk 必须声明 `INTERNET` 权限**：Android 的 paranoid networking
  要求 INTERNET 才能绑定监听 socket，否则 ServerSocket 报 EPERM。
- **HTTP 体按字节读**：Content-Length 是字节数；char Reader 会在 CJK
  UTF-8 正文中饿死（字节≠字符），导致 settext 请求挂死。
- `text` 动作路由：优先 a11y `ACTION_SET_TEXT`（支持中文，替换内容），
  纯 ASCII 时回退 `input text`。
- 执行失败不再中止任务：错误反馈给 LLM 换路继续（例如包名猜错时改 tap 图标）。
- OEM 包名别名：gallery3d/photos → `com.coloros.gallery3d` 等。
