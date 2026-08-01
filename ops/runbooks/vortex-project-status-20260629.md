# Vortex Project Status - 2026-06-29

生成日期：2026-06-29（Asia/Shanghai）

本文是当前项目状态快照，用于后续交接、提交前审查、README/简历材料整理。本文已按 2026-06-29 main-path latency benchmark 更新过期 latency claim。

## Executive Summary

当前 `ops/runbooks/目标.md` 中的工程能力目标已经基本闭环，三个核心技术指标均已有可引用证据：

- Hybrid Retrieval：`Recall@5 +28.75% relative lift`
- Runtime Recovery：`32/32 covered cases`，covered-case recovery success rate `100.00%`
- Async Memory Pipeline：deterministic main-path benchmark 中 P99 latency 从 `1172.50 ms` 降至 `220.34 ms`，平均 latency 从 `829.40 ms` 降至 `186.64 ms`，平均降低 `77.50%`，主路径与 L2/L3 readiness success rate 均为 `100.00%`

当前 GitHub Stars：`0`（用户在 2026-06-29 提供）。这是展示/传播状态，不是工程实现缺口。

当前最重要的工程事实：

- 异步 Memory ingest pipeline 已实现，并接入 REST API 与 eval CLI benchmark。
- Recall、runtime recovery、main-path latency / async memory pipeline 三条证据链均有 runbook 和报告文件。
- 定向测试与 package 最近一次通过。
- 工作区仍然很脏，包含大量 modified/untracked 文件；不要清理或回滚用户/前序工作。
- `README.md` 当前是 deleted 状态；除非明确要求，不要自动恢复。

## User Instruction Boundary

当前维护边界：

- 不要恢复或重写 deleted `README.md`，除非用户明确要求。
- 不要清理、reset 或 checkout 当前大量 modified/untracked 工作区。
- 对外材料以本文件、handoff 和 main-path latency evidence 的新口径为准。

## Goal Alignment

目标文件：

- `ops/runbooks/目标.md`

当前目标文案中的工程指标可支撑状态：

| 方向 | 当前可防守结果 | 证据状态 |
| --- | ---: | --- |
| Hybrid Retrieval | `Recall@5 +28.75%` | 已有真实 Milvus + BGE-Small recall benchmark |
| Runtime Recovery | `32/32 covered cases`，covered-case recovery success rate `100.00%` | 已有 deterministic runtime recovery benchmark |
| Async Memory Pipeline | P99 `1172.50 ms -> 220.34 ms`，平均 `829.40 ms -> 186.64 ms`，平均降低 `77.50%` | 已有 Docker-backed Milvus/MinIO main-path benchmark |
| Stars | `0` | 用户提供；不属于工程能力证据 |

注意：

- `Stars 0` 不建议作为核心卖点突出展示。
- 如果后续整理 README/简历，建议把技术指标前置，把 Stars 字段移到非核心位置或省略。但这一步需要用户明确允许修改对应文档。

## Evidence Inventory

### Hybrid Recall Benchmark

证据文档：

- `ops/runbooks/vortex-recall-benchmark-evidence-20260626.md`

报告文件：

- `ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001/recall-benchmark-20260625-162328.json`
- `ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001/recall-benchmark-20260625-162328.md`
- `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.json`
- `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.md`

条件：

- Dataset：`classpath:llm-memory-eval-set-v3-1-real-agent-workload.json`
- Cases：20
- Runs：40
- Modes：`VORTEX_VECTOR_ONLY`, `VORTEX_MEMORY`
- TopK：1 和 5
- Runtime：Docker-backed Milvus + BGE-Small embeddings
- Candidate pool：shared namespace candidate pool
- Generation：disabled

关键结果：

| TopK | Mode | Recall@K | Absolute Lift | Relative Lift | Case Hit Rate | NDCG | Avg Latency Ms | Errors |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | Vortex-VectorOnly | 0.2667 | 0.0000 | 0.0000 | 0.5500 | 0.5500 | 187.55 | 0 |
| 1 | Vortex-Memory | 0.3833 | 0.1167 | 0.4375 | 0.8000 | 0.8000 | 245.55 | 0 |
| 5 | Vortex-VectorOnly | 0.6667 | 0.0000 | 0.0000 | 0.9000 | 0.5907 | 199.20 | 0 |
| 5 | Vortex-Memory | 0.8583 | 0.1917 | 0.2875 | 1.0000 | 0.7825 | 298.30 | 0 |

允许 claim：

> Hybrid Retrieval 在 v3.1 real-agent workload deterministic recall benchmark 中，相对 Vector-only 的 Recall@5 从 0.6667 提升到 0.8583，relative lift 为 +28.75%。

