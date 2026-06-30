# Vortex 下一轮开发交接状态

生成日期：2026-06-29（Asia/Shanghai）

本文件用于下一次对话继续开发。下一次建议直接让 coding agent 先读本文件：

```text
请先完整阅读 ops/runbooks/vortex-next-dev-handoff-20260629.md，然后从“下一步最应该做什么”开始继续开发。
```

## 当前总状态

`ops/开发计划.md` 的两个 P0 高风险数字修复任务已经完成：

- P0 任务 1：运行时恢复 benchmark 已从小分母样例扩展为 32-case 故障注入矩阵。
- P0 任务 2：主链路延迟 benchmark 已替换掉误导性的 `99.99% latency reduction` 口径，改为真实 main-path 对照数据。

现在对外或简历中不应再说“主链路延迟降低 99.99%”。那是早期 async admission/enqueue 口径，只测了入队，不是请求主路径端到端。

当前可防守的最新主链路延迟结论是：

> 在 deterministic main-path benchmark（Docker-backed Milvus/MinIO）中，将 Memory 抽取、摘要、Embedding、L1 admission、L2 indexing 与 L3 archive 从请求主路径移到后台后，主路径 P99 latency 从 `1172.50 ms` 降至 `220.34 ms`，平均主路径 latency 从 `829.40 ms` 降至 `186.64 ms`；平均延迟相对降低 `77.50%`，主路径成功率与 L2/L3 readiness 成功率均为 `100.00%`。

## 最重要的状态边界

- `ops/runbooks/vortex-main-path-latency-benchmark-evidence-20260629.md` 是当前延迟 claim 的权威证据。
- `ops/runbooks/vortex-async-pipeline-latency-benchmark-evidence-20260628.md` 中的 `99.99%` 是已被取代的 async-ingest/admission-only 旧口径，不应用作主链路延迟 claim。
- `ops/runbooks/vortex-project-status-20260629.md` 生成较早，仍包含旧的 `99.99%` 说法；继续开发时以本 handoff 和 main-path evidence 为准。
- `README.md` 当前在 git status 中显示 deleted；不要擅自恢复或重写，除非用户明确要求。
- 工作区有大量 modified/untracked 文件，很多来自前序开发。不要 reset、checkout 或清理不相关文件。

## P0 任务 1：Runtime Recovery Benchmark

状态：已完成。

证据文档：

- `ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md`

最新矩阵报告：

- JSON：`ops/eval-reports/20260629-runtime-recovery-benchmark-003/runtime-recovery-benchmark-20260629-135453.json`
- Markdown：`ops/eval-reports/20260629-runtime-recovery-benchmark-003/runtime-recovery-benchmark-20260629-135453.md`

最新结果：

| 指标 | 值 |
| --- | ---: |
| Total cases | `32` |
| Passed cases | `32` |
| Failed cases | `0` |
| Overall success rate | `1.0000` |
| Average measured case latency | `68.7813 ms` |

分类别成功率：

| Category | Cases | Passed | Failed | Success rate | Avg latency ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Service restart | 6 | 6 | 0 | 1.0000 | 121.6667 |
| Tool failure | 8 | 8 | 0 | 1.0000 | 46.1250 |
| LLM exception | 6 | 6 | 0 | 1.0000 | 49.3333 |
| State integrity | 7 | 7 | 0 | 1.0000 | 51.5714 |
| Concurrency | 5 | 5 | 0 | 1.0000 | 89.0000 |

允许 claim：

> 覆盖 Service Restart / Tool Failure / LLM Exception / State Integrity / Concurrency 五类异常共 32 个 deterministic recovery cases，基于 checkpoint/WAL/runtime-state 与 Execution ID 幂等恢复，benchmark 中 32/32 通过状态一致性校验。

不允许 claim：

- 不要说完整生产故障全集恢复成功率 100%。
- 不要说外部 process-manager crash-loop orchestration 已覆盖。
- 不要说跨历史二进制版本 snapshot schema migration 已覆盖。
- 不要说 full async memory extraction/summary/embedding/index pipeline recovery 已覆盖。

复现命令：

