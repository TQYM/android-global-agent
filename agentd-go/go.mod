module agentd

// 与 agent-unified/scripts/fetch-go.sh 自备工具链（1.24.6）对齐：
// 代码未使用任何 1.25+ 新 API，抬高版本只会让本机离线构建失败（2026-09-13 P0-4 修复）。
go 1.24
