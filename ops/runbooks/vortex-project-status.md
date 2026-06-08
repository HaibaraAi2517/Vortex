# Vortex 项目状态总结与后续开发方向

更新时间：2026-06-08 20:31（Asia/Shanghai）

仓库路径：

```text
E:\1projects\claude\Vortex
```

本文基于当前仓库工作树、近期提交、核心代码、runbook、测试结构和最新真实 LLM eval evidence 阅读整理。本文不包含任何 API Key。

## 1. 总体结论

Vortex 当前已经不再只是一个“记忆内核原型”。它已经完成了一个非常关键的闭环：

1. 有可运行的三层记忆系统：L1 Caffeine、L2 Milvus、L3 MinIO。
2. 有真实 BGE ONNX embedding、OpenAI-compatible generation、prompt assembly 和真实模型问答评测链路。
3. 有可量化的 eval runner，能比较 `Baseline-NoMemory`、`Vortex-Memory`、`Vortex-RecoveredMemory`。
4. 有多代数据集和 strict baseline profile，从 v2、v2.1、v2.1 extended，推进到 v3 / v3.1 real agent workload。
5. 最新 v3.1 real agent workload 已通过真实模型 3 轮 official strict audit。
6. 有本地/CI 可运行的 baseline governance check，用于无模型、无 API Key 地复验已接受 evidence。
7. 最近四个治理提交已经完成 v3.1 默认门禁、fixture 拆分、证据资产策略和 actual generation model 漂移诊断。

最重要的事实是：

```text
Vortex 已经证明：在受控的真实 LLM 长任务 Agent memory workload 中，接入 Vortex 记忆后，模型回答准确率相对 NoMemory 有决定性提升；并且在 L1 被压低、需要 L2 recovery 的场景下仍能保持正确。
```

但也要明确边界：

```text
当前证明的是“受控 eval workload 下的真实模型记忆增强能力”，不是“生产级多租户 SaaS 记忆平台已经完成”。
```

下一阶段不应该继续无边界地扩数据集或反复跑真实模型。v3.1 这条最新基线已经纳入默认治理门禁，并且 accepted evidence 已迁入 fixture 目录。最值得做的是设计独立的 learning-specific workload，专门证明 adaptive learning 是否能在多轮 feedback 后稳定改善 recall ranking / eviction decision，然后再转向生产化硬化：API/安全/配置治理、长期运行稳定性、真实 Agent 场景集成。

## 2. 当前 Git 状态

当前主线状态：

```text
v3.1 default governance complete
accepted evidence lives under ops/eval-fixtures/baselines
actual generation model drift is an audit diagnostic, not a gate breaker
```

最近提交：

```text
3554517 Treat actual generation model drift as audit diagnostic
c1bdd56 Document LLM eval evidence asset policy
9771254 Move v3.1 baseline evidence to fixtures
93e637b Promote v3.1 evidence to default governance check
ea3eef6 Record actual generation model in eval reports
f8f789f Promote v3.1 real agent workload to official strict baseline
```

最近四个治理提交的意义：

1. `93e637b`：把 baseline governance 默认目标切到 `official-v3.1-real-agent-workload-strict`。
2. `9771254`：把 v3.1 accepted evidence 从 generated reports 迁入 `ops/eval-fixtures/baselines`。
3. `c1bdd56`：补齐 eval evidence asset policy，明确 reports / fixtures / runbooks 的职责边界。
4. `3554517`：把 actual generation model drift 标记为诊断信号，不让上游透明路由误伤 audit gate。

这些提交让项目状态进入 Phase 4 之后的新阶段：v3.1 已经是最新最强的 official strict workload，默认 CI governance 复验的也是这条基线，同时报告开始具备模型身份可追溯能力。

## 3. 项目架构状态

项目是 Maven 多模块 Java 21 / Spring Boot 3.3.4 工程：

```text
vortex-common   公共模型、DTO、序列化、异常、健康信号
vortex-storage  L1/L2/L3 存储实现
vortex-kernel   HMC、召回、淘汰、学习、快照、分页、generation
vortex-app      REST API、health、eval CLI、integration tests
```