不允许 claim：

- 不要说 LLM answer accuracy 提升 +28.75%。
- 不要说所有 workload 或所有 TopK 都提升。
- 不要用这组 recall benchmark 证明 latency 降低。

### Runtime Recovery Benchmark

证据文档：

- `ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md`

报告文件：

- `ops/eval-reports/20260629-runtime-recovery-benchmark-003/runtime-recovery-benchmark-20260629-135453.json`
- `ops/eval-reports/20260629-runtime-recovery-benchmark-003/runtime-recovery-benchmark-20260629-135453.md`

条件：

- Command：`runtime-recovery-benchmark`
- Total cases：32
- Passed cases：32
- Failed cases：0
- Success rate：`1.0000`
- Average measured case latency：`68.7813 ms`

覆盖能力：

- Service restart
- Tool failure
- LLM exception
- State integrity
- Concurrency
- Checkpoint/WAL/runtime-state recovery
- Execution ID idempotent recovery

允许 claim：

> 覆盖 Service Restart / Tool Failure / LLM Exception / State Integrity / Concurrency 五类异常共 32 个 deterministic recovery cases，基于 checkpoint/WAL/runtime-state 与 Execution ID 幂等恢复，benchmark 中 32/32 通过状态一致性校验。

不允许 claim：

- 不要说完整生产 Agent Runtime 恢复成功率 100%。
- 不要说所有真实故障场景已覆盖。
- 不要说外部 process-manager crash-loop orchestration 已覆盖。
- 不要说跨历史二进制版本 snapshot schema migration 已覆盖。

### Main-Path Latency Benchmark

证据文档：

- `ops/runbooks/vortex-main-path-latency-benchmark-evidence-20260629.md`

报告文件：

- `ops/eval-reports/20260629-main-path-latency-benchmark-003/async-pipeline-latency-benchmark-20260629-151448.json`
- `ops/eval-reports/20260629-main-path-latency-benchmark-003/async-pipeline-latency-benchmark-20260629-151448.md`

条件：

- Command：`async-pipeline-latency-benchmark`
- Main path：request -> hybrid retrieval -> rerank -> prompt/context assembly -> return payload
- Excluded：external LLM generation
- Background async pipeline：extraction, summary, semantic split, embedding, L1 admission, L2 index, L3 archive
- Fragment count：`16`
- Warmup fragment count：`2`
- Modes：`SYNC_BASELINE`, `ASYNC_PIPELINE`
- Embedding：local BGE-Small, `dim=512`
- L1：Caffeine，`VORTEX_STORAGE_L1_MAX_TOKENS=32768`
- L2：Docker-backed Milvus collection `vortex_memory_main_path_latency_20260629_003`
- L3：Docker-backed MinIO prefix `main-path-latency-benchmark/20260629-003/`

关键结果：

| Mode | Main Avg Ms | Main P50 Ms | Main P95 Ms | Main P99 Ms | Recall P95 Ms | Prompt P95 Ms | Write Submit P95 Ms | Main Success | Persistence Success | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 829.3997 | 863.9291 | 1172.5000 | 1172.5000 | 485.2810 | 13.1784 | 952.8537 | 1.0000 | 1.0000 | 0 |
| ASYNC_PIPELINE | 186.6363 | 184.6629 | 220.3377 | 220.3377 | 206.9066 | 13.5065 | 0.1981 | 1.0000 | 1.0000 | 0 |

Derived：

- Sync average main-path latency：`829.3997 ms`
- Async average main-path latency：`186.6363 ms`
- Relative average main-path latency reduction：`0.7750` / `77.50%`
- Sync main-path P99：`1172.5000 ms`
- Async main-path P99：`220.3377 ms`
- Sync returned fragment average：`5.0000`
- Async returned fragment average：`5.0000`
- Main-path success rate：`100.00%`
- L2/L3 readiness success rate：`100.00%`

Background readiness：

| Mode | Pipeline Avg Ms | Pipeline P95 Ms | Pipeline TPS | Readiness P95 Ms | Readiness Lag Avg Ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 155.8646 | 180.0704 | 6.4158 | 1172.5084 | 0.0162 |
| ASYNC_PIPELINE | 151.5252 | 164.6405 | 6.5996 | 972.1643 | 585.9576 |

允许 claim：

> 将 Memory 抽取 / 摘要 / Embedding / L1 admission / L2 indexing / L3 archive 移出请求主路径；在 Docker-backed Milvus/MinIO deterministic main-path benchmark 中，主路径 P99 从 `1172.50 ms` 降至 `220.34 ms`，平均主路径 latency 从 `829.40 ms` 降至 `186.64 ms`，主路径与 L2/L3 readiness 成功率均为 `100.00%`。

