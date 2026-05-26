# Memory Health Signals Runbook

本手册对应 `dictionaryVersion=memory-health-v2`，用于把内存健康 `code`、Prometheus `health_code`、REST `summary.code` 和日志事件统一到同一套排障语义。

迁移与兼容窗口见 `ops/runbooks/memory-health-migration.md`。

## 快速定位

先看三个入口：

1. `GET /api/v1/memory/health`
2. `GET /api/v1/memory/slo/report`
3. `GET /api/v1/memory/health/catalog`

再补充两个观测面：

1. `GET /actuator/prometheus`
2. 应用日志中的 `memory_health_*` 事件

## healthy

含义：当前 SLO 和诊断信号正常。

操作：无需处理。

## namespace_isolation_violation

含义：跨 namespace 的记忆隔离被破坏，属于正确性故障。

优先检查：

1. `namespaceIsolationViolations`
2. 近期 recall 请求是否混入错误 namespace
3. L1/L2 检索和过滤链路是否绕过 namespace 条件

处理动作：先阻断异常 recall 流量，再排查过滤条件和索引污染。

## checkpoint_recovery_success_rate_low

含义：checkpoint 恢复链路成功率低于目标。

兼容说明：旧 `health_code=recovery_success_rate_low` 与 REST 明细 `recoverySuccessRate` 仍会保留到 2026-08-31 之后再考虑移除，但新的监控、告警和日志检索应立即切换到 `checkpoint_recovery_success_rate_low` 与 `checkpointRecoverySuccessRate`。

优先检查：

1. `checkpointRecoverySuccessRate` 或兼容字段 `recoverySuccessRate`
2. L3 冷存储可用性
3. checkpoint 链和 WAL 回放异常日志中的 `chain=checkpoint-recovery`

处理动作：确认最新 FULL/DELTA checkpoint 可读，再检查对象存储和恢复顺序。

## memory_persistence_success_rate_low

含义：记忆持久化链路成功率低于目标，说明 fragment 写入在 L2/L3 或 DLQ 最终落盘阶段发生永久失败。

优先检查：

1. `persistenceSuccessRate`
2. DLQ 积压、重放与丢弃日志中的 `chain=memory-persistence`
3. L2 Milvus 与 L3 冷存储写入可用性

处理动作：先确认失败是暂时退化还是已发生 `dlq-drop`，再处理 L2/L3 写入异常和 DLQ 重放积压。

## baseline_lift_low

含义：当前活跃权重相对 baseline 的收益跌破目标。

优先检查：

1. `/api/v1/memory/learning?scenario=chat`
2. `baselineRelativeLift`
3. `sampleCount` 与 `pendingRecallSessions`

处理动作：检查反馈质量，必要时回退激进权重或降低自适应更新速度。

## eviction_regret_high

含义：被淘汰的数据被过快召回，说明 victim 选择错误。

优先检查：

1. `regretRate`
2. `/api/v1/memory/slo/report` 中 `regret.modes`
3. `protectedGroupCount`

处理动作：优先看是哪种 mode 回归，再看保护组是否不足、pin 压力是否过高。

## store_latency_p99_high

含义：写入路径 p99 延迟超标。

优先检查：

1. `storeLatencyP99Ms`
2. embedding 服务时延
3. 异步持久化积压

处理动作：先排 embedding 和持久化，再看 L1 admission 锁竞争。

## recall_latency_p99_high

含义：召回路径 p99 延迟超标。

优先检查：

1. `recallLatencyP99Ms`
2. L1 命中率
3. L2 搜索与 page fault 数量

处理动作：确认是否由 L2 回源、分页抖动或预取失效放大延迟。

## shadow_lift_regression

含义：shadow 配置相对 active 出现回归。

优先检查：

1. `shadowRelativeLift`
2. 场景级 `learningScenarios`
3. 近期反馈样本是否偏斜

处理动作：不要提升 shadow 配置，先确认是样本不足还是真实退化。

## baseline_lift_not_sustained

含义：相对 baseline 的收益不稳定。

优先检查：

1. `baselineLiftSustainedRatio`
2. 不同场景的反馈量是否均衡

处理动作：提高样本量或分场景观察，不要基于短窗口收益直接推广。

## eviction_log_coverage_low

含义：驱逐日志覆盖率下降，后续 regret 和排障可信度会下降。

优先检查：

1. `evictionLogCoverage`
2. 驱逐决策日志是否被跳过
3. 指标和日志链路是否丢失

处理动作：先恢复日志覆盖率，再根据 regret 指标做策略判断。

## prefetch_strategy_degraded

含义：某个预取策略在消耗预算但没有产生足够消费命中。

优先检查：

1. `/api/v1/memory/slo/report` 中 `prefetchStrategies`
2. `hitRate`
3. `effectiveBudget`

处理动作：定位具体 source，先收缩预算，再判断是否需要调整触发条件。

## eviction_regret_mode_high

含义：某个驱逐 mode 的 regret 明显偏高。

优先检查：

1. `regret.modes`
2. mode 对应的 `evictionCount`、`regretCount`

处理动作：按 mode 做定向调优，不要全局修改所有驱逐参数。

## paging_drift_high

含义：语义分页的增量分配开始偏离局部性。

优先检查：

1. `assignment.reuseRate`
2. `assignment.newPageRate`
3. `residentPages` / `evictedPages`

处理动作：优先检查距离阈值和页大小，再考虑重建页表。

## learning_regression

含义：在线学习在某个场景中出现退化信号。

优先检查：

1. `learningScenarios`
2. 场景级 `shadowRelativeLift`
3. 待处理反馈是否堆积

处理动作：先确认退化是否集中在单场景，再决定是否冻结学习更新。

## diagnostic_warning

含义：出现了未归类的诊断告警。

优先检查：

1. `diagnosticWarnings`
2. 近期是否新增了诊断文案但未纳入字典

处理动作：如果该告警重复出现，应补充专用 `health_code`、日志字段和 runbook 条目。
