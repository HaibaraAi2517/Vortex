# Memory Test Plan

## 目标

这个方案不是验证 "`/api/v1/memory/*` 接口能返回 200"；它要验证记忆系统在 5 个核心指标上是否真的有效：

1. 召回命中率
2. 淘汰后可恢复性
3. 恢复一致性
4. 学习有效性
5. 延迟和健康度

方案基于项目当前实现设计，覆盖以下真实接口与组件：

- Memory API: `/api/v1/memory/store`、`/store/fragment`、`/recall`、`/feedback`、`/learning`、`/health`、`/slo`、`/slo/report`
- Task API: `/api/v1/tasks/*`、`/checkpoint`、`/recover`、`/dag`
- 关键实现: `HierarchicalMemoryController`、`RecallOrchestrator`、`AdaptiveWeightLearner`、`SnapshotService`、`MemorySloHealthIndicator`
- 现有基础: `vortex-app/src/test/java/com/vortex/app/integration/FullLifecycleIT.java`

## 测试分层

建议分 4 层，不要把所有指标都堆到一个大而慢的 E2E 里。

### L0: 算法/组件单测

目标：快速验证排序、学习、恢复、SLO 计算逻辑。

覆盖对象：

- `RecallOrchestratorTest`
- `TieredEvictionCoordinatorTest`
- `AdaptiveWeightLearnerTest`
- `RecoveryEngineTest`
- `SnapshotServiceTest`
- `MemorySloTrackerTest`

这层主要验证：

- 排序分值是否按预期变化
- eviction 后 regret 是否被记录
- feedback 后 `updateCount`、profile 是否变化
- checkpoint + WAL replay 后 DAG 节点/边是否一致
- p95/p99、success rate、lift 等指标是否按公式更新

### L1: API 合约测试

目标：验证外部接口、字段结构、错误码、健康摘要字段稳定。

覆盖对象：

- `MemoryControllerTest`
- `TaskControllerTest`
- `MemorySloHealthIndicatorTest`

这层主要验证：

- 请求参数校验
- 返回 JSON 字段是否完整
- `/api/v1/memory/health` 的 `status`、`summary`、`statusReason`
- `/api/v1/memory/learning`、`/slo/report` 的字段可被外部平台直接消费

### L2: 组件集成测试

目标：在不依赖真实 embedding 的前提下，验证多层存储联动。

建议做法：

- 复用 `FullLifecycleIT` 里的 `StubEmbeddingService`
- 把 L1 token 容量压低，稳定触发 eviction
- 通过 Testcontainers 拉起 MinIO + Milvus
- 使用 `IsolatedIntegrationTestSupport` 隔离 MinIO bucket、Milvus collection、WAL 目录

这层是主战场，5 个指标里有 4 个应该主要在这里完成。

### L3: 观测与压测

目标：验证健康度、SLO、Prometheus 指标、长时间回归趋势。

建议做法：

- 启应用后调用 `/api/v1/memory/health`
- 调用 `/api/v1/memory/slo/report`
- 拉取 `/actuator/prometheus`
- 以固定负载循环做 store/recall/feedback/checkpoint/recover

这层适合 nightly 或预发布，不适合作为每次 PR 的阻塞门禁。

## 测试数据设计

先准备一套可控的金标数据，不要一开始就用真实语料。

### 1. 记忆片段数据集

每条数据至少包含：

- `fragmentId`
- `namespace`
- `content`
- `embedding`
- `tokenCount`
- `importance`
- `tags`
- `expectedQueries`

建议至少准备 3 类片段：

1. 强相关片段
2. 中等相关片段
3. 干扰片段

示例主题：

- Java 并发
- Checkpoint / Recover / DAG
- Python dataframe
- 无关噪声文本

### 2. 查询金标

每个 query 需要定义：

- `query`
- `namespace`
- `topK`
- `tokenBudget`
- `expectedHitIds`
- `expectedBestId`
- `expectedTier`，当需要验证 L2/L3 恢复时填入

### 3. DAG 场景数据

至少覆盖 4 类结构：

1. 线性链路: A -> B -> C
2. 分叉链路: A -> B1, A -> B2
3. 合并链路: B1 + B2 -> C
4. checkpoint 前后继续写入: checkpoint 后追加节点，再 recover

## 五个指标的具体方案

## 1. 召回命中率

### 目标

验证问一个问题时，返回结果里确实包含之前存进去的正确信息。

### 推荐测试

新增一个集成测试类，例如 `MemoryRecallQualityIT`。

步骤：

1. 写入一组强相关、中相关、干扰片段
2. 调用 `/api/v1/memory/recall`
3. 断言返回 `fragments[].fragment.id`
4. 记录 `score`、`tier`、`sourceTrace`

断言口径：

- `Hit@K`: `expectedHitIds` 是否至少命中 1 个
- `Top1 Accuracy`: 第一名是否为 `expectedBestId`
- `MRR`: 正确片段首次出现位置的倒数
- `nDCG@K`: 如果后续要评估排序质量，加入这一项