```powershell
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260629-runtime-recovery-benchmark-003'
$env:VORTEX_WAL_DIR='E:/tmp/vortex-runtime-recovery-benchmark-20260629-003/wal'
$env:MINIO_KEY_PREFIX='runtime-recovery-benchmark/20260629-003/'
$env:VORTEX_EXECUTION_ID_BACKEND='MEMORY'
$env:VORTEX_SCHEDULER_ENABLED='false'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_runtime_recovery_20260629_003'
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar runtime-recovery-benchmark
```

## P0 任务 2：真实 Main-Path Latency Benchmark

状态：已完成。

证据文档：

- `ops/runbooks/vortex-main-path-latency-benchmark-evidence-20260629.md`

最新报告：

- JSON：`ops/eval-reports/20260629-main-path-latency-benchmark-003/async-pipeline-latency-benchmark-20260629-151448.json`
- Markdown：`ops/eval-reports/20260629-main-path-latency-benchmark-003/async-pipeline-latency-benchmark-20260629-151448.md`

测量口径：

- Main path：`request -> hybrid retrieval -> rerank -> prompt/context assembly -> return payload`
- Excluded：external LLM generation
- Background async pipeline：extraction, summary, semantic split, embedding, L1 admission, L2 index, L3 archive
- Baseline：同步 memory pipeline 阻塞主路径
- Treatment：async pipeline 入队后主路径返回，L2/L3 readiness 单独统计

最新结果：

| Mode | Main Avg Ms | Main P50 Ms | Main P95 Ms | Main P99 Ms | Recall P95 Ms | Prompt P95 Ms | Write Submit P95 Ms | Main Success | Persistence Success | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 829.3997 | 863.9291 | 1172.5000 | 1172.5000 | 485.2810 | 13.1784 | 952.8537 | 1.0000 | 1.0000 | 0 |
| ASYNC_PIPELINE | 186.6363 | 184.6629 | 220.3377 | 220.3377 | 206.9066 | 13.5065 | 0.1981 | 1.0000 | 1.0000 | 0 |

Derived：

| Metric | Value |
| --- | ---: |
| Sync average main-path latency | `829.3997 ms` |
| Async average main-path latency | `186.6363 ms` |
| Relative average main-path latency reduction | `0.7750` |
| Percent reduction | `77.50%` |
| Sync main-path P99 | `1172.5000 ms` |
| Async main-path P99 | `220.3377 ms` |
| Sync returned fragment average | `5.0000` |
| Async returned fragment average | `5.0000` |

Background readiness：

| Mode | Pipeline Avg Ms | Pipeline P95 Ms | Pipeline TPS | Readiness P95 Ms | Readiness Lag Avg Ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 155.8646 | 180.0704 | 6.4158 | 1172.5084 | 0.0162 |
| ASYNC_PIPELINE | 151.5252 | 164.6405 | 6.5996 | 972.1643 | 585.9576 |

Backpressure probe：

| Policy | Queue Capacity | Submitted | Completed | Errors | CallerRuns During Benchmark | Max Queue | Saturated |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| CALLER_RUNS | 8 | 14 | 14 | 0 | 2 | 8 | true |

允许 claim：

> 将 Memory 抽取 / 摘要 / Embedding / L1 admission / L2 indexing / L3 archive 移出请求主路径；在 Docker-backed Milvus/MinIO deterministic main-path benchmark 中，主路径 P99 从 `1172.50 ms` 降至 `220.34 ms`，平均主路径 latency 从 `829.40 ms` 降至 `186.64 ms`，主路径与 L2/L3 readiness 成功率均为 `100.00%`。

不允许 claim：

- 不要再说主链路降低 `99.99%`。
- 不要说完整 Agent end-to-end latency，因为没有包含真实 LLM generation。
- 不要说生产 p95/p99 行为，只能说 deterministic benchmark。
- 不要隐藏 async readiness latency；本次 async readiness P95 是 `972.1643 ms`，readiness lag avg 是 `585.9576 ms`。

复现命令：