根 POM 使用：

```text
Java 21
Spring Boot 3.3.4
Caffeine 3.1.8
Milvus SDK 2.4.4
MinIO 8.5.11
Kryo 5.6.0
DJL 0.28.0
ONNX Runtime 1.18.0
Testcontainers 2.0.2
```

`vortex-app` 会打两个 Spring Boot artifact：

1. 常规应用 jar。
2. `vortex-app-0.1.0-SNAPSHOT-eval-cli.jar`，main class 是 `LlmMemoryEvalCliApplication`，用于独立运行真实 LLM eval 和 baseline verify。

当前架构边界总体健康：核心记忆逻辑在 kernel，存储实现不泄露到 app，eval 是 app 内独立子系统，baseline governance 由 ops 脚本驱动。

## 4. 核心能力现状

### 4.1 分级记忆 HMC

`HierarchicalMemoryController` 是三层记忆系统门面，当前能力包括：

1. 写入文本并通过 `SemanticTextSplitter` 分片。
2. L1 使用 BGE-Small embedding 做本地快速评分。
3. L2 可使用 cloud embedding；未启用时回退到 BGE-Small。
4. 写入后进入 L1，同时异步持久化到 L2/L3。
5. L1 eviction listener 会记录 eviction decision、regret，并触发持久化。
6. 召回委托给 `RecallOrchestrator`。
7. feedback 委托给 `AdaptiveWeightLearner`，并写入 SLO tracker。
8. 支持 fragment pin/unpin、delete、diagnostics snapshot。

这个实现已经从“缓存 + 搜索”升级为“可观测、可学习、可恢复的 memory controller”。

### 4.2 召回链路

`RecallOrchestrator` 的召回路径比较完整：

1. L1 使用 BGE query embedding，对当前 namespace 的候选 fragments 打分。
2. 支持 required tags 过滤。
3. 受 `topK` 和 token budget 约束。
4. L1 不足时走 L2 Milvus search。
5. L2 search 仍不足时走 namespace fallback。
6. 对 L2 candidate 会 enrich：优先 L1 / L3 / L2 找完整 fragment。
7. L2 命中会 admit 回 L1，形成 recovery / reinforcement。
8. 每次召回都会记录 recall session，供 feedback 学习使用。
9. 输出详细 `RecallDiagnostics`：候选数、tag reject、token budget reject、L2 accepted、fallback accepted、empty recall reason 等。

这个诊断粒度是当前项目的优势之一。真实 eval 失败时可以区分：

```text
是召回没拿到 expected fragments，
还是召回拿到了但模型没有按 contract 回答，
还是 runtime / provider 抖动。
```

### 4.3 语义淘汰

`SemanticEvictionPolicy` 当前实现的是 semantic-LRU 变体：

```text
score = alpha * recency + beta * similarity + gamma * importance
```

低分优先淘汰。当前还加入了：

1. reasoningChainId 分组淘汰，避免拆散推理链。
2. redundancy penalty / novelty bonus。
3. pinned fragment 保护。
4. density 排序，即 group score / group token count。
5. adaptive profile 支持。

这说明项目已经不只是简单 LRU，而是围绕 Agent 长任务上下文做了更贴近语义的保留策略。

### 4.4 自适应学习

`AdaptiveWeightLearner` 当前使用 bandit / shadow evaluation 思路：

1. 多个 alpha/beta/gamma arm。
2. active profile 和 shadow profile。
3. recall session 保存 active/shadow/baseline 排名。
4. feedback 后计算 answer reward、regret penalty、recall reward、eviction reward、grounding、precision、coverage。
5. EXP3 风格更新 arm 权重。
6. shadow 满足条件后可 promotion。
7. promotion 后有 rollback 逻辑。

当前学习系统在测试和 eval 报告中已经有观测字段，但从产品证明角度看，它还不是 v3.1 结果的主证据。v3.1 的主要证据仍是 memory/recovery 在固定 workload 上带来的回答准确率提升。

