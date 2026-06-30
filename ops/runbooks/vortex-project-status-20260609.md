# Vortex Project Status Snapshot

更新时间：2026-06-09（Asia/Shanghai）

仓库路径：

```text
E:\1projects\claude\Vortex
```

本文是当前项目状态与后续开发方向的接续快照。它基于仓库结构、README、Maven/CI 配置、核心 runbook、HMC / recall / learning / snapshot / storage / eval / controller 代码、测试分布和本地轻量 governance check 阅读整理。本文不包含任何 API Key 或凭据。

## 1. 当前结论

Vortex 当前不是普通原型，而是一个已经有真实证据链的 Agent memory / state kernel。

最强已证实能力是：

```text
在 v3.1 真实 LLM 长任务 Agent memory workload 中：
Baseline-NoMemory = 0/20
Vortex-Memory = 20/20
Vortex-RecoveredMemory = 20/20

并且在强制 L1 eviction 后，依靠 L2 recovery 仍能全部答对。
```

这证明的是：

1. Vortex 的三层 memory / recovery 链路在受控真实 LLM workload 中有效。
2. 最新 v3.1 baseline 已经进入默认治理门禁。
3. accepted evidence 已迁入 fixture 目录，不再依赖 ignored generated reports。

但这还不能证明：

1. 生产级多租户服务已经完成。
2. adaptive learning 长期一定带来收益。
3. Task DAG 已经接入真实 Agent runtime。
4. 长时间高并发容量稳定性已经充分验证。

## 2. Git 与治理状态

当前主线最新提交为：

```text
351792f Document next-phase learning workload
3554517 Treat actual generation model drift as audit diagnostic
c1bdd56 Document LLM eval evidence asset policy
9771254 Move v3.1 baseline evidence to fixtures
93e637b Promote v3.1 evidence to default governance check
```

当前工作区在阅读时为 clean。

