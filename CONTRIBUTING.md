# 贡献指南（Contributing）

## 开发环境

- macOS 或 Windows（Android 子项目提供 `build-mac.sh` 与 `build.ps1` 两套脚本）；
- JDK 11+（Windows 脚本假定 JDK 17）；Android SDK Platform 34 + Build Tools 34；
- `agentd-go` 需要 Go 工具链；测试脚本需要 Python 3 与 ADB。

客户端采用纯命令行工具链（aapt2 → javac → d8 → zipalign → apksigner），**无 Gradle、无 Kotlin、无第三方运行时依赖**——新增功能时请保持这一约束，不要引入 Gradle 或外部依赖。

## 构建与自检

提交前至少执行与改动范围对应的检查：

```bash
cd agent-lite && sh build-mac.sh      # 或 agent-client / agentd-apk
cd agentd-go && go vet ./... && gofmt -l .
```

改动 UI / 交互逻辑时，请附带真机或模拟器验证说明（设备型号、系统版本、是否 Root）。

## 提交规范

- 提交信息使用 Conventional Commits：`feat(...)` / `fix(...)` / `docs:` / `style(...)` / `chore(...)`，正文用中文简明描述动机与影响；
- 代码风格：Java/Go/Python/Shell 统一 4 空格缩进、LF、UTF-8、文件末尾换行（见 `.editorconfig`）；源码文件顶部保留 `SPDX-License-Identifier: Apache-2.0`；
- **请勿 `git add -A` 盲目提交**：先检查 `git status`，排除密钥、APK、ZIP、设备日志、上传文件和一次性运维脚本；
- API Key 等本地配置写入未跟踪的 `dev.local`（构建期注入），真实密钥永不入库；
- 中文文档文件名带 `.zh-CN.md` 后缀；进度/计划类文档带日期前缀（如 `PROGRESS_2026-09-11.zh-CN.md`）。

## 文档同步

改动行为或能力时，请同步更新：`README.md`（英文）与 `README.zh-CN.md`（中文，权威版本）、受影响子项目的 README、`agent-lite/COMPATIBILITY.zh-CN.md`（兼容性相关时）。