### 4.5 快照与任务 DAG

`SnapshotService` 已经是 facade，委托给：

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
3. 支持 checkpoint、recover、branch、merge、switch、DOT export。
4. L3 MinIO 使用 Kryo binary checkpoint，保留 Jackson legacy fallback。
5. Checkpoint metadata 独立保存。

这部分代表 Vortex 的“状态管理内核”方向，不只是 memory QA。它的代码结构已经比较模块化，但它还没有像 LLM memory eval 一样形成独立的真实 Agent workload 端到端基线。

### 4.6 语义分页

`SemanticPagingManager` 当前支持：

1. 从 L1/L2 fragments 构建 semantic pages。
2. page fault 时加载整页回 L1。
3. recall 后触发 semantic neighborhood prefetch。
4. fragment access 记录。
5. DAG change event 触发 DAG-aware prefetch。
6. branch created/switched 触发 speculative prefetch。

这个方向很有潜力，但目前在项目证据链里属于“已实现并有单测”的子系统，不是最新 v3.1 baseline 结论的核心来源。

## 5. API 与应用层现状

### 5.1 Memory API

`MemoryController` 提供：

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

接口已经覆盖 store、recall、feedback、pin、health、SLO 和 learning。

需要注意的是：这些 API 当前更像工程内核接口，而不是最终用户产品接口。下一阶段如果要产品化，需要补：

1. 认证鉴权。
2. 多租户隔离。
3. namespace 规范。
4. 请求限流。
5. 审计日志。
6. 对外 DTO 稳定性和错误码治理。

### 5.2 Task API

`TaskController` 提供 task lifecycle、DAG node、edge、checkpoint、recover、branch、merge、DOT export。

当前 API 已经能支撑“Agent task state manager”的基本演示，但还缺生产级能力：

1. 操作权限。
2. 幂等请求 ID。
3. 更清晰的 conflict / merge 语义暴露。
4. 大 DAG 分页与查询。
5. 长任务执行器或外部 Agent runtime 的正式集成。

### 5.3 Health / SLO

项目已经有统一 health signal catalog、SLO health indicator 和 memory diagnostic signals。`/api/v1/memory/health` 返回 summary/details，并将状态映射为 HTTP 200 或 503。

Prometheus / Grafana / alertmanager 配置也存在：

```text
ops/prometheus/vortex-memory-slo-alerts.yml
ops/grafana/memory-health-queries.md
ops/alertmanager/memory-health-routes.yml
```

这说明 observability 已经有基础，但仍需要真实长时运行下的数据来校准阈值。

## 6. 存储层现状

### 6.1 L1

L1 是 Caffeine hot store，支持 token capacity，已有 eviction listener 和测试。

在 eval 中，L1 会被压低到：

```text
L1 max tokens = 96
```

这是为了稳定制造 eviction/recovery 场景。生产默认配置是：

```text
vortex.storage.l1.max-tokens = 8192
```

### 6.2 L2

L2 是 Milvus warm store：

1. 默认 collection 是 `vortex_memory`。
2. eval 每轮使用独立 collection。
3. 默认 embedding dim 是 512。
4. 支持 collection dimension validation。
5. 支持 load collection 并等待 load state / loading progress。
6. 支持 tags 字段。
7. 支持 namespace search 和 namespace list fallback。

之前出现过 Milvus eval collections 过多导致 load 卡顿的风险，已经通过 load guard 和 cleanup 脚本治理。

### 6.3 L3

L3 是 MinIO cold store：

1. fragment 存储为 JSON。
2. checkpoint 默认 Kryo + gzip。
3. legacy Jackson checkpoint 可迁移。
4. checkpoint metadata 独立 JSON。
5. 支持 prefix 隔离，eval 每次运行可用独立 MinIO prefix。

这部分的核心风险不是功能缺失，而是生产化配置：bucket policy、凭据管理、对象生命周期、跨环境迁移。

## 7. 真实 LLM Eval 状态

这是当前项目最成熟、证据最强的部分。