最低建议：

- 冒烟集: `Hit@3 = 100%`
- PR 门禁: 金标集 `Hit@3 >= 95%`
- Nightly: 噪声集 `MRR`、`nDCG@5` 不低于基线

必须断言的接口字段：

- `recallSessionId`
- `fragments[*].fragment.id`
- `fragments[*].tier`
- `totalTokens`

额外要做的负例：

- 错 namespace 不应召回
- tag 不匹配不应召回
- tokenBudget 太小时，正确结果即使相关，也可能被截断；这要单独记录，不和命中失败混淆

## 2. 淘汰后可恢复性

### 目标

验证 L1 被挤掉后，能否通过 L2 或 L3 找回，并重新回灌到 L1。

### 推荐测试

扩展现有 `FullLifecycleIT.memoryStoreRecallEvictCycle`，再拆一个更细的 `MemoryTierRecoveryIT`。

步骤：

1. 把 `vortex.storage.l1.max-tokens` 设得很小，比如 24
2. 先写入目标片段 A
3. 再写入高 importance 的 filler 片段 B/C，稳定触发 A 从 L1 淘汰
4. `await` 确认 A 已进入 L2；如果要验证 L3，再确认对象已落冷存
5. 调用 `/api/v1/memory/recall` 查询 A
6. 断言 A 从 `tier = L2` 或 `L3` 返回
7. 再次检查 `l1HotStore.peek(A)` 已恢复

断言口径：

- 目标片段在 L1 不存在
- 目标片段在 L2 或 L3 可取回
- recall 结果包含目标片段
- recall 返回后的短时间内，目标片段重新进入 L1

建议拆成 3 个 case：

1. `L1 -> L2` 恢复
2. `L1 -> L2 -> L3` 恢复
3. 恢复后再次 recall，tier 应从 `L1` 返回

额外关注：

- 恢复后 `importance` 是否被 `reinforceImportanceOnRecall()` 提升
- `regretTracker.recordRecall(candidate, "L2")` 是否改变 regret 统计

## 3. 恢复一致性

### 目标

验证 checkpoint / recover 后，任务 DAG 不丢节点、不乱序、不断链。

### 推荐测试

在现有 `taskCheckpointRecoverCycle` 基础上补全结构性断言，新增 `TaskRecoveryConsistencyIT`。

步骤：

1. 创建 task
2. 写入一组节点和边，至少包含线性、分叉、合并
3. 更新 context
4. 打 checkpoint
5. checkpoint 后继续追加节点
6. 手动驱逐内存缓存，强制走恢复链路
7. 调用 `/api/v1/tasks/{taskId}/recover`
8. 导出 `/api/v1/tasks/{taskId}/dag`
9. 调用 `/api/v1/tasks/{taskId}`、`/checkpoints`、必要时 `/branches`

断言口径：

- 节点数一致
- 边数一致
- 所有关键节点内容仍存在
- 关键边仍存在
- `latestCheckpointId` 正确
- recover 后还能继续追加节点
- branch/fork/merge 元数据不丢

不要只断言 DOT 字符串包含文本；还要补结构断言：

- 解析 DOT 或直接对 `TaskState` 做断言
- 校验不存在 orphan node
- 校验所有 edge 的 source/target 都存在
- 校验 active branch 没漂移

建议至少补 4 个故障型 case：

1. 从指定 checkpoint recover
2. 从 latest durable state recover
3. checkpoint 后追加节点，再 recover，检查增量 WAL replay
4. 多 branch 场景 recover，检查 branch graph 没断

## 4. 学习有效性

### 目标

验证连续 feedback 后，`/api/v1/memory/learning` 中 active 权重、profile 或 `updateCount` 会真实变化。

### 推荐测试

扩展现有 `feedbackDrivesWeightEvolution`，再补一个以 API 为主的 `MemoryLearningEffectivenessIT`。

步骤：

1. 先调用 `/api/v1/memory/learning?scenario=chat`，记录 before snapshot
2. 连续写入两组可区分片段:
   - 一组应该被用到
   - 一组是干扰项
3. 每轮执行 recall
4. 用 recall 返回的 `recallSessionId` 调 `/api/v1/memory/feedback`
5. 重复 8 到 20 轮
6. 再调用 `/api/v1/memory/learning`

断言口径：

- `pendingRecallSessions` 最终回到 0
- `active.updateCount` 增长
- `shadowEvaluation.sampleCount` 增长
- `active.profileName` 或 `alpha/beta/gamma` 至少有一项变化
- 如满足 promotion 条件，`deployment.state` 可从 `STABLE` 进入 `SHADOW_PROMOTED`

建议分正负两组：

1. 正反馈组: `answerAccepted=true`，`usedFragmentIds` 指向正确片段
2. 负反馈组: `answerAccepted=false` 或 `usedFragmentIds` 为空

