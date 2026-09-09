# Android 14 Global Agent 工程审计与未来规划

审计日期：2026-08-18（Asia/Shanghai）
审计范围：C++ 可移植核心、AOSP 私有适配、Android Bridge/ModelGateway、SELinux/init、KernelSU 调试包、构建与测试脚本。
审计原则：先验证可复现事实，再按影响和可利用性分级；“未实现/未验收”不等同于漏洞，但在发布门禁中必须视为阻塞。

## 1. 执行摘要

当前仓库适合作为“安全边界脚手架”和合同测试仓库，不应被标记为可在真实设备上自动执行用户操作的生产版本。

- **本地可验证部分：通过。** 在新建构建目录中，C++20 host 构建通过 ASan/UBSan，1/1 单元测试通过；AIDL/JVM API 34/35/36 边界测试通过；静态安全门、XML、Shell 语法检查通过。
- **生产能力仍关闭。** `main_aosp.cpp` 使用 `NoopDecision`，protocol v2 全部返回 `UNSUPPORTED`；尚无完整 AOSP Soong 构建和目标设备验收，因此私有截图、平台输入、SELinux、Binder death 和 OEM 行为不能宣称已验证。
- **最高优先级不是增加模型能力，而是先收紧网络/配置边界、补状态恢复语义校验、修复超时进程清理，并建立可复现构建门禁。**

## 2. 验证记录

| 检查 | 结果 | 备注 |
| --- | --- | --- |
| 新目录 CMake + ASan/UBSan + CTest | 通过 | `build/audit-host`，1/1 测试通过 |
| AIDL/JVM boundary stub | 通过 | API 34 arm64；Java policy tests 全通过 |
| `tools/check-project.sh` | 通过 | exact AOSP tree 检查未运行，输出明确标记 `not-run` |
| `sh -n tools/*.sh deploy/magisk/*.sh` | 通过 | Shell 语法无错误 |
| `tools/run-tests.sh` | **失败** | 现有 `build/host/CMakeCache.txt` 记录了已不存在的旧路径 `/Users/.../Desktop/项目/android agent` |
| 完整 Soong/AOSP + 真机 | **未完成** | 当前工作区不是可直接编译的目标平台环境 |

## 3. 发现项（按严重性）

### P0：发布阻塞

**P0-1 私有平台集成尚未形成可发布证据链**
位置：`src/platform/aosp/aosp_surface_capture.cpp:99-115`、`src/platform/aosp/main_aosp.cpp:25-30`、`src/platform/aosp/v2_platform_agent_service.cpp:38-118`。
当前依赖精确 AOSP/OEM 私有 ABI；生产决策为 `NoopDecision`，v2 服务固定返回 `UNSUPPORTED`。没有 API 34/36 exact tree、完整 Soong、匹配 platform key/SELinux 镜像和目标设备回归前，不能证明普通界面截图、旋转、多 display、Binder death、输入取消和安全界面行为。

**P0-2 安全内容与用户确认的设备验收缺失**
文档声明 `FLAG_SECURE`/DRM 不应被捕获，但目前只有代码路径和 host/stub 证据；没有目标 OEM 上的黑帧/拒绝测试、锁屏/Keyguard、全局麦克风关闭、AppOps、SystemUI 重启测试。任何“能工作”的 demo 结果都不能替代这些负向验收。

### P1：在启用真实网络或自动执行前必须修复

**P1-1 endpoint 校验允许回环、链路本地和私网目标（潜在 SSRF/错误路由）**
位置：`android/model-gateway/src/com/example/globalagent/gateway/ModelGatewayPolicy.java:22-37`。
校验只限制 `https`、端口和 URI 语法，没有拒绝 `localhost`、`127.0.0.1`、IPv6 loopback、RFC1918/链路本地、云元数据地址，也没有 provider host allowlist。当前没有 HTTP client，所以是“启用网络前的潜在漏洞”；一旦按配置发请求，root/shell 导入的配置可把请求导向本机或内网服务。修复方式：解析 DNS 后对每个地址做 IP 分类并拒绝内网/特殊网段；按 provider 固定 host allowlist；禁止重定向到未批准 host；记录并测试代理、IPv6 和 DNS rebinding。