### 7.1 Eval Runner 能力

`LlmMemoryEvalRunner` 当前支持：

1. 加载 classpath dataset。
2. 运行 `Baseline-NoMemory`。
3. 运行 `Vortex-Memory`。
4. 运行 `Vortex-RecoveredMemory`。
5. 对 memory modes 先写入 fragments。
6. 等待 expected fragments 到达 L2，避免异步持久化竞态。
7. `Vortex-RecoveredMemory` 中强制制造 L1 eviction。
8. recall 后 prompt assembly。
9. 调真实 `GenerationService`。
10. 规则判分。
11. 正确时可自动提交 feedback。
12. 记录 learning before/after。
13. 记录 generation latency breakdown、HTTP status、attempts、request/response bytes。
14. 支持 mode-phased parallelism。
15. 记录 runtime telemetry。
16. 记录 provider actual generation model。

这是一个相当完整的真实模型 memory benchmark harness。

### 7.2 数据集演进

当前数据集包括：

```text
llm-memory-eval-set.json                         v1
llm-memory-eval-set-v2.json                      v2
llm-memory-eval-set-v2-1.json                    v2.1
llm-memory-eval-set-v2-1-extended.json           v2.1 extended
llm-memory-eval-set-v3-real-agent-workload.json  v3
llm-memory-eval-set-v3-1-real-agent-workload.json v3.1
```

演进方向很清楚：

1. v2/v2.1：稳定 factual memory 和 recovery contract。
2. v2.1 extended：扩大到 30 case。
3. v3：转向更真实的长任务 Agent workload。
4. v3.1：在 v3 基础上扩展到 20 case，并加入更难的状态覆盖、多片段合成、namespace/alias 冲突、checkpoint 延续、历史干扰和策略应用。

### 7.3 最新 official strict baseline

当前最新、最应该引用的真实 LLM memory baseline 是：

```text
Profile: official-v3.1-real-agent-workload-strict
Dataset: classpath:llm-memory-eval-set-v3-1-real-agent-workload.json
Dataset version: v3.1-real-agent-workload
Case count: 20
Requested model: gpt-5.2
Base URL: https://sub2.congmingai.com
L1 max tokens: 96
Eval parallelism: 24
Prompt SHA-256: e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3
```

Accepted post-promotion evidence：

```text
ops/eval-reports/20260603-v3-1-real-agent-workload-official-strict-audit-003/baseline-audit-summary.md
ops/eval-reports/20260603-v3-1-real-agent-workload-official-strict-audit-003/baseline-audit-summary.json
```

审计结果：

```text
OverallPassed = true
AuditGate.Passed = true
ProfileGate.Passed = true
StrictVerifierPassed = true
EvalSuccessCount = 3/3
VerifierPassCount = 3/3
CaseFailureCount = 0
CaseFailureGroupCount = 0
TransientRuntimeErrorCount = 0
Baseline-NoMemory = 0/20, 0/20, 0/20
Vortex-Memory = 20/20, 20/20, 20/20
Vortex-RecoveredMemory = 20/20, 20/20, 20/20
RecoveredAccuracy = 1.0, 1.0, 1.0
RecoveredL2HitRate = 1.0, 1.0, 1.0
```

Runtime telemetry：

```text
Runs with telemetry: 3
Configured parallelism: 24, 24, 24
Actual worker count: 20, 20, 20
Mean total elapsed ms: 39269
```

这组结果非常强。它说明在 v3.1 的 20 个更接近真实 Agent 长任务记忆场景中：

1. 无记忆模式完全不能答对。
2. 有 Vortex 记忆后全部答对。
3. 强制 L1 eviction 后，依靠恢复路径仍全部答对。

### 7.4 Baseline profiles

当前 `LlmMemoryEvalBaselineProfile` 中有 10 个 profile：

```text
official-v2-strict
audit-v2-stability
official-v2.1-strict
contract-v2.1-candidate
official-v2.1-extended-strict
candidate-v2.1-extended
official-v3-real-agent-workload-strict
audit-v3-real-agent-workload
official-v3.1-real-agent-workload-strict
candidate-v3.1-real-agent-workload
```

