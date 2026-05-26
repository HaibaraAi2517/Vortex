# Memory Health Migration Guide

生效日期：2026-05-25

本次变更不是普通重构，而是一次 durability 与 health 语义升级。目标是把 checkpoint/WAL 恢复链路和 memory persistence 链路从单一 recovery 语义拆开，避免告警、日志和 runbook 出现观测断层。

## 变更摘要

新增 durability chain：

1. `checkpoint-recovery`
2. `memory-persistence`

新增 health code：

1. `checkpoint_recovery_success_rate_low`
2. `memory_persistence_success_rate_low`

兼容字段：

1. `recoverySuccessRate`
2. `recovery_success_rate_low`
3. `vortex_hmc_slo_recovery_success_rate`

以上兼容项自 2026-05-25 起进入弃用状态，若没有新的外部依赖登记，最早在 2026-08-31 之后移除。

## 消费方迁移

Prometheus / Alertmanager：

1. 将 checkpoint 告警规则切换到 `vortex_hmc_slo_checkpoint_recovery_success_rate`
2. 继续保留 legacy metric 仅用于短期 dashboard 平滑迁移，不再作为新规则入口
3. 告警路由统一按 `health_code=checkpoint_recovery_success_rate_low` 与 `health_code=memory_persistence_success_rate_low` 聚合
4. Alertmanager 路由可参考 `ops/alertmanager/memory-health-routes.yml`

Grafana / 指标面板：

1. checkpoint 恢复成功率面板改读 `vortex_hmc_slo_checkpoint_recovery_success_rate`
2. 新增或显式展示 `vortex_hmc_slo_persistence_success_rate`
3. 如需总览 durability，可保留 `vortex_hmc_slo_durability_success_rate` 作为聚合视图，但不要再用它替代链路级告警
4. 查询样例可直接使用 `ops/grafana/memory-health-queries.md`

日志检索：

1. checkpoint/WAL 故障检索条件切到 `healthCode=checkpoint_recovery_success_rate_low` 且 `chain=checkpoint-recovery`
2. persistence 故障检索条件切到 `healthCode=memory_persistence_success_rate_low` 且 `chain=memory-persistence`
3. DLQ 演练重点关注 `phase=dlq-enqueue`、`phase=dlq-replay`、`phase=dlq-drop`

REST / 自动化消费方：

1. 优先读取 `/api/v1/memory/health.details.checkpointRecoverySuccessRate`
2. 优先读取 `/api/v1/memory/health.details.persistenceSuccessRate`
3. 旧 `recoverySuccessRate` 仅作为兼容字段，不再承载完整 durability 语义
4. `/api/v1/memory/health/catalog` 会返回 `migrationGuide` 与 `compatibility`，消费方可以据此提示升级

## 故障演练矩阵

已覆盖的最小演练集合：

1. `L2` 写失败但成功入 DLQ
   参考 `FragmentPersistenceManagerTest.failedPersistenceIsQueuedAndCanBeReplayed`
2. `L3` 写失败后 DLQ 重放恢复
   参考 `FragmentPersistenceManagerTest.replayResumesFromL3WhenL2AlreadySucceeded`
3. checkpoint / WAL 恢复失败
   参考 `SnapshotRecoveryHealthLoggingTest.recoveryFailureLogsUnifiedDurabilityEnvelope`

验证口径：

1. 日志必须包含统一 envelope：`memory_durability_degraded` 或 `memory_durability_recovered`
2. `healthCode`、`chain`、`phase`、`failureReason` 必须和 runbook 一致
3. Prometheus 告警标签 `health_code` 必须与 REST `summary.code` 一致

## 回归验证

2026-05-25 已执行：

`mvn --% test -pl vortex-kernel,vortex-app -am`

结果：`BUILD SUCCESS`