不允许 claim：

- 不要再说主链路降低 `99.99%`。
- 不要说完整 Agent end-to-end latency，因为没有包含真实 LLM generation。
- 不要说生产 p95/p99 行为，只能说 deterministic benchmark。
- 不要隐藏 async readiness latency；本次 async readiness P95 是 `972.1643 ms`，readiness lag avg 是 `585.9576 ms`。

## Implemented Capabilities

### Hybrid Retrieval

已实现：

- Vector-only control mode
- Keyword recall candidates
- Vector recall candidates
- Hybrid reranking
- Recall diagnostics
- Shared namespace candidate pool benchmark

关键文件：

- `vortex-common/src/main/java/com/vortex/common/dto/RetrievalMode.java`
- `vortex-common/src/main/java/com/vortex/common/dto/RecallDiagnostics.java`
- `vortex-common/src/main/java/com/vortex/common/dto/RecallQuery.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/KeywordRecallIndex.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HybridRecallReranker.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/RecallOrchestrator.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkExecutionService.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkReportWriter.java`

### Runtime Recovery

已实现：

- Task DAG checkpoint/recover
- WAL replay
- Dirty set / delta checkpointing
- Branch and merge recovery
- Execution ID replay idempotency
- Conversation state recovery
- Tool failure state recovery
- LLM timeout retry recovery
- Runtime recovery benchmark CLI/report

关键文件：

- `vortex-common/src/main/java/com/vortex/common/model/ConversationMessage.java`
- `vortex-common/src/main/java/com/vortex/common/model/ConversationState.java`
- `vortex-common/src/main/java/com/vortex/common/model/ToolExecutionState.java`
- `vortex-common/src/main/java/com/vortex/common/model/ToolExecutionStatus.java`
- `vortex-common/src/main/java/com/vortex/common/model/LlmCallState.java`
- `vortex-common/src/main/java/com/vortex/common/model/LlmCallStatus.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/RuntimeMutationService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/RecoveryEngine.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/SnapshotService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/IncrementalCheckpointManager.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/CheckpointDelta.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/DirtySetTracker.java`
- `vortex-app/src/main/java/com/vortex/app/runtime/`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkExecutionService.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkReportWriter.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryTaskCacheEvictor.java`

### Async Memory Pipeline

已实现：

- Async request admission
- Memory extraction
- Summary
- Semantic split
- Embedding
- L1 admission
- L2 indexing
- L3 archive
- Pipeline status snapshot
- Blocking baseline path for benchmark
- REST async store/status endpoints
- Async pipeline latency benchmark CLI/report

关键文件：

- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/AsyncMemoryPipeline.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryExtractionService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemorySummaryService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineRequest.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStatus.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStage.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStatusCode.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HierarchicalMemoryController.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/FragmentPersistenceManager.java`
- `vortex-app/src/main/java/com/vortex/app/controller/MemoryController.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkExecutionService.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkReportWriter.java`

REST API：

- `POST /api/v1/memory/store/async`
- `GET /api/v1/memory/pipeline/{pipelineId}`

CLI command：

```text
async-pipeline-latency-benchmark
```

## Validation

最近一次定向测试：