默认 `eval-cli verify <report>` 仍是：

```text
official-v2-strict
```

这不是遗漏，是兼容性设计。v3/v3.1 报告必须显式传 `--profile`，或者由 audit 脚本按 dataset 推断。

## 8. Governance 与 CI 状态

### 8.1 Baseline governance check

`ops/run-baseline-governance-check.ps1` 是无模型治理门禁。它不调用真实 generation API，不需要 API Key，主要做：

1. 可选 Maven test。
2. 可选 package eval CLI。
3. `verify --list-profiles`。
4. `verify --profile <profile> --describe`。
5. 检查 accepted evidence summary。
6. 逐轮 strict verify 既有 JSON report。

### 8.2 CI

`.github/workflows/ci.yml` 当前包含：

```text
mvn -B test -pl vortex-common,vortex-kernel,vortex-storage -am
mvn -B verify -pl vortex-app -am
./ops/run-baseline-governance-check.ps1 -SkipMavenTest -SkipPackage
```

CI 使用 Ubuntu runner + JDK 21 + PowerShell Core。

### 8.3 重要治理闭环

v3.1 默认治理缺口已经处理完成。`ops/run-baseline-governance-check.ps1` 当前默认指向：

```text
official-v3.1-real-agent-workload-strict
20260603-v3-1-real-agent-workload-official-strict-audit-003
```

默认 evidence root 是：

```text
ops/eval-fixtures/baselines
```

当前状态应解释为：

1. v3.1 已在代码和本地真实 audit 中晋升成功。
2. v3.1 accepted evidence 已迁入 `ops/eval-fixtures/baselines/20260603-v3-1-real-agent-workload-official-strict-audit-003/`。
3. CI 默认 governance check 复验的是 v3.1 fixture evidence，不再依赖 ignored `ops/eval-reports` 输出。
4. `.gitignore` 对新的 generated eval reports 继续保持保守策略，避免误提交临时失败报告和 Markdown 输出。

下一步不再是继续搬 evidence，而是为 adaptive learning 单独设计 workload 和指标，避免把学习收益证明混入 v3.1 memory/recovery baseline。

## 9. Actual model identity 改动状态

提交 `ea3eef6` 已经补上 provider 实际返回模型记录；提交 `3554517` 已经把 actual model drift 纳入 audit summary 诊断视图，并明确它不影响 `AuditGate.Passed`。

数据流：

```text
OpenAiCompatibleGenerationService parsed.model()
-> GenerationResult.model
-> LlmMemoryEvalResult.actualGenerationModel
-> LlmMemoryEvalEnvironmentSnapshot.actualGenerationModels
-> report Markdown / JSON
-> baseline audit summary Aggregate / AuditGate
```

报告中现在会区分：

```text
environment.generationModel        requested model，例如 gpt-5.2
result.actualGenerationModel       provider 返回的实际模型，例如 gpt-5.4
environment.actualGenerationModels 本轮报告去重后的实际模型集合
```

这个改动非常必要。真实审计中曾观察到 provider response log 的 model 与 requested model 不一致。如果不记录 actual model，后续 baseline 漂移会很难判断到底是代码、数据、prompt 变化，还是 provider 后端实际模型变化。

当前 strict verifier 仍只验证 requested model，不强制 actual model。这是合理的，因为旧报告没有该字段，且 provider 实际模型可能是上游路由结果。`ops/run-llm-memory-baseline-audit.ps1` 会在 summary 中输出 `ActualGenerationModelsStable`，但该检查的 `AffectsGate=false`。也就是说，actual model drift 会被显式报告为环境诊断，不会直接导致 `AuditGate.Passed=false`。

## 10. 测试状态

当前仓库有：

```text
51 个 *Test.java / *IT.java
```

覆盖方向包括：

