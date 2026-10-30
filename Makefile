# SPDX-License-Identifier: Apache-2.0
#
# 免设备检查的聚合入口（手册 §15.3 P2「测试缺少统一入口」的落地）。
#
#   make check      # 全部免设备门禁：Go 单测 + 契约 + 迁移演练 + UI 守卫 + 桌面 JVM 回归
#   make go         # 只跑 Go 单测（agentd + agentlite-server）
#   make contract   # 只跑两端契约门禁
#   make drill      # 只跑数据副本迁移演练（报告 §4.1 / §4.2）
#   make ui         # 只跑 UI 文案/控件守卫
#   make jvm        # 只跑桌面 JVM 回归（无需 Android SDK）
#   make clean
#
# 设计原则：**不碰设备、不碰外部网络、不改产物**。需要真机的回归（充值闭环、
# 无障碍重绑、分屏旋转）仍在 tests/ 下单独跑，见 docs/ 各报告。
#
# drill 是唯一会起进程的一项：它在临时目录里造数据副本并跑真实服务端二进制，
# 只绑 127.0.0.1 的随机端口 + 现场自签证书，结束即回收，不写仓库内任何文件。
#
# Go 工具链：优先用 PATH 里的 go；没有就用 agent-unified 自备的那份
# （见 agent-unified/scripts/fetch-go.sh）。

SHELL := /bin/bash
GO ?= $(shell command -v go 2>/dev/null || echo "$$HOME/.agent-unified-go/go/bin/go")
JAVA_RELEASE ?= 11
CLIENT_SRC := agent-lite/src/com/dsh/agentlite
TMP := $(CURDIR)/.check-tmp

.PHONY: check go contract drill ui jvm hooks clean

check: go contract drill ui jvm
	@echo ""
	@echo "== 全部免设备检查通过 ✅ =="

go:
	@echo "== Go 单测（agentd） =="
	@cd agentd-go && "$(GO)" test ./...
	@echo "== Go 单测（agentlite-server） =="
	@cd agentlite-server && "$(GO)" test ./...

contract:
	@echo "== 两端契约门禁 =="
	@python3 agent-unified/scripts/verify-contract.py

# 报告 §4.1「在数据副本上验证首次迁移与重开」与 §4.2「支付竞争 smoke」的落地：
# 造一份生产旧形态的数据副本（2 账号 / 5 兑换码 / 2 订单 / 2 设备 / 归档记录），
# 用真实二进制 + 真实 TLS + 真实 HTTP 走完：首次迁移 → 重开幂等 → 孤儿设备认领 →
# 迁移后写入 → 支付竞争（并发 pay/cancel 与双码抢付）→ 回滚与损坏边界。
drill:
	@echo "== 数据副本迁移演练（§4.1 迁移与重开 / §4.2 支付竞争 smoke）=="
	@python3 tests/migration_drill.py

ui:
	@echo "== UI 文案守卫 =="
	@bash tests/ui_text_guard.sh
	@echo "== UI 控件守卫 =="
	@python3 tests/ui_widget_guard.py; \
	  code=$$?; \
	  if [ $$code -eq 2 ]; then \
	    echo "  ↳ SKIP：无 android.jar / classes.jar（干净检出未构建 APK），控件类型断言跳过；构建后自动恢复"; \
	  elif [ $$code -ne 0 ]; then exit $$code; fi
	@echo "== SPEC-UX-001 零门槛守则（手册 §16.4）=="
	@python3 tests/spec_ux_001_guard.py
	@echo "== 适老化前端（手机形态：字号/触控/对比度/不丢功能）=="
	@python3 tests/elder_ui_guard.py
	@echo "== 动作词表一致性（手册 §16.9 / UX-10）=="
	@python3 tests/action_schema_guard.py
	@echo "== 模糊语义转译层（设计方案 §11.1）=="
	@python3 tests/fuzzy_flow_guard.py
	@echo "== 操作记忆库（FlowMemory / FlowPlan）=="
	@python3 tests/flow_memory_guard.py
	@echo "== 突发界面处置守则（弹窗/广告/权限/异常跳转，守则 4）=="
	@python3 tests/popup_ad_guard.py
	@echo "== 全局代码复核回归门禁（节点生命周期/账号一致性）=="
	@python3 tests/global_review_guard.py
	@echo "== M0-01 功能开关契约门禁 =="
	@python3 tests/m0_feature_flags_guard.py
	@echo "== M0-02 四类契约静态门禁 =="
	@python3 tests/m0_contract_guard.py
	@echo "== M3-01 工具注册表静态门禁 =="
	@python3 tests/m3_tool_registry_guard.py
	@echo "== M3-02 工具参数校验静态门禁 =="
	@python3 tests/m3_tool_validation_guard.py
	@echo "== T05a 任务代际防护静态门禁 =="
	@python3 tests/generation_guard.py
	@echo "== T03a 动作入口收敛静态门禁（代点进 exec）=="
	@python3 tests/auto_tap_guard.py