**P1-2 ModelGateway 服务的调用方校验缺少包名/证书二次绑定**
位置：`android/model-gateway/src/com/example/globalagent/gateway/ModelGatewayService.java:82-87`。
`requireExternalBridgeCaller()` 仅排除本 UID、root、shell，实际授权主要依赖 Manifest 中由另一个 APK 声明的 signature permission。若产品合并、权限声明、shared UID 或安装顺序发生偏差，服务代码本身无法识别“是否为指定 Bridge 包”。应在服务首次调用及每次高风险方法调用中通过 `PackageManager` 验证 `Binder.getCallingUid()` 所属包、签名摘要和已批准版本；未通过时返回 `SecurityException`。同时增加“Bridge 未安装/权限定义缺失/同签名非目标包”设备测试。

**P1-3 公共配置导入接口是 root/shell 可写的发布面，必须做构建变体隔离**
位置：`android/model-gateway/AndroidManifest.xml:12-16`、`PublicConfigProvider.java:24-51`、`PublicConfigImporter.java:38-70`。
当前 provider `exported=true`，任何 root/shell 都可替换 endpoint、model、allowPackages 和预算。开发 AVD 这是有意设计，但若 APK 被带入 user/userdebug 生产镜像，就等于提供了一个高权限远程配置入口。应在 release 变体移除 provider 或改为不可导出，并由签名 UI/Keystore 流程写入；debug 变体需显式 build flag、设备属性和审计日志。

**P1-4 状态恢复缺少语义完整性校验**
位置：`src/state_graph.cpp:212-271`。
反序列化验证 magic/version、数量、记录长度和 `current_node` 存在，但没有验证：节点/边 ID 非零、边两端节点存在、枚举值合法、时间/置信度范围、节点字段间一致性。状态文件虽应 root-only，仍可能因升级、部分损坏或测试工具写入而形成“CRC 正确但语义非法”的状态，未来决策引擎可能据此执行错误动作。应增加 `ValidateGraphInvariants()`，失败时丢弃该代并回退另一代；对未知 enum、悬空边、重复 node ID、异常时间戳做负向测试。

**P1-5 调试 KernelSU WebUI 具备 root 截图和输入能力，必须阻止误发布**
位置：`deploy/magisk/webroot/app.js:255-319`、`deploy/magisk/action.sh:8-15`。
页面通过 KernelSU bridge 执行 `screencap` 和 `input touchscreen tap`。代码已做坐标范围和路径约束，但这仍是 root 级设备操作面，不应与生产 daemon/模型包同渠道发布。应将该模块标记为 debug-only，加入签名/版本/设备属性门禁，发布 CI 检查产物不得包含 WebUI 和 root shell 命令，并在操作日志中记录操作者确认和设备序列号哈希。

### P2：应在 P1 后处理的可靠性与质量问题

**P2-1 `tools/run-tests.sh` 不具备干净环境可复现性**
位置：`tools/run-tests.sh:9-14`。
脚本固定复用 `build/host`，旧 CMake cache 中的绝对 source path 会导致整个脚本失败。应支持 `BUILD_DIR`/临时构建目录，或在 source path 不匹配时自动新建目录；CI 不应依赖工作区历史产物。

**P2-2 子进程超时只杀直接子进程**
位置：`src/subprocess.cpp:47-69`、`src/subprocess.cpp:85-105`。
超时路径调用 `kill(pid, SIGKILL)`，若诊断程序创建孙进程，孙进程仍可持有 stdout/stderr、继续消耗 CPU 或访问资源。应让子进程成为独立 process group，并在超时/错误时杀整个 group；补充 fork-child 负向测试。

**P2-3 截图 hash 路径在 deadline 后仍可能处理大 buffer**
位置：`src/platform/aosp/aosp_surface_capture.cpp:157-191`。
回调和 fence 有预算，但 lock、16K 维度检查和采样 hash 后没有再次检查 deadline；异常大 stride/分辨率可能把单步延迟推过 200 ms。应限制 stride/总字节数，分块采样并在关键阶段重新检查 deadline，超时立即 unlock/失败。

**P2-4 诊断与状态日志的隐私/取证策略尚未统一**
位置：`src/platform/aosp/main_aosp.cpp:69-73`、`deploy/magisk/webroot/app.js:81-92`。
当前错误输出直接写 stderr/WebUI terminal。未来接入 transcript、OCR、模型请求后，必须使用安全错误码、随机 request ID 和截断 hash，禁止输出原始文本、截图路径、credentialRef 或完整 provider response。