1. common model / serialization。
2. L1 Caffeine。
3. L2/L3 storage。
4. HMC、recall、eviction、learning、regret。
5. snapshot、checkpoint、WAL、recovery、branch、DAG mutation。
6. semantic paging、prefetch、metrics binder。
7. app controller。
8. memory health。
9. eval runner、report writer、baseline verifier、CLI application、startup runner。
10. integration tests：checkpoint retention、recovery failure、Docker compose、full lifecycle。

最近本地已验证：

```powershell
powershell -NoProfile -Command "[void][scriptblock]::Create((Get-Content -Raw '.\ops\run-llm-memory-baseline-audit.ps1')); 'parse-ok'"
mvn --% -q -pl vortex-app -am -Dtest=LlmMemoryEvalRunnerTest,LlmMemoryEvalReportWriterTest,LlmMemoryEvalExecutionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn --% -q -pl vortex-app -am -DskipTests package
```

结果均为 exit code 0。

需要注意：Maven 测试日志中会出现一些 ERROR 日志，这是测试故意模拟 runtime failure / generation timeout，不等于测试失败。

## 11. 已证实的能力

当前可以较有把握地说，项目已经证实：

1. 真实 BGE +真实 LLM generation + Vortex memory 可以组成可运行闭环。
2. 在 v3.1 20-case 长任务 Agent workload 中，NoMemory 模式无法回答，Vortex memory 模式全部回答正确。
3. 在低 L1 token capacity、强制 eviction 后，L2 recovery 路径仍然能支撑全部回答正确。
4. 真实 eval 可以自动输出 JSON / Markdown 报告。
5. strict verifier 可以把报告转成机器可验证的 baseline contract。
6. audit 脚本可以执行多轮真实模型审计，并聚合 profile gate、audit gate、runtime telemetry、case failure summary。
7. provider actual model 现在可以进入报告链路。
8. baseline governance 已经具备无模型回归门禁能力。

这些结论是当前项目最有价值的资产。

## 12. 尚未充分证明的能力

当前还不能过度宣称以下能力：

### 12.1 生产级稳定性

真实 eval 是多轮受控运行，不是长时间在线服务压测。还没有充分证明：

1. 长时间运行下 Milvus / MinIO / L1 的资源变化。
2. 大量 namespace 下的索引和隔离稳定性。
3. 高并发多用户下的 tail latency。
4. 容器重启、网络抖动、对象存储异常下的恢复体验。

### 12.2 多租户与安全

当前 API 没有完整产品级鉴权和租户边界。namespace 是逻辑字段，但生产环境需要：

1. tenantId。
2. auth principal。
3. namespace ownership。
4. RBAC / ABAC。
5. request audit。
6. rate limit。
7. sensitive memory redaction / deletion。

### 12.3 学习系统的长期收益

`AdaptiveWeightLearner` 已实现，但 v3.1 结论主要证明 memory/recovery，不等于已经证明 adaptive learning 长期能带来稳定收益。下一阶段要单独设计 learning workload 和指标。

### 12.4 Task DAG 与真实 Agent runtime 集成

任务 DAG、checkpoint、branch 已实现，但还缺一个真实 Agent runtime 把任务执行、记忆写入、checkpoint continuation、branch decision 串起来。v3/v3.1 eval 用 memory fragments 模拟 Agent workload，但还不是完整 Agent executor。

### 12.5 成本、配额与模型供应商波动治理

真实 audit 曾遇到 provider timeout / preflight 抖动。现在 eval 可以分类 runtime error，但还没有形成完整的 provider reliability dashboard、成本统计、重试预算、模型变更审计策略。

## 13. 当前主要风险

### 风险 1：adaptive learning 还缺独立证据链

`AdaptiveWeightLearner` 已经有 bandit、shadow evaluation、feedback update、promotion / rollback 机制，也有单测和集成测试覆盖。但这些还不能等价于“长期学习收益已经被真实 workload 证明”。

当前最高价值风险是：如果没有独立 learning workload，后续很难判断学习参数变化到底改善了 recall / eviction，还是只是在固定 memory/recovery workload 上没有退化。

### 风险 2：文档存在历史状态与最新状态不一致