要特别观察：

- 不是只看 `updateCount`，还要看 `baselineRelativeLift`、`shadowRelativeLift`
- 如果 updateCount 变了但收益没变，说明学习在“动”，但未必“有效”

建议阶段性通过标准：

- 冒烟: `updateCount > before`
- 进阶: `sampleCount >= N` 且 `baselineRelativeLift >= 0`
- 发布前: `baselineRelativeLift` 稳定高于你设定的业务门槛

## 5. 延迟和健康度

### 目标

验证系统在真实操作后，`/health`、`/slo/report`、`/actuator/prometheus` 的观测面是可信的。

### 推荐测试

新增 `MemoryObservabilityIT` 或脚本化 smoke。

步骤：

1. 执行一轮 store / recall / feedback / checkpoint / recover
2. 调用 `/api/v1/memory/health`
3. 调用 `/api/v1/memory/slo/report`
4. 调用 `/actuator/prometheus`

健康接口必须断言：

- `status`
- `dictionaryVersion`
- `summary`
- `statusReason`
- `details.storeLatencyP99Ms`
- `details.recallLatencyP99Ms`
- `details.checkpointRecoverySuccessRate`
- `details.persistenceSuccessRate`
- `details.learningScenarios`

`/api/v1/memory/slo/report` 必须断言：

- `slo.regretRate`
- `slo.storeLatencyP99Ms`
- `slo.recallLatencyP99Ms`
- `slo.checkpointRecoverySuccessRate`
- `slo.persistenceSuccessRate`
- `regret.modes`
- `learning[*]`
- `signals[*].code`

`/actuator/prometheus` 至少 grep 这些指标：

- `vortex_hmc_slo_checkpoint_recovery_success_rate`
- `vortex_hmc_slo_persistence_success_rate`
- `vortex_hmc_slo_store_latency_p99_ms`
- `vortex_hmc_slo_recall_latency_p99_ms`
- `vortex_hmc_slo_eviction_regret_rate`
- `vortex_hmc_slo_baseline_relative_lift`
- `vortex_hmc_slo_shadow_relative_lift`

这部分建议分两类场景：

1. 健康场景: 期待 `status = UP`
2. 退化场景: 人为制造高 regret、低成功率或高延迟，期待 `status = DEGRADED` 或 `DOWN`

## 建议新增的测试类

当前项目已经有一个不错的骨架，但还不够体系化。建议补齐下面几个类：

- `vortex-app/src/test/java/com/vortex/app/integration/MemoryRecallQualityIT.java`
- `vortex-app/src/test/java/com/vortex/app/integration/MemoryTierRecoveryIT.java`
- `vortex-app/src/test/java/com/vortex/app/integration/TaskRecoveryConsistencyIT.java`
- `vortex-app/src/test/java/com/vortex/app/integration/MemoryLearningEffectivenessIT.java`
- `vortex-app/src/test/java/com/vortex/app/integration/MemoryObservabilityIT.java`

建议新增测试资源：

- `vortex-app/src/test/resources/memory-golden-set.json`
- `vortex-app/src/test/resources/task-dag-scenarios.json`

## 执行节奏

### PR 门禁

运行：

- 单测
- MockMvc API 测试
- 少量 deterministic 集成测试

目标：

- 10 分钟内完成
- 不依赖真实外部 embedding

### Nightly

运行：

- 全量 Testcontainers 集成测试
- 召回质量金标集
- 退化健康场景
- Prometheus scrape 校验

目标：

- 看趋势，不只是看 pass/fail

### 发布前

运行：

- 长时 workload
- store/recall/checkpoint/recover 混合负载
- 观测 health summary 和 Prometheus 指标是否持续达标

## 第一阶段落地顺序

如果你现在“还没有任何测试效果方案”，不要一次做完全部，按这个顺序推进：

1. 先把 `FullLifecycleIT` 扩成 5 个独立指标测试
2. 先建立 `memory-golden-set.json`
3. 先把召回命中率、淘汰后恢复、checkpoint 一致性做成 PR 门禁
4. 再把 learning effectiveness 和 observability 放进 nightly
5. 最后补长时间趋势测试和告警验证

## 最小验收标准

第一版方案建议先达成下面 5 条：

1. 金标 query 集的 `Hit@3` 可自动统计并稳定输出
2. 至少有 1 个用例证明片段从 `L1` 淘汰后仍可从 `L2/L3` 找回
3. 至少有 1 个用例证明 checkpoint/recover 后 DAG 节点数和边数不变
4. 至少有 1 个用例证明 feedback 后 `/api/v1/memory/learning` 的 `active.updateCount` 增长
5. 至少有 1 个用例同时校验 `/api/v1/memory/health`、`/api/v1/memory/slo/report`、`/actuator/prometheus`

做到这一步，这个项目才算从“接口测试”进入“记忆系统效果测试”。
