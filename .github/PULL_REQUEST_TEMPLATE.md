<!--
提交前请确认：
1) 已在本地跑过 `make check`（或至少对应的 `make <target>`），并在下方写明结果；
2) 若改动了 agent-lite/ 或 agentlite-server/ 的源码，已执行
   `bash agent-unified/scripts/sync-sources.sh` 同步镜像（契约门禁会拦截漂移）；
3) 没有把 dev.local、APK、keystore、设备日志等本地产物带进来。
-->

## 这个 PR 做了什么

<!-- 一两句话说明动机与改动面 -->

## 关联 Issue

<!-- Fixes #123 / Refs #456，没有可写「无」 -->

## 改动类型

- [ ] 功能（feat）
- [ ] 缺陷修复（fix）
- [ ] 文档 / 注释（docs）
- [ ] 重构（refactor，无行为变化）
- [ ] 测试 / 门禁（test / ci）
- [ ] 构建与发布（chore / release）

## 自检结果

```
$ make check
...
（粘贴结尾的「== 全部免设备检查通过 ✅ ==」或失败项）
```

- [ ] 已同步 agent-unified 镜像（`bash agent-unified/scripts/sync-sources.sh --check` 通过）
- [ ] 改动了客户端 UI / 交互逻辑时，已附真机验证说明（机型、系统版本、是否 Root）
- [ ] 未引入 Gradle / Kotlin / 第三方运行时依赖（客户端保持纯命令行工具链）

## 风险与回滚

<!-- 影响面、兼容性、需要同步更新的文档；不涉及可写「无」 -->