部分旧 runbook / 状态文档仍说 v2.1 extended 或 v3 是当前推荐基线。它们作为历史材料可以保留，但面向接续开发的文档必须明确：

```text
最新 official strict baseline 是 v3.1。
```

### 风险 3：真实模型 provider actual model 漂移

已新增记录字段，audit summary 也能报告 actual model drift。当前策略是诊断而不是阻断门禁。未来如果要把它变成强门禁，必须先确认 provider 是否存在透明路由，否则会把上游路由行为误判为 Vortex regression。

### 风险 4：CI 环境仍可能暴露 Docker / PowerShell / Maven 差异

本地通过不等于 GitHub Actions 必然通过。尤其是：

1. `mvn verify -pl vortex-app -am` 依赖 Docker compose。
2. PowerShell Core 在 Ubuntu 上路径和通配符行为可能与 Windows 有差异。
3. eval CLI jar 必须由前一步 package/verify 生成。

## 14. 后续开发方向

### P0：v3.1 默认治理门禁

状态：已完成。

当前默认参数：

```text
Profile = official-v3.1-real-agent-workload-strict
EvidenceStamp = 20260603-v3-1-real-agent-workload-official-strict-audit-003
ReportRoot = ops/eval-fixtures/baselines
```

验证命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-baseline-governance-check.ps1 -SkipMavenTest -SkipPackage
```

### P1：actual model identity 进入治理视图

状态：已完成第一阶段。

当前报告链路已经记录 requested model 与 actual generation model；audit summary 会输出 actual model stability 诊断。当前策略是：

```text
ActualGenerationModelsStable 参与诊断
AffectsGate = false
AuditGate.Passed 不因 actual model drift 直接失败
```

这个策略适合当前阶段，因为 provider 可能存在透明路由。等积累更多 evidence 后，再决定是否把 actual model stability 升级为强门禁。

### P2：整理 eval evidence 资产管理

状态：已完成第一阶段。

当前职责边界：

```text
ops/eval-reports/                 本地生成，默认 ignored
ops/eval-fixtures/baselines/       CI 必需的最小 accepted JSON
ops/runbooks/...                   决策文档引用 fixture
```

这已经避免 `.gitignore` allowlist 继续膨胀，也降低了误提交临时报告的风险。不要迁移全部历史 evidence，除非有明确清理任务。

### P3：设计 learning-specific workload

当前 v3.1 主要证明 memory/recovery。下一类真正有价值的 benchmark 是：

```text
同一类任务经过 feedback 后，recall ranking / eviction decision 是否稳定改善。
```

建议新增独立 learning workload：

1. 多轮同 namespace。
2. 初始有多个相似 fragments。
3. 用户 feedback 指定实际有用 fragments。
4. 后续同类 query 观察 active profile ranking 是否提升。
5. 输出 shadow lift、baseline lift、selection precision、selection coverage、active/shadow/baseline NDCG、learning sample / update deltas。

不要把它混入 v3.1 official strict baseline。它应该是独立 profile，例如：

```text
learning-v1-agent-feedback-audit
```

设计稿见：

- [llm-memory-eval-learning-workload-proposal.md](E:/1projects/claude/Vortex/ops/runbooks/llm-memory-eval-learning-workload-proposal.md:1)

### P4：真实 Agent runtime 集成

当前 eval 用静态 fragments 模拟 Agent 长任务记忆。下一步产品价值更高的是接一个最小 Agent runtime：

1. Agent 执行任务。
2. 每步产生 observation / decision / tool result。
3. Vortex 自动存 memory fragments。
4. checkpoint task DAG。
5. 中途重启恢复。
6. 后续问题或任务继续时通过 recall 找回状态。

这会把 Vortex 从“memory benchmark 通过”推进到“Agent runtime middleware 可用”。

### P5：生产化 API 与安全

如果目标是可对外使用，需要补：

1. tenant model。
2. auth。
3. namespace ownership。
4. request id / idempotency。
5. structured error response。
6. API versioning。
7. memory deletion / retention policy。
8. secret handling。
9. rate limiting。
10. OpenAPI contract tests。

### P6：长期稳定性与容量压测

建议建立非 LLM 的 deterministic stress suite：

1. 大量 namespace。
2. 大量 fragments。
3. 混合 store / recall / delete / feedback。
4. L1 token pressure。
5. L2 collection load / search latency。
6. MinIO checkpoint save/load。
7. app restart + recovery。

这类测试不应该依赖真实模型，适合作为 nightly 或本地长期压测。

## 15. 不建议下一步做什么

不建议立即做：

1. 继续盲目扩 v3.2 数据集。
2. 反复重跑真实 LLM audit 证明同一件事。
3. 把所有 `ops/eval-reports` 历史产物纳入 Git。
4. 修改默认 `eval-cli verify` 到 v3.1，除非明确评估兼容性影响。
5. 引入 Raft、Netty、自定义协议、多节点共识等大架构改造。
6. 在还没有 auth / tenant 的情况下包装成外部生产服务。

当前最稀缺的不是“更多功能”，而是：

```text
把已经证明有效的 v3.1 baseline 固化、让证据资产可维护、让系统具备生产化边界。
```

## 16. 建议的下一步执行清单

按收益排序：

1. 设计 learning-specific workload，形成独立 profile 和指标门槛。
2. 优先做确定性 learning harness，避免第一版依赖真实 LLM 和 provider 抖动。
3. 把 workload 设计映射到现有 `AdaptiveWeightLearner`、`RecallSessionRecord`、`ShadowEvaluationTracker` 指标。
4. 明确它不替代 v3.1 official strict baseline，也不修改默认 governance。
5. 本地继续跑 baseline governance check，确保新文档或脚本不破坏 v3.1 门禁。
6. 推送后观察 GitHub Actions。
7. 设计最小真实 Agent runtime 集成。
8. 再考虑 v3.2 memory workload，而不是现在马上扩。

## 17. 常用命令

查看状态：

```powershell
git status --short
git log --oneline -5
```

打包 eval CLI：

```powershell
mvn -pl vortex-app -am -DskipTests package
```

列出 baseline profiles：

```powershell
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify --list-profiles
```

查看 v3.1 strict profile：

```powershell
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify `
  --profile official-v3.1-real-agent-workload-strict `
  --describe