**P2-5 边界测试偏重纯函数，缺少真实 Binder/生命周期测试**
已有测试覆盖 UID/SID 解析、DTO 和状态策略，但尚未覆盖真实 Binder 调用中的调用方 UID 变化、持有 Binder 后重装/换用户、callback 阻塞、daemon 重启、服务权限缺失和 provider 导出错误。应把这些纳入 API 34/35/36 Enforcing AVD 的黑盒测试。

## 4. 代码质量分区

| 分区 | 评价 | 依据 |
| --- | --- | --- |
| 可移植 C++ 核心 | **B+** | 边界常量集中、无异常裸指针 ownership、CRC/mmap/并发 grant 有测试；但恢复语义校验和超时进程组仍不足 |
| AOSP 私有适配 | **C（未验收）** | API 依赖和安全注释清楚；缺 exact-tree/设备证据，不能按生产质量评分 |
| Android Bridge | **B** | UID+SELinux SID、revision、Binder death 和输入结构校验较完整；缺真实包/证书绑定与设备生命周期黑盒测试 |
| ModelGateway | **C+** | 严格 JSON、原子写入、最小权限方向正确；endpoint/调用方二次授权和 release 配置隔离不足 |
| Shell/WebUI | **C** | 路径和输入有基本约束、离线资源完整；root 调试能力过强，构建脚本复现性不足 |
| 测试与发布工程 | **C+** | host sanitizer、JVM matrix、静态门禁齐全；完整 Soong/device gate 缺失，默认脚本受旧 cache 影响 |

## 5. 建议的未来规划

### G0：审计修复与发布门禁（1 个迭代）

1. 修复 endpoint 私网/回环/重定向策略，增加 host allowlist 和 DNS rebinding 测试。
2. 为 ModelGateway 增加指定包+证书+UID 校验；debug provider 与 release 变体分离。
3. 增加 graph 语义校验、双代回退测试；修复 subprocess process-group kill。
4. 让 `run-tests.sh` 使用可配置/干净 build dir；CI 从空工作区开始执行。
5. 建立 SBOM、许可证、依赖 CVE 扫描和 secret scan；所有失败默认 fail-closed。

### G1：精确平台集成（API 34/35/36）

1. 为每个目标 tag 记录 source commit、framework/private header hash、platform key、设备 fingerprint/SPL。
2. 在 x86_64 Linux 完成 Soong 构建，部署匹配镜像；验证 service_contexts、seapp_contexts、MLS、Binder SID。
3. 通过 AVD 与至少一台目标 OEM 设备完成普通截图、旋转、多 display、输入取消、SurfaceFlinger/daemon 重启和锁屏负向测试。
4. 设定 P50/P95/P99：截图、map/hash、输入注入、Binder callback、端到端 200 ms/2 s 预算及功耗。

### G2：受控感知与模型边界

1. 先实现 RGBA/ROI/OCR 的脱敏 DTO，不传原始截图；通知、键盘、OTP、账号、支付和安全页面默认拒绝。
2. 仅接入 mock HTTPS server，验证超时、重试=0/1、预算、证书错误、重定向、响应 schema 和取消。
3. 完成 provider/区域/DPA/留存/训练政策双审批后，才实现 Keystore UI 和真实网络 adapter。
4. 所有模型输出必须经过本地 allowlist、焦点/session/revision、风险等级、用户确认和执行后验证；不允许模型直接产生输入事件。

### G3：有限执行与运营

1. 将执行能力限制为可逆、低风险、明确 allowlist 的动作；默认 dry-run，显式确认后单次 `ExecutionGrant`。
2. 建立 crash/restart、Binder death、stale grant、并发 replay、时钟回拨和存储损坏的故障注入测试。
3. 发布前生成设备级验收报告；任一 unresolved 安全、合规、性能或 exact-tree 项目都阻止 production 标记。

## 6. 发布判定

在 G0/G1 完成前，版本标签只能是 **scaffold / debug / device-validation**。只有同时满足以下条件，才可进入受限 production beta：

- exact AOSP/OEM tree 构建和签名可复现；
- API 34/35/36 及目标 OEM 的正向、负向、安全界面测试均通过；
- 网络 endpoint、凭据、日志、截图保留和数据出境策略获得批准；
- v2 capability、grant、plan、execution、verification 全链路 fail-closed；
- 关键指标达到 P95/P99 预算，且 root 调试 WebUI 不在 production 产物中。
