# 充值改版设计：跳转百炼官方购买 → 自动创建 API Key

> 状态：**实现进行中**（2026-09-13 更新）。已完成 `AliyunV3Signer` + 桌面回归 43/43 + curl 打通脚本；
> `CreateApiKey` 的 host/path/version/返回字段已查实（§5.1）；仍卡在 §5.2 套餐映射，等用户提供。
> 目的：让下一轮可直接照此实现，不必重新推导。

## 1. 已确认的决策（用户 2026-09-13）

| # | 决策 | 含义 |
|---|---|---|
| 1 | **AK 只存手机本地**（方案 A） | 阿里云 AccessKey 永不上传服务器；`CreateApiKey` 的签名在客户端完成 |
| 2 | **不买 Token Plan** | 买**按量的 token 资源包**，不订阅个人/团队版套餐 |
| 3 | **用户为自己的百炼账号付费** | 额度在用户自己的阿里云账号里；不经我方 ECS 额度网关 |
| 4 | **本地隐藏充值 API** | 客户端不再暴露兑换码/订单/额度网关那套；`use_gateway` 对这批用户关闭 |
| 5 | **单独加"更改 API"入口** | 让用户能手填/替换 DashScope API Key（自动创建失败时的兜底） |

## 2. 目标用户流程

```text
App 选套餐
  → 打开百炼官方购买页（token 资源包，对应档位）
  → ★ 用户本人在浏览器完成支付（唯一的手动步骤）
  → 返回 App，自动检测返回
  → 客户端用本地 AK 调 Model Studio CreateApiKey
  → 拿到 API Key → 写入 Prefs.apiKey → 关闭网关模式
  → 发一次真实请求校验；失败则回滚并提示改用「更改 API」手填
```

对外表现：**前台只跳出付款界面，其余全自动**。

## 3. 关键技术事实（已查实）