```

运行当前 baseline governance check：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-baseline-governance-check.ps1
```

运行 v3.1 真实 audit 示例：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-llm-memory-baseline-audit.ps1 `
  -ApiKey '<API_KEY>' `
  -BaseUrl 'https://sub2.congmingai.com' `
  -Model 'gpt-5.2' `
  -Rounds 3 `
  -DatasetLocation 'classpath:llm-memory-eval-set-v3-1-real-agent-workload.json' `
  -AuditStamp '20260603-v3-1-real-agent-workload-official-strict-audit' `
  -EvalParallelism 24 `
  -SkipComposeUp `
  -FailOnAuditGateFailure
```

注意：真实 audit 需要 API Key；baseline governance check 不需要。

## 18. 最终判断

当前 Vortex 的最强定位是：

```text
一个已经通过真实 LLM 长任务 Agent memory workload 验证的分级记忆与状态管理内核。
```

它的当前强项是：

1. 三层 memory/recovery 能力真实有效。
2. eval/governance 体系明显强于一般原型项目。
3. baseline profile 和 evidence 决策链清楚。
4. 诊断字段足够丰富，便于定位 memory、generation、judge、provider 的不同问题。

它的当前短板是：

1. adaptive learning 还缺独立 workload 证明长期收益。
2. task DAG 还缺真实 Agent runtime 级别的证明。
3. 生产级 API、安全、多租户和长期运行稳定性尚未完成。
4. provider actual model drift 目前只是诊断信号，还没有形成长期供应商稳定性策略。

因此，下一步最值得做的是：

```text
先为 adaptive learning 设计独立 benchmark；
然后停止继续横向扩 memory/recovery benchmark，转向 production hardening 和真实 Agent runtime 集成。
```