jvm:
	@echo "== 桌面 JVM：AliyunV3Signer =="
	@mkdir -p "$(TMP)/v3"
	@javac --release $(JAVA_RELEASE) -d "$(TMP)/v3" \
		tests/AliyunV3SignerTest.java "$(CLIENT_SRC)/AliyunV3Signer.java"
	@java -cp "$(TMP)/v3" AliyunV3SignerTest
	@echo "== 桌面 JVM：PurchaseFlow =="
	@mkdir -p "$(TMP)/pf"
	@javac --release $(JAVA_RELEASE) -d "$(TMP)/pf" \
		tests/PurchaseFlowTest.java "$(CLIENT_SRC)/PurchaseFlow.java"
	@java -cp "$(TMP)/pf" PurchaseFlowTest
	@echo "== 桌面 JVM：模糊语义转译（TextNormalizer / FuzzyFlow / SemanticTranslator）=="
	@mkdir -p "$(TMP)/fuzzy"
	@javac --release $(JAVA_RELEASE) -encoding UTF-8 -d "$(TMP)/fuzzy" \
		tests/FuzzyTranslatorTest.java \
		"$(CLIENT_SRC)/FuzzyFlow.java" \
		"$(CLIENT_SRC)/TextNormalizer.java" \
		"$(CLIENT_SRC)/SemanticTranslator.java"
	@java -cp "$(TMP)/fuzzy" FuzzyTranslatorTest
	@echo "== 桌面 JVM：操作记忆库（FlowPlan）=="
	@mkdir -p "$(TMP)/flowmem"
	@javac --release $(JAVA_RELEASE) -encoding UTF-8 -d "$(TMP)/flowmem" \
		tests/FlowMemoryTest.java \
		"$(CLIENT_SRC)/FlowPlan.java" \
		"$(CLIENT_SRC)/NodeInfo.java" \
		"$(CLIENT_SRC)/TextNormalizer.java" \
		"$(CLIENT_SRC)/FuzzyFlow.java"
	@java -cp "$(TMP)/flowmem" FlowMemoryTest
	@echo "== 桌面 JVM：M0-02 四类契约模型 =="
	@mkdir -p "$(TMP)/contracts"
	@javac --release $(JAVA_RELEASE) -encoding UTF-8 -d "$(TMP)/contracts" \
		tests/ContractModelsTest.java \
		"$(CLIENT_SRC)/ContractVersions.java" \
		"$(CLIENT_SRC)/contracts/ContractJson.java" \
		"$(CLIENT_SRC)/contracts/ScreenSnapshot.java" \
		"$(CLIENT_SRC)/contracts/ToolInvocation.java" \
		"$(CLIENT_SRC)/contracts/TraceEvent.java" \
		"$(CLIENT_SRC)/contracts/ScreenState.java"
	@java -cp "$(TMP)/contracts" ContractModelsTest
	@echo "== 桌面 JVM：M3-01 ToolSpec/ToolRegistry =="
	@mkdir -p "$(TMP)/tools"
	@javac --release $(JAVA_RELEASE) -encoding UTF-8 -d "$(TMP)/tools" \
		tests/ToolRegistryTest.java \
		"$(CLIENT_SRC)/ActionSchema.java" \
		"$(CLIENT_SRC)/tools/ToolParameter.java" \
		"$(CLIENT_SRC)/tools/ToolSpec.java" \
		"$(CLIENT_SRC)/tools/ToolRegistry.java" \
		"$(CLIENT_SRC)/tools/ValidationResult.java"
	@java -cp "$(TMP)/tools" ToolRegistryTest
	@echo "== 桌面 JVM：突发弹窗守卫（PopupGuard）=="
	@mkdir -p "$(TMP)/popup"
	@javac --release $(JAVA_RELEASE) -encoding UTF-8 -d "$(TMP)/popup" \
		tests/PopupGuardTest.java \
		"$(CLIENT_SRC)/PopupGuard.java" \
		"$(CLIENT_SRC)/FlowPlan.java" \
		"$(CLIENT_SRC)/NodeInfo.java" \
		"$(CLIENT_SRC)/TextNormalizer.java" \
		"$(CLIENT_SRC)/FuzzyFlow.java"
	@java -cp "$(TMP)/popup" PopupGuardTest
	@echo "== 桌面 JVM：凭据预检/请求路由（内存连接，无真实网络）=="
	@JAVA_RELEASE=$(JAVA_RELEASE) python3 tests/credential_route_guard.py
	@echo "== 桌面 JVM：ASR单通道/LLM图检测/LAN批量与字节分页 =="
	@JAVA_RELEASE=$(JAVA_RELEASE) python3 tests/asr_lan_regression.py
	@echo "== 桌面 JVM：账号同步与直接命令结果 =="
	@JAVA_RELEASE=$(JAVA_RELEASE) python3 tests/account_sync_regression.py
	@echo "== 桌面 JVM：单一动作执行与明确失败 =="
	@JAVA_RELEASE=$(JAVA_RELEASE) python3 tests/action_execution_regression.py
	@echo "== 桌面 JVM：T04a done 证据分级（抽取 judgeDone 真值表 + 终态守卫）=="
	@JAVA_RELEASE=$(JAVA_RELEASE) python3 tests/done_evidence_guard.py

clean:
	@rm -rf "$(TMP)"
	@echo "已清理 $(TMP)"

# 启用仓库自带的 git 钩子（提交渠道的本地一半）。每台克隆执行一次。
# 钩子只做本地快速拦截；远端正本见 .github/workflows/ci.yml。
hooks:
	@git config core.hooksPath .githooks
	@chmod +x .githooks/pre-commit .githooks/commit-msg .githooks/pre-push
	@echo "已启用 .githooks（core.hooksPath=.githooks）"
	@echo "  pre-commit：冲突标记 / 大文件 / 密钥 / 镜像一致性"
	@echo "  commit-msg：Conventional Commits"
	@echo "  pre-push：契约门禁 + 镜像校验（全量：AGENTLITE_PRE_PUSH_FULL=1）"