```powershell
mvn -pl vortex-kernel,vortex-app -am "-Dtest=AsyncMemoryPipelineTest,AsyncPipelineLatencyBenchmarkRunnerTest,AsyncPipelineLatencyBenchmarkReportWriterTest,MemoryControllerTest,LlmMemoryEvalCliApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

- 时间：2026-06-28 22:02 Asia/Shanghai
- `Tests run: 32, Failures: 0, Errors: 0, Skipped: 0`
- `BUILD SUCCESS`

最近一次 package：

```powershell
mvn -DskipTests package
```

结果：

- 时间：2026-06-28 22:01 Asia/Shanghai
- `BUILD SUCCESS`
- Eval CLI jar 生成：`vortex-app/target/vortex-app-0.1.0-eval-cli.jar`

最近一次真实 Docker-backed main-path latency benchmark：

```powershell
$env:LOGGING_LEVEL_COM_VORTEX='INFO'
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260629-main-path-latency-benchmark-003'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_main_path_latency_20260629_003'
$env:MINIO_KEY_PREFIX='main-path-latency-benchmark/20260629-003/'
$env:BGE_MODEL_PATH='models/bge-small-zh'
$env:VORTEX_STORAGE_L1_MAX_TOKENS='32768'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_FRAGMENTS='16'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_WARMUP_FRAGMENTS='2'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_ASYNC_PARALLELISM='4'
$env:VORTEX_MEMORY_PIPELINE_MAX_WORKERS='4'
$env:VORTEX_MEMORY_PIPELINE_QUEUE_CAPACITY='8'
$env:VORTEX_SCHEDULER_ENABLED='false'
java -jar .\vortex-app\target\vortex-app-0.1.0-eval-cli.jar async-pipeline-latency-benchmark
```
结果：

- Sync average main-path latency：`829.3997 ms`
- Async average main-path latency：`186.6363 ms`
- Sync main-path P99：`1172.5000 ms`
- Async main-path P99：`220.3377 ms`
- Relative average main-path latency reduction：`77.50%`
- Main-path and L2/L3 readiness success rate：`100.00%`

## Current Working Tree

当前工作区不是干净状态。不要做自动清理、reset、checkout 或删除文件。

已知状态：

- `README.md`：deleted。不要恢复，除非用户明确要求。
- 多个源码文件处于 modified 状态。
- 多个 runbook、benchmark 类、runtime model、async pipeline 类仍是 untracked。
- `ops/runbooks/目标.md` 是 untracked；当前已按 main-path benchmark 更新过期 latency claim。
- `readme-history/`、`简历建议5.md` 保持原状，不要清理。

重要 modified/untracked 范围：

- Eval CLI / reports / benchmark runner
- Memory controller async endpoint
- Hierarchical memory controller persistence path
- Recall orchestrator / hybrid retrieval
- Runtime snapshot/recovery
- Runtime app APIs/tests
- Evidence runbooks and handoff docs

## Risks And Boundaries

### Technical Risks

- `AsyncMemoryPipeline` 当前状态保留在内存 map 中，受 `max-statuses` 限制；不是持久化 pipeline state。
- Main-path benchmark 覆盖 request -> hybrid retrieval -> rerank -> prompt/context assembly -> return payload，但不包含真实 LLM generation。
- Runtime recovery benchmark 是 deterministic covered-case，不是生产故障全集。
- Recall benchmark 是 deterministic retrieval recall，不是 LLM answer correctness。
- 默认 Milvus collection `vortex_memory` 可能仍存在历史维度风险；真实 benchmark 应继续使用 isolated collection。

### Documentation Risks

- `README.md` 处于 deleted 状态，仓库展示面目前不完整。
- `Stars 0` 不适合作为当前核心卖点。
- `目标.md` 仍包含展示字段，后续如要改文案必须先获得用户许可。

### Repository Hygiene Risks

- 工作区 modified/untracked 文件很多，提交前需要拆分检查。
- 不要把 unrelated 或用户上下文文件误删。
- 不要恢复 `README.md`，除非用户明确要求。

## Recommended Next Steps

最应该做的下一步不是继续加功能，而是提交前工程整理：

1. 做一次 code-review 风格自查，重点看 async pipeline failure path、status lifecycle、readiness 口径、REST API 参数校验。
2. 跑更宽测试：优先 `mvn -pl vortex-app -am test`；时间允许再考虑 full `mvn test`。
3. 整理 README/项目首页，但必须先确认是否恢复或重写 deleted `README.md`。
4. 分组提交：recall benchmark、runtime recovery、async pipeline、docs/evidence 分开。
5. 若要对外展示，优先突出三项可验证技术指标，不突出 Stars。

## Suggested Current Public Claim

中文：

> Vortex 是 Java 21 + Spring Boot 实现的 Agent Memory & RAG Runtime 基础设施，覆盖长期记忆、混合检索、运行时恢复与异步 Memory Pipeline。在 deterministic benchmark 中，Hybrid Retrieval 相对 Vector-only 的 Recall@5 从 0.6667 提升到 0.8583（+28.75%）；runtime recovery 覆盖五类异常 32/32 cases 通过；将 Memory 抽取、摘要、Embedding、L1 admission、L2 indexing 与 L3 archive 移出请求主路径后，主路径 P99 latency 从 1172.50 ms 降至 220.34 ms，平均 latency 从 829.40 ms 降至 186.64 ms，主路径与 L2/L3 readiness success rate 均为 100.00%。

英文：

> Vortex is a Java 21 + Spring Boot Agent Memory and RAG Runtime foundation covering long-term memory, hybrid retrieval, runtime recovery, and async memory ingest. In deterministic benchmarks, Hybrid Retrieval improved Recall@5 from 0.6667 to 0.8583 over vector-only (+28.75%); runtime recovery passed 32/32 covered cases across five failure categories; and moving extraction, summary, embedding, L1 admission, L2 indexing, and L3 archive off the request main path reduced P99 latency from 1172.50 ms to 220.34 ms and average latency from 829.40 ms to 186.64 ms while keeping main-path and L2/L3 readiness success at 100.00%.
