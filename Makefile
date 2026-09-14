# SPDX-License-Identifier: Apache-2.0
#
# 免设备检查的聚合入口（手册 §15.3 P2「测试缺少统一入口」的落地）。
#
#   make check      # 全部免设备门禁：Go 单测 + 契约 + UI 守卫 + 桌面 JVM 回归
#   make go         # 只跑 Go 单测（agentd + agentlite-server）
#   make contract   # 只跑两端契约门禁
#   make ui         # 只跑 UI 文案/控件守卫
#   make jvm        # 只跑桌面 JVM 回归（无需 Android SDK）
#   make clean
#
# 设计原则：**不碰设备、不碰网络、不改产物**。需要真机的回归（充值闭环、
# 无障碍重绑、分屏旋转）仍在 tests/ 下单独跑，见 docs/ 各报告。
#
# Go 工具链：优先用 PATH 里的 go；没有就用 agent-unified 自备的那份
# （见 agent-unified/scripts/fetch-go.sh）。

SHELL := /bin/bash
GO ?= $(shell command -v go 2>/dev/null || echo "$$HOME/.agent-unified-go/go/bin/go")
JAVA_RELEASE ?= 11
CLIENT_SRC := agent-lite/src/com/dsh/agentlite
TMP := $(CURDIR)/.check-tmp

.PHONY: check go contract ui jvm clean

check: go contract ui jvm
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

ui:
	@echo "== UI 文案守卫 =="
	@bash tests/ui_text_guard.sh
	@echo "== UI 控件守卫 =="
	@python3 tests/ui_widget_guard.py
	@echo "== SPEC-UX-001 零门槛守则（手册 §16.4）=="
	@python3 tests/spec_ux_001_guard.py
	@echo "== 动作词表一致性（手册 §16.9 / UX-10）=="
	@python3 tests/action_schema_guard.py

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

clean:
	@rm -rf "$(TMP)"
	@echo "已清理 $(TMP)"