本地轻量门禁已通过：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-baseline-governance-check.ps1 -SkipMavenTest -SkipPackage
```

验证结果：

1. `official-v3.1-real-agent-workload-strict` profile 可列出和 describe。
2. `ops/eval-fixtures/baselines/20260603-v3-1-real-agent-workload-official-strict-audit-003/` 中 3 轮 JSON report 均通过 strict verify。
3. `Baseline governance check passed`。

## 3. 项目架构

项目是 Java 21 / Spring Boot 3.3.4 / Maven 多模块工程：

```text
vortex-common   公共模型、DTO、序列化、异常、健康信号
vortex-storage  L1/L2/L3 存储实现
vortex-kernel   HMC、召回、淘汰、学习、快照、分页、generation
vortex-app      REST API、health、eval CLI、integration tests
```

核心入口：

1. HMC facade：`vortex-kernel/src/main/java/com/vortex/kernel/hmc/HierarchicalMemoryController.java`
2. 召回：`vortex-kernel/src/main/java/com/vortex/kernel/hmc/RecallOrchestrator.java`
3. 自适应学习：`vortex-kernel/src/main/java/com/vortex/kernel/hmc/AdaptiveWeightLearner.java`
4. Shadow evaluation：`vortex-kernel/src/main/java/com/vortex/kernel/hmc/ShadowEvaluationTracker.java`
5. Task DAG / checkpoint facade：`vortex-kernel/src/main/java/com/vortex/kernel/snapshot/SnapshotService.java`
6. Eval runner：`vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalRunner.java`
7. Memory API：`vortex-app/src/main/java/com/vortex/app/controller/MemoryController.java`
8. Task API：`vortex-app/src/main/java/com/vortex/app/controller/TaskController.java`

架构边界总体健康：storage 不直接泄露到 app，kernel 聚合核心行为，app 层提供 REST 和 eval CLI，ops 脚本负责治理。

## 4. 已完成能力

### 4.1 三层记忆

Vortex 已有完整 L1 / L2 / L3 记忆链路：

1. L1：Caffeine hot store，按 token capacity 计量。
2. L2：Milvus warm store，默认 512 维 embedding，支持 tags、namespace search、dimension validation、collection load guard。
3. L3：MinIO cold store，用于 fragment cold archive 与 checkpoint 持久化。

HMC 写入时会：

1. 对 raw content 做 semantic split。
2. 同步生成 L1 BGE embedding。
3. 可选生成 L2 cloud embedding；未启用时回退 BGE。
4. admit 到 L1。
5. 异步持久化到 L2 / L3。

### 4.2 召回链路

`RecallOrchestrator` 已经实现完整召回路径：

1. BGE query embedding。
2. L1 namespace candidates。
3. tag filter。
4. active adaptive profile ranking。
5. topK / token budget 控制。
6. L1 不足时走 L2 vector search。
7. L2 search 不足时走 namespace fallback。
8. L2 candidate enrichment。
9. L2 命中后 re-admit 到 L1。
10. 记录 recall session。
11. 记录 active / shadow / baseline ranking。
12. 输出 detailed recall diagnostics。

这让 eval 失败时可以区分：

```text
召回没拿到 expected fragments
召回拿到了但模型没按 contract 回答
generation/runtime/provider 抖动
```

### 4.3 语义淘汰

`SemanticEvictionPolicy` 已经不是简单 LRU，而是 semantic-LRU 变体：

```text
score = alpha * recency + beta * similarity + gamma * importance
```

并已支持：

1. reasoning chain 分组。
2. redundancy penalty。
3. novelty bonus。
4. pinned fragment 保护。
5. density 排序。
6. adaptive profile。

### 4.4 自适应学习

`AdaptiveWeightLearner` 已实现 bandit / shadow evaluation 机制：

1. 多个 alpha / beta / gamma arms。
2. active profile 与 shadow profile。
3. recall session 保存 active / shadow / baseline ranking。
4. feedback 后计算 answer reward、regret penalty、recall reward、eviction reward、grounding、selection precision、selection coverage。
5. EXP3 风格更新 arm weight。
6. shadow promotion。
7. promotion 后 rollback。
8. `ShadowEvaluationTracker` 记录 active / shadow / baseline NDCG、eviction utility、composite score、relative lift、baseline lift、win rate、sample count。

注意：这是机制已完成，不等于长期收益已由独立 benchmark 证明。

### 4.5 Task DAG / Checkpoint

`SnapshotService` 是 facade，委托给：

```text
TaskLifecycleManager
DagMutationService
RecoveryEngine
BranchManager
DotGraphExporter
IncrementalCheckpointManager
CheckpointScheduler
```

关键语义：

1. validate-before-WAL。
2. WAL-before-state。
3. FULL / DELTA checkpoint。
4. WAL replay。
5. idempotent recovery。
6. branch create / switch / merge。
7. DOT export。
8. checkpoint metadata 独立保存。

这部分已经足以支撑状态管理内核演示，但还没有接真实 Agent runtime。

### 4.6 语义分页与预取

`SemanticPagingManager` 支持：

1. 从 L1 / L2 fragments 构建 semantic pages。
2. page fault 加载整页回 L1。
3. recall 后 semantic neighborhood prefetch。
4. DAG change event 触发 DAG-aware prefetch。
5. branch create / switch 触发 speculative prefetch。
6. page table / prefetch metrics。

它是潜力方向，但不是 v3.1 证据链核心。

### 4.7 REST API 与观测

Memory API 覆盖：

```text
POST   /api/v1/memory/store
POST   /api/v1/memory/store/fragment
GET    /api/v1/memory/fragment/{fragmentId}
DELETE /api/v1/memory/fragment/{fragmentId}
POST   /api/v1/memory/recall
POST   /api/v1/memory/feedback
POST   /api/v1/memory/pin
POST   /api/v1/memory/unpin
GET    /api/v1/memory/health
GET    /api/v1/memory/learning
GET    /api/v1/memory/slo
GET    /api/v1/memory/slo/report
GET    /api/v1/memory/health/catalog
```

Task API 覆盖 task lifecycle、DAG node、edge、checkpoint、recover、branch、merge、DOT export。

观测方面已有：

1. health summary。
2. health signal catalog。
3. SLO snapshot。
4. diagnostic signals。
5. Prometheus / Grafana / Alertmanager 配置。

## 5. Eval 与 Evidence 状态

这是当前最成熟、最有价值的资产。

当前正式基线：

```text
Profile: official-v3.1-real-agent-workload-strict
Dataset: classpath:llm-memory-eval-set-v3-1-real-agent-workload.json
Dataset version: v3.1-real-agent-workload
Case count: 20
Requested model: gpt-5.2
Generation base URL: https://sub2.congmingai.com/v1
L1 max tokens: 96
Prompt SHA-256: e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3
```

Current default fixture：

```text
ops/eval-fixtures/baselines/20260603-v3-1-real-agent-workload-official-strict-audit-003/
```

该 fixture 包含：

```text
baseline-audit-summary.json
runs/*/llm-memory-eval-*.json
```

v3.1 accepted audit 结论：

```text
OverallPassed = true
AuditGate.Passed = true
ProfileGate.Passed = true
StrictVerifierPassed = true
EvalSuccessCount = 3/3
VerifierPassCount = 3/3
CaseFailureCount = 0
TransientRuntimeErrorCount = 0
Baseline-NoMemory = 0/20 in all rounds
Vortex-Memory = 20/20 in all rounds
Vortex-RecoveredMemory = 20/20 in all rounds
RecoveredAccuracy = 1.0 in all rounds
RecoveredL2HitRate = 1.0 in all rounds
```

`ops/run-baseline-governance-check.ps1` 当前默认复验这组 fixture evidence。

## 6. CI 与测试

CI 文件：

```text
.github/workflows/ci.yml
```

CI 当前执行：

```text
mvn -B test -pl vortex-common,vortex-kernel,vortex-storage -am
mvn -B verify -pl vortex-app -am
./ops/run-baseline-governance-check.ps1 -SkipMavenTest -SkipPackage
```

测试文件数量：

```text
51 个 *Test.java / *IT.java
```

覆盖方向：

1. common model / Kryo serialization。
2. L1 / L2 / L3 storage。
3. HMC、recall、eviction、pin、persistence、regret。
4. AdaptiveWeightLearner、ShadowEvaluationTracker、metrics binder。
5. snapshot、WAL、checkpoint、recovery、branch、DAG mutation。
6. semantic paging、prefetch、metrics。
7. REST controller。
8. health / SLO。
9. eval runner、report writer、baseline verifier、CLI。
10. Docker compose integration tests。

`FullLifecycleIT` 默认排除，需要显式启用：

```powershell
mvn verify -pl vortex-app -am -Dvortex.it.fullLifecycleExclude= -Drun.full.lifecycle.it=true
```

## 7. 尚未充分证明的能力

### 7.1 Adaptive learning 长期收益

学习机制已实现，但还没有独立 workload 证明：

```text
多轮 feedback 后，active profile 是否稳定改善 recall ranking / eviction decision。
```

这是当前最大证据缺口。

### 7.2 真实 Agent runtime

v3 / v3.1 eval 用静态 fragments 模拟长任务 Agent memory workload。

尚未完成的是：

1. Agent 执行任务。
2. 每步产生 observation / decision / tool result。
3. 自动写入 memory fragments。
4. 自动 checkpoint task DAG。
5. 重启后恢复。
6. 后续任务通过 recall 接续状态。

### 7.3 生产级 API / 安全 / 多租户

当前 API 更像工程内核接口，还不是外部生产服务接口。缺口包括：

1. tenant model。
2. auth principal。
3. namespace ownership。
4. RBAC / ABAC。
5. request id / idempotency。
6. rate limit。
7. request audit。
8. memory deletion / retention policy。
9. structured error response。
10. OpenAPI contract tests。

### 7.4 长期稳定性与容量

还没有充分证明：

1. 大量 namespace 下的 Milvus collection / search 稳定性。
2. 长时间运行下 L1 token pressure 行为。
3. MinIO checkpoint save / load 的长期容量表现。
4. 高并发 store / recall / feedback tail latency。
5. app restart + recovery 的大量任务场景。

### 7.5 Provider 与成本治理

actual generation model 已进入报告链路，但当前仍是诊断信号：

```text
ActualGenerationModelsStable 参与诊断
AffectsGate = false
AuditGate.Passed 不因 actual model drift 直接失败
```

还缺：

1. provider reliability dashboard。
2. cost accounting。
3. retry budget。
4. model routing / actual model drift policy。

## 8. 当前主要风险

1. 最大风险：adaptive learning 已有机制但缺独立 benchmark，后续改学习参数时没有强证据判断收益或退化。
2. 文档风险：历史 runbook 较多，需要持续明确 v3.1 是当前最新 official strict baseline。
3. CI 风险：GitHub Actions 中 Docker / PowerShell Core / Maven 行为可能与本地不同。
4. 产品风险：在没有 tenant / auth / namespace ownership 前，不应把它包装成外部多租户生产服务。
5. 运行风险：长期 Milvus / MinIO / L1 pressure 还需要 deterministic stress suite。

## 9. 后续开发方向

### P0：保持 v3.1 governance 稳定

状态：已完成，后续只需要防回归。

要求：

1. 不随意修改 `ops/run-baseline-governance-check.ps1` 默认 profile。
2. 不把 generated reports 全部纳入 Git。
3. 新 accepted evidence 必须走 fixture promotion flow。
4. 修改 eval prompt / dataset / L1 tokens / generation model 后，不能直接复用旧 baseline 结论。

### P1：实现 deterministic learning workload harness

这是下一步最应该做的工程动作。

目标 profile：

```text
learning-v1-agent-feedback-audit
```

第一版应无真实 LLM 依赖：

1. 新增 learning dataset，放在 `vortex-app/src/main/resources/`。
2. 新增 learning eval runner。
3. 流程为 store fragments -> calibration recall -> feedback -> probe recall。
4. 以 fragment id ranking / feedback metrics 判定，不做 answer text judging。
5. 输出 JSON / Markdown report 到 `ops/eval-reports/<stamp>/`。
6. 不修改默认 v3.1 governance。

关键指标：

1. `sampleCountBefore` / `sampleCountAfter`
2. `activeUpdateCountBefore` / `activeUpdateCountAfter`
3. `pendingRecallSessions`
4. active / shadow / baseline NDCG
5. active / shadow / baseline eviction utility
6. shadow relative lift
7. baseline relative lift
8. selection precision / coverage
9. median relevant rank before / after
10. probe all-relevant hit rate

候选 gate 可以先保守：

1. `scenarioCount >= 5`
2. `feedbackSampleCount >= 30`
3. `pendingRecallSessions = 0`
4. `activeUpdateCountAfter > activeUpdateCountBefore`
5. `probeAllRelevantHitRate >= 0.90`
6. `activeAverageNdcgAfter >= activeAverageNdcgBefore`
7. `medianRelevantRankAfter <= medianRelevantRankBefore`
8. no scenario has `activeSelectionCoverage = 0.0` after feedback

设计稿：

```text
ops/runbooks/llm-memory-eval-learning-workload-proposal.md
```

### P2：最小真实 Agent runtime 集成

在 learning harness 之后，最有产品价值的是接一个最小 runtime：

1. Agent step abstraction。
2. observation / decision / tool result 自动写入 memory。
3. DAG node 自动追加。
4. checkpoint 自动触发。
5. restart 后 recover。
6. 通过 recall 接续任务状态。

这会把项目从“memory benchmark 通过”推进到“Agent runtime middleware 可用”。

### P3：生产化 API 与安全边界

如果目标是对外服务，需要补：

1. tenantId。
2. auth。
3. namespace ownership。
4. request id / idempotency。
5. structured error response。
6. API versioning。
7. rate limit。
8. audit log。
9. deletion / retention policy。
10. OpenAPI contract tests。

### P4：deterministic stress suite

建议建立非 LLM 的长期压测：

1. 大量 namespace。
2. 大量 fragments。
3. 混合 store / recall / delete / feedback。
4. L1 token pressure。
5. L2 search latency。
6. MinIO checkpoint save / load。
7. app restart + recovery。

这适合作为 nightly 或手动长期压测，不应依赖真实 LLM。

## 10. 不建议下一步做什么

不建议立即做：

1. 继续盲目扩 v3.2 memory dataset。
2. 反复重跑真实 LLM audit 证明 v3.1 已经证明过的事情。
3. 把全部 `ops/eval-reports` 历史产物纳入 Git。
4. 修改默认 `eval-cli verify <report>` 到 v3.1，除非明确评估兼容性影响。
5. 引入 Raft、Netty、自定义协议、多节点共识等大架构改造。
6. 在没有 auth / tenant 的情况下包装成外部生产服务。

## 11. 建议的立即执行清单

按收益排序：

1. 新增 `llm-memory-eval-set-learning-v1-agent-feedback.json`。
2. 新增 learning eval case model。
3. 新增 deterministic learning eval runner。
4. 新增 learning metrics / gate evaluator。
5. 新增 learning report writer。
6. 添加 unit tests。
7. 添加小型 integration test。
8. 添加 ops 脚本。
9. 本地跑 baseline governance，确认不破坏 v3.1。
10. 后续再决定是否把 learning candidate evidence 迁入 fixture。

## 12. 最终判断

Vortex 当前最强定位是：

```text
一个已经通过真实 LLM 长任务 Agent memory workload 验证的分级记忆与状态管理内核。
```

当前最需要补的不是更多 memory/recovery 证明，而是：

```text
证明 adaptive learning 是否能在多轮 feedback 后稳定改善 recall ranking / eviction decision。
```

因此下一步最应该做：

```text
实现 deterministic learning-v1-agent-feedback-audit harness；
保持 v3.1 official strict baseline 不动；
再向真实 Agent runtime 和生产化边界推进。
```