```powershell
$env:LOGGING_LEVEL_COM_VORTEX='INFO'
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260629-main-path-latency-benchmark-003'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_main_path_latency_20260629_003'
$env:MINIO_KEY_PREFIX='main-path-latency-benchmark/20260629-003/'
$env:VORTEX_WAL_DIR='E:/tmp/vortex-main-path-latency-benchmark-20260629-003/wal'
$env:BGE_MODEL_PATH='models/bge-small-zh'
$env:VORTEX_STORAGE_L1_MAX_TOKENS='32768'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_FRAGMENTS='16'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_WARMUP_FRAGMENTS='2'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_ASYNC_PARALLELISM='4'
$env:VORTEX_MEMORY_PIPELINE_MAX_WORKERS='4'
$env:VORTEX_MEMORY_PIPELINE_QUEUE_CAPACITY='8'
$env:VORTEX_SCHEDULER_ENABLED='false'
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar async-pipeline-latency-benchmark
```

## 已实现/改动的主要代码范围

Main-path latency / async pipeline：

- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkReportWriter.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkExecutionService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/AsyncMemoryPipeline.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryExtractionService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemorySummaryService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineRequest.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStatus.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStage.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStatusCode.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HierarchicalMemoryController.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/FragmentPersistenceManager.java`
- `vortex-app/src/main/resources/application.yml`

Runtime recovery：

- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkReportWriter.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkExecutionService.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryTaskCacheEvictor.java`
- `vortex-app/src/main/java/com/vortex/app/runtime/`
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

Hybrid recall / benchmark 已有前序成果：

- `ops/runbooks/vortex-recall-benchmark-evidence-20260626.md`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkReportWriter.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkExecutionService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/KeywordRecallIndex.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HybridRecallReranker.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/RecallOrchestrator.java`
- `vortex-common/src/main/java/com/vortex/common/dto/RetrievalMode.java`

## 测试与验证状态

最新已记录的 focused tests：

```powershell
mvn -pl vortex-kernel,vortex-app -am '-Dtest=AsyncMemoryPipelineTest,AsyncPipelineLatencyBenchmarkRunnerTest,AsyncPipelineLatencyBenchmarkReportWriterTest,LlmMemoryEvalCliApplicationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：

- `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0`

最新已记录的 package：

```powershell
mvn -pl vortex-app -am -DskipTests package
```

结果：

- `BUILD SUCCESS`

真实 CLI benchmark：

```powershell
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar async-pipeline-latency-benchmark
```

结果：

- Docker-backed Milvus/MinIO 环境下通过。
- 输出报告在 `ops/eval-reports/20260629-main-path-latency-benchmark-003/`。

## 当前工作区状态

截至本 handoff 生成前，`git status --short` 显示工作区仍然很脏：

- `README.md` deleted。
- `docker-compose.yml`、`vortex-app/pom.xml`、`application.yml` 等有 modified。
- `vortex-app`、`vortex-common`、`vortex-kernel` 下大量源码和测试 modified/untracked。
- 多个 runbook/evidence/handoff 文件 untracked。
- `readme-history/` 和 `简历建议5.md` untracked。

特别注意：

- 不要用 `git reset --hard`、`git checkout --` 或批量删除来“清理”。
- `application.yml` diff 里既有前序未提交配置，也有本次 async pipeline 的 `queue-capacity` 相关配置；提交前需要人工分辨。
- 如果要提交，建议拆成多个 commit：runtime recovery、main-path latency/async pipeline、recall benchmark、docs/evidence。

## 下一步最应该做什么

建议下一轮优先级：

1. 更新用户可见/对外材料，把所有 `99.99% 主链路延迟降低` 替换为新的 main-path benchmark 口径。
2. 对 `application.yml` 做提交前 diff review，确认哪些配置是本次 async pipeline 必需，哪些属于前序未提交上下文。
3. 做一次 code-review 风格自查，重点看 `AsyncMemoryPipeline` 的队列 backpressure、status lifecycle、失败路径和 benchmark readiness 口径。
4. 跑更宽的测试：优先 `mvn -pl vortex-app -am test`；时间允许再跑 full `mvn test`。
5. P0 完成后进入 P1 任务 3：混合检索 benchmark 增强，做 ablation（keyword-only / vector-only / vector+rerank / hybrid / hybrid+rerank）和 Recall@1/3/5/10、Precision@K、MRR、nDCG@K。

最建议下一次直接执行的工作：

> 先清理文档 claim，把旧的 `99.99%` 主链路延迟说法替换为 `P99 1172.50 ms -> 220.34 ms，平均 829.40 ms -> 186.64 ms，平均降低 77.50%`；然后再进入 P1 hybrid retrieval ablation benchmark。