- 官方流程是三步：订阅购买 → 获取 API Key → 配置工具
  （[Token Plan 个人版快速开始](https://help.aliyun.com/zh/model-studio/token-plan-personal-quick-start.md)）。
- **API Key 可由 OpenAPI 创建**：[`CreateApiKey`](https://api.aliyun.com/api/ModelStudio/2026-02-10/CreateApiKey?RegionId=cn-beijing)
  （Model Studio，版本 `2026-02-10`；Token Plan 另有 `CreateTokenPlanKey`，**本次不用**）。
- **签名是 ROA 风格 + V3（ACS3-HMAC-SHA256）**，不是经典 RPC HMAC-SHA1：
  见 [ROA-style request body and signature mechanism](https://help.aliyun.com/en/model-studio/roa-style-request-body-signature-mechanism)
  与 [V3 请求结构与签名](https://www.alibabacloud.com/help/ja/sdk/product-overview/v3-request-structure-and-signature)。
  → 客户端需实现 V3 签名链（HMAC-SHA256 逐级派生），**纯 Java 标准库可做，零依赖**。

## 4. 实现清单

### 客户端（`agent-lite/`）


| 文件 | 改动 |
|---|---|
| `AliyunV3Signer.java`（新） | ✅ **已完成**（2026-09-13）：V3 签名：规范化请求 → 待签串 → HMAC-SHA256。**不含任何 android.* API**，桌面 JVM 固定向量回归 43/43 通过 |
| `BailianClient.java`（新） | 调 `CreateApiKey`；解析 `apiKey.apiKeyValue`。⚠️ 必须校验 `Success==true`，不能只看 HTTP 200（见 §5.1 实测坑） |
| `AkStore.java`（新） | AK 本地存储。**加密存放**：密钥由 Android Keystore 生成（`KeyGenParameterSpec`），AK 密文写 SharedPreferences；**不落日志、不进网络**。⚠️ 还需补「首次录入 AK + 当场校验权限」入口（见 §5.4 缺口 3） |
| `PurchaseActivity.java` | 改为：套餐 → 跳官方购买页；返回后走「创建 Key → 写入 → 校验」；移除兑换码/订单 UI |
| `SettingsActivity.java` | **移除**充值/兑换入口语义，**新增「更改 API」**（手填 DashScope Key + 测试连接） |
| `LlmClient.forPrefs` | 网关模式对这批用户关闭（`use_gateway` 置 false 或按账号来源判断） |

### 服务端（`agentlite-server/`）

- **保留** `order.go` / 兑换码 / 额度网关代码：另一条模式（我方额度）仍可用，只是前端不再暴露。
- 无需新增接口。若将来要做"我方代管 AK"，再议（当前决策明确排除）。

## 5. 必须先验证的两点（实现前）

### 5.1 `CreateApiKey` 的准确请求形状 —— **大部分已查实（2026-09-13）**

来源：API 元数据 JSON `https://error-center.alibabacloud.com/meta/v1/products/ModelStudio/versions/2026-02-10/apis/CreateApiKey/api`
＋ 官方规范《ROA风格请求体&签名机制》＋ **假凭据实测两次对照**。

**请求形状（已确认）**

| 项 | 值 | 证据强度 |
|---|---|---|
| host | **`modelstudio.cn-beijing.aliyuncs.com`** | **实测：`InvalidAccessKeyId.NotFound` + 响应体 `product=ModelStudio`** = 标准网关已进到凭据查找阶段 |
| ~~`bailian.aliyuncs.com`~~ | **不要用** | 另一套（老百炼）网关，扁平错误码 `100016`；**不校验版本**（对 `2023-12-29` 也返回同样错误）→ 用它探测会误判 |
| ~~`bailian.cn-beijing.aliyuncs.com`~~ | **不可用** | 实测 `400 InvalidVersion` |
| ~~`modelstudio.aliyuncs.com`~~ | **DNS 不存在** | regional 规则要求带 region |
| method | `POST` | metadata + SDK 生成代码 |
| path | `/modelstudio/apikeys` | metadata |
| `x-acs-action` | `CreateApiKey` | metadata |
| `x-acs-version` | `2026-02-10` | metadata |
| query 参数 | `workspaceId`（选填，留空=默认业务空间）、`description`（选填，≤200） | metadata。**注意：走 query，不是 body**（与直觉相反） |
| 正文 | 空（先用空 body 打通链路；`auth` 字段标「未证实」，见下） | SDK `reqBodyType='formData'` |
| 必填公共头 | `host` / `x-acs-action` / `x-acs-version` / `x-acs-date` / `x-acs-content-sha256` / `x-acs-signature-nonce` | 官方规范 |
| 签名集合（建议） | 只签这 6 个：`host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version` | 见下方「User-Agent 陷阱」 |

**★ endpoint 判定的教训（写下来避免重犯）**：「版本被承认」**不等于**「路由正确」。
最初用 `bailian.aliyuncs.com` 探测，见它返回"凭据被拒"就断定 host 正确——**错了**：
那个网关对任意版本都返回同一个扁平错误码，**根本不校验版本**，所以它无法证明任何事。
正确做法是用**标准 OpenAPI 网关**（`modelstudio.*`）探测，它的错误码会区分
「版本错 / AK 不存在 / 权限不足 / 签名错」，每一项都能定位到具体环节。

**★ User-Agent / Content-Type 陷阱**：**绝对不要**把 `content-type` / `user-agent` / `accept`
放进签名集合。若 `HttpURLConnection`（或任何 HTTP 库）在签名**之后**才自动补齐/改写这些头，
服务端算出的规范化头就与客户端签的不一致 → `SignatureDoesNotMatch`，且极难排查。
`AliyunV3Signer` 只签显式传入的头，天然规避此问题；调用方也**不要**额外加头进签名。

**返回体（已确认字段名）**

- **成功**：`apiKey.apiKeyValue` ← **这就是要写进 `Prefs.apiKey` 的 Key**，形如 `sk-ws-djI.8O7dkfkW2aICctnid4u4`；
  同级还有 `apiKeyId`(int64) / `workspaceId` / `description` / `createdBy` / `gmtCreate`(epoch ms)。
  ⚠️ 官方注释：「**立即复制你的 API key**…丢失可重置或新建」→ 客户端拿到后**必须立刻落盘**。
- ⚠️ **失败时外层字段是 PascalCase**：`{"Code":"…","Data":null,"Message":"…","Success":false}`
  —— 与成功时的 camelCase 不是同一套风格，**解析必须兼容两种**。

**★ 实测踩到的坑（实现时必须写死）**：**认证失败也可能返回 HTTP 200**。
→ 客户端**绝不能只看状态码**判断成功，必须校验 `Success == true` **且** `apiKey.apiKeyValue` 非空。
否则会把「令牌认证失败」当成「创建成功」，接着写一个空 Key 进 Prefs —— 用户以为充值成功，实际不可用。

**仍未验证的部分**
1. **有效 AK 下 `Success=true` 时的完整真实返回体** —— 需要一把 RAM 子账号 AK 跑 `tests/verify_create_apikey.sh`。
2. `auth` 字段（API Key 权限设置）在线上是 form-data 还是 JSON —— **先用空 body 打通，再补**。

**V3 签名实现状态：已完成并回归（含官方固定向量）**
- `agent-lite/src/com/dsh/agentlite/AliyunV3Signer.java`（纯标准库，零 `android.*` 依赖）
- `tests/AliyunV3SignerTest.java` 桌面 JVM 回归 **43/43 通过**（编码/规范化/URI/查询串/11 行 CanonicalRequest 形状/
  确定性/两级签名形状/独立复算/篡改敏感性）
- 签名链：`CanonicalRequest → SHA256 → StringToSign("ACS3-HMAC-SHA256\n"+摘要) → HMAC-SHA256(原始 AccessKeySecret) → 小写 hex`
  - **V3 不派生密钥**（已用官方固定向量字节级复算确认）：不是 V2 的 `secret+"&"` + HMAC-SHA1 + base64
  - `StringToSign` 与最终签名都是**小写 hex**，不是 base64
  - `CanonicalRequest` 共 **6 个 `\n`**，`CanonicalHeaders` 自带尾随 `\n` 再加一个 `\n` 构成空行 —— 最易写错处
- `tests/verify_create_apikey.sh`：curl 打通脚本，含**跨实现固定向量自检**（python 侧与 Java 侧签名必须一致才发真实请求），
  AK 只走环境变量、签名头不落文件、响应默认遮蔽 Key

### 5.2 购买页 URL 的套餐映射 —— **已定（2026-09-13 用户决策）**

**用户决策三条**：
1. **仍买单模型 token 资源包**，但**把 App 固定为单一模型**（见下方冲突说明）。
2. 购买页商品：**`sfm_llminference_spn_public_cn`**（大语言模型推理资源包）
   → `https://common-buy.aliyun.com/?commodityCode=sfm_llminference_spn_public_cn`
3. 返回检测：**用 `ListApiKeys` 比对前后差异**，不猜时间窗（见 §5.3 缺口 1 的解法）。

**实测**：该页面**需登录阿里云**，且**无法用 URL 预选档位**（带 commodityCode 只定位到商品）。
→ 因此「点了就对应套餐」**只能做到打开对应商品页，由用户自己选档**
（`PURCHASE_REDIRECT_DESIGN` §5.2 原本就要求如实告知，不得假装能直达）。
→ App 侧应把这句写进界面文案，而不是做一个"已为你选中 XX 档"的假承诺。

**★ 决策 1 带来的架构冲突（必须处理，否则用户白花钱）**

官方明确：**资源包按模型名称严格匹配、跨版本或子型号不通用**
（例：qwen-plus 资源包不抵扣 qwen-max）。而 App 当前**不只用一个模型**：

| 用途 | 当前取值 | 来源 |
|---|---|---|
| 主模型 | `qwen3.5-omni-plus` | `agent-lite/dev.local` 的 `MODEL` |
| 语义规划 | `qwen3.5-flash`（**硬编码默认值**） | `Prefs.java:97` |
| 视觉 | `visionModel()`（空=关闭） | `Prefs.java:95` |
| 语音转写 | `asrModel()`（空=用主模型） | `Prefs.java:23` |

→ 只买一个模型的资源包，**只有该模型的调用被抵扣**，其余走按量付费；账户余额不足则整条链路失败。

**好消息**：`qwen3.5-omni-plus` 是**全模态模型**（原生支持图像/音频输入），
所以「固定单一模型」在技术上是可行的。**待做**：
- 把 `plannerModel` 默认值从 `qwen3.5-flash` 改成主模型（或留空 = 关闭规划前置）
- `visionModel` 留空（走主模型的全模态能力）
- `asrModel` 留空（走主模型）
- ⚠️ 这属于 **App 核心行为变更**，需用户确认后再实施——且应做成**可关闭**，
  否则自己配了 API Key 的多模型用户会被无声降级。

**现有 App 套餐（`agentlite-server/order.go` 的 `defaultPacks()`，可由 `packs.json` 覆盖）**

| pack_id | 名称 | 价格 | 额度 |
|---|---|---|---|
| `starter` | 体验包 | ￥5 | 500 units |
| `standard` | 标准包 | ￥18 | 2200 units（含赠送 200） |
| `pro` | 专业包 | ￥48 | 7000 units（含赠送 1000） |

→ 这三档**不映射到官方档位**（官方商品页由用户自选），App 套餐仅用于展示与话术对齐。

### 5.3 设计缺口（开工前应定口径，否则会写出"看着能跑"的错实现）

上一轮复核时读出 4 处，尚未决策：

1. **返回检测机制没设计**。§2 流程图写「返回 App，自动检测返回」，但没定机制。
   ~~`onResume` + 时间窗？还是 `intent://` deep link 回跳？~~ 官方购买页不接受自定义 scheme 回跳。
   若只靠 `onResume` 猜，会出现**「用户切出去看了眼微信再回来」被误判为「已付款」**的假阳性。
   → ✅ **已决策（2026-09-13 用户选择）**：用 **`ListApiKeys` 比对前后差异**。
   即：打开购买页**前**记下已有 Key 集合；回来后调 `ListApiKeys`，
   **只有出现新 Key 才继续**走 `CreateApiKey` 流程。不依赖时序，彻底消除假阳性。
   ⚠️ 注意：`ListApiKeys` 与 `CreateApiKey` 同属 ModelStudio 控制面，
   host/path/version 需按同一套元数据确认（`ListApiKeys` 的 path 见
   `https://error-center.alibabacloud.com/meta/v1/products/ModelStudio/versions/2026-02-10/apis/ListApiKeys/api`）。
2. **回滚语义模糊**。§6 说失败必须可回滚，但没说回滚哪一层。官方那边钱已花，物理上退不回；
   能回滚的只有本地状态。→ 建议明确写成「本地不进中间态 + 明确引导到『更改 API』手填」，
   别让人误读成能撤销支付。
3. **AK 的录入路径缺失**。§7 要 RAM 子账号 AK，但 §4 清单里原本没有任何界面接这个 AK
   （`AkStore` 只写了「存储」）。缺「首次使用录入 AK + 当场校验权限」的流程，
   否则 `CreateApiKey` 无凭据可签。
4. **`use_gateway` 判定口径未定**。§4 写「置 false 或按账号来源判断」—— 二选一没选。
   全局置 false 会让老用户也掉出网关模式。

另：`agent-unified/client/src/com/dsh/agentlite/` 下有 `PurchaseActivity` / `SettingsActivity` / `LlmClient`
的**平行副本**。开工前先定谁是真身、另一份是否同步，否则会改一半。

## 6. 安全边界与红线

- **AK 绝不离开设备**：不上传、不入日志、不进崩溃报告。存储用 Keystore 加密。
- **AK 需要最小权限**：只授予 `bailian:CreateApiKey`（RAM 自定义策略），**不要给主账号 AK**。
  实现时在界面上明确写出这一点，并给出创建 RAM 用户的指引。
- **支付必须由用户本人在官方页面完成**。不得用 WebView 注入脚本自动勾选/自动点击确认支付——
  这既违反阿里云服务条款，也违反本项目自定的红线
  （`software-interaction-logic` §7：支付扣费前必须 `ask` 用户明示许可；`SECURITY.md` 同）。
  **"仅跳出付款界面"按此实现为"只让用户点那一次确认支付"，而不是替用户点。**
- 创建 Key 失败必须**可回滚**：不得让客户端停在一个"有网关无额度、有 Key 不可用"的中间态。
- **签名脚本的红线**：AK/SK 只走环境变量，签名头不落临时文件，真凭据永不入 git
  （`tests/verify_create_apikey.sh` 已按此实现，响应默认遮蔽 `apiKeyValue`）。

## 7. 需要用户提供的信息

1. App 内套餐 → 官方 token 资源包档位的**对应关系**（含价格）。**← 唯一还卡着的输入**
2. 是否为 **RAM 子账号 AK**（推荐）；若是主账号 AK，实现时必须显著警告。
3. **一把有效 AK**（RAM 子账号）用来跑 `tests/verify_create_apikey.sh`，确认成功路径的真实返回体。
   **只需在你自己机器上 export，不要贴进对话。**

