# Vortex 下一轮开发交接状态

生成日期：2026-06-28（Asia/Shanghai）

本文件用于下一次对话继续开发。当前 `ops/runbooks/目标.md` 的工程目标已经基本闭环；下一轮不应再从“实现 async pipeline latency benchmark”开始，而应从代码审查、补充测试或准备提交开始。

建议下一次对话开场：

```text
请阅读 ops/runbooks/vortex-next-dev-handoff-20260628.md，然后检查是否还有提交前风险。
```

## 当前主结论

`ops/runbooks/目标.md` 里的三个核心量化/卖点方向已经有可防守证据：

- Hybrid Retrieval：真实 Milvus + BGE-Small recall benchmark，`Recall@5` relative lift 为 `+28.75%`。
- Runtime Recovery：扩展版 deterministic runtime recovery benchmark，`7/7` covered cases 通过，covered-case recovery success rate 为 `100.00%`。
- Async Memory Pipeline：真实 Docker-backed Milvus/MinIO latency benchmark，平均 request-admission main-path latency 从 `653.4839 ms` 降至 `0.0743 ms`，relative reduction 为 `99.99%`，L2/L3 readiness success rate 为 `100.00%`。

`目标.md` 已更新为这些数字。剩余 `Stars XX` 属于 GitHub 实时展示字段，不是工程实现目标；如需替换，需要确认当前真实 star 数。

## 目标.md 对齐状态

目标文件：

- `ops/runbooks/目标.md`

当前可防守填写：

- `Recall@5 +28.75% relative lift`
- `covered-case recovery success rate 100.00%`
- `async memory pipeline main-path latency reduction 99.99%`

仓库 URL 已从 `git remote` 确认为：

- `github.com/HaibaraAi2517/Vortex`

注意边界：

- `99.99%` 是 memory ingest pipeline 的 measured request-admission main-path latency reduction，不是完整 Agent 执行、召回查询或 LLM 生成 latency。
- async benchmark 的 readiness latency 需要单独说明；成功报告中 async readiness average 为 `600.7725 ms`，p95 为 `829.2747 ms`。
- `100.00%` recovery 是 covered-case benchmark success rate，不代表所有生产故障场景。

## 已完成证据 1：Hybrid Recall Benchmark

证据文档：

- `ops/runbooks/vortex-recall-benchmark-evidence-20260626.md`

报告文件：

- `ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001/recall-benchmark-20260625-162328.json`
- `ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001/recall-benchmark-20260625-162328.md`
- `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.json`
- `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.md`

Benchmark 条件：

- Dataset：`classpath:llm-memory-eval-set-v3-1-real-agent-workload.json`
- Cases：20
- Runs：40
- Modes：`VORTEX_VECTOR_ONLY`, `VORTEX_MEMORY`
- TopK：1 和 5 各跑一组
- Storage/runtime：Docker-backed Milvus + BGE-Small embeddings
- Candidate pool：按原始 namespace 共享 run-scoped candidate pool
- Generation：disabled；这是 deterministic retrieval recall benchmark，不是 LLM answer generation benchmark

关键结果：

| TopK | Mode | Recall@K | Absolute Lift | Relative Lift | Case Hit Rate | NDCG | Avg Latency Ms | Errors |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | Vortex-VectorOnly | 0.2667 | 0.0000 | 0.0000 | 0.5500 | 0.5500 | 187.55 | 0 |
| 1 | Vortex-Memory | 0.3833 | 0.1167 | 0.4375 | 0.8000 | 0.8000 | 245.55 | 0 |
| 5 | Vortex-VectorOnly | 0.6667 | 0.0000 | 0.0000 | 0.9000 | 0.5907 | 199.20 | 0 |
| 5 | Vortex-Memory | 0.8583 | 0.1917 | 0.2875 | 1.0000 | 0.7825 | 298.30 | 0 |

推荐中文表述：

> 在自建 deterministic recall benchmark（v3.1 real-agent workload，Milvus + BGE-Small，shared namespace candidate pool）中，Hybrid Retrieval 相对 Vector-only 的 Recall@5 从 0.6667 提升到 0.8583，relative lift 为 +28.75%。

## 已完成证据 2：Runtime Recovery Benchmark

证据文档：

- `ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md`

最新报告文件：

- `ops/eval-reports/20260628-runtime-recovery-benchmark-002/runtime-recovery-benchmark-20260628-104936.json`
- `ops/eval-reports/20260628-runtime-recovery-benchmark-002/runtime-recovery-benchmark-20260628-104936.md`

Benchmark 条件：

- Command：`runtime-recovery-benchmark`
- Total cases：7
- Passed cases：7
- Failed cases：0
- Success rate：`1.0000`
- Average latency：`92.7143 ms`

覆盖能力：

- Task DAG checkpoint and recover
- Recovery after process-local task cache eviction
- Repeated recover idempotency
- Branch and merge state recovery
- Application Execution ID replay idempotency
- Conversation state snapshot and recovery
- Tool failure runtime recovery
- LLM timeout task-level retry recovery

推荐中文表述：

> 在扩展版 deterministic runtime recovery benchmark 中，覆盖 Task DAG checkpoint/recover、进程内状态清空后恢复、重复 recover 幂等、branch/merge 状态恢复、Execution ID replay 幂等、Conversation 状态恢复、Tool Failure 状态恢复与 LLM Timeout retry 状态恢复，7/7 cases 通过，covered-case recovery success rate 为 100%。

## 已完成证据 3：Async Memory Pipeline Latency Benchmark

证据文档：

- `ops/runbooks/vortex-async-pipeline-latency-benchmark-evidence-20260628.md`

最新报告文件：

- `ops/eval-reports/20260628-async-memory-pipeline-latency-benchmark-002/async-pipeline-latency-benchmark-20260628-135001.json`
- `ops/eval-reports/20260628-async-memory-pipeline-latency-benchmark-002/async-pipeline-latency-benchmark-20260628-135001.md`

Benchmark 条件：

- Command：`async-pipeline-latency-benchmark`
- Scope：memory extraction + summary + semantic split + embedding + L1 admission + L2 index + L3 archive
- Explicitly not covered：full Agent execution or LLM generation
- Fragment count：`16`
- Warmup fragment count：`2`
- Modes：`SYNC_BASELINE`, `ASYNC_PIPELINE`
- Embedding：local BGE-Small, `dim=512`
- L1：Caffeine，`VORTEX_STORAGE_L1_MAX_TOKENS=32768`
- L2：Docker-backed Milvus collection `vortex_memory_async_pipeline_20260628_002`
- L3：Docker-backed MinIO prefix `async-memory-pipeline-latency-benchmark/20260628-002/`

关键结果：

| Mode | Main Avg Ms | Main P50 Ms | Main P95 Ms | Readiness Avg Ms | Readiness P95 Ms | Success | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 653.4839 | 703.8858 | 790.2326 | 653.4839 | 790.2326 | 1.0000 | 0 |
| ASYNC_PIPELINE | 0.0743 | 0.0490 | 0.2233 | 600.7725 | 829.2747 | 1.0000 | 0 |

Derived：

- Sync average main-path latency：`653.4839 ms`
- Async average main-path latency：`0.0743 ms`
- Relative main-path latency reduction：`0.9999` / `99.99%`
- Async extraction/summary/embedding/L2/L3 completed：`16/16`
- L2/L3 readiness success rate：`100.00%`

推荐中文表述：

> 将 Memory 抽取 / 摘要 / Embedding / L2 indexing / L3 archive 解耦为异步 Pipeline；在 Docker-backed Milvus/MinIO benchmark 中，平均主链路 latency 从 653.48 ms 降至 0.07 ms，降低 99.99%，L2/L3 readiness 成功率 100.00%。

## 已实现代码能力概览

### Async Memory Pipeline

新增/更新：

- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/AsyncMemoryPipeline.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryExtractionService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemorySummaryService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineRequest.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStatus.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStage.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStatusCode.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HierarchicalMemoryController.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/FragmentPersistenceManager.java`

行为：

- `AsyncMemoryPipeline.submit(...)`：快速 admission，并后台执行 extraction/summary/split/embedding/L1/L2/L3。
- `AsyncMemoryPipeline.processBlocking(...)`：benchmark 用同步 baseline。
- `HierarchicalMemoryController.storeProcessed(...)`：pipeline 内部复用 semantic split + fragment persistence。
- `FragmentPersistenceManager.persistBlocking(...)`：同步 baseline 持久化路径。

REST API：

- `POST /api/v1/memory/store/async`
- `GET /api/v1/memory/pipeline/{pipelineId}`

### Async Benchmark CLI

新增/更新：

- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkExecutionService.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkReportWriter.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalCliApplication.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalProperties.java`
- `vortex-app/src/main/resources/application.yml`

CLI command：

```text
async-pipeline-latency-benchmark
```

## 当前验证记录

Targeted tests：

```powershell
mvn -pl vortex-kernel,vortex-app -am "-Dtest=AsyncMemoryPipelineTest,AsyncPipelineLatencyBenchmarkRunnerTest,AsyncPipelineLatencyBenchmarkReportWriterTest,MemoryControllerTest,LlmMemoryEvalCliApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

最新结果（2026-06-28 22:01 Asia/Shanghai）：

- `Tests run: 32, Failures: 0, Errors: 0, Skipped: 0`

Package：

```powershell
mvn -DskipTests package
```

最新结果（2026-06-28 22:01 Asia/Shanghai）：

- `BUILD SUCCESS`

真实 Docker-backed benchmark：

```powershell
$env:LOGGING_LEVEL_COM_VORTEX='INFO'
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260628-async-memory-pipeline-latency-benchmark-002'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_async_pipeline_20260628_002'
$env:MINIO_KEY_PREFIX='async-memory-pipeline-latency-benchmark/20260628-002/'
$env:BGE_MODEL_PATH='models/bge-small-zh'
$env:VORTEX_STORAGE_L1_MAX_TOKENS='32768'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_FRAGMENTS='16'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_WARMUP_FRAGMENTS='2'
java -jar .\vortex-app\target\vortex-app-0.1.0-eval-cli.jar async-pipeline-latency-benchmark
```

结果：

- Sync average main-path latency：`653.4839 ms`
- Async average main-path latency：`0.0743 ms`
- Relative reduction：`99.99%`
- L2/L3 readiness success rate：`100.00%`

## 当前工作区状态注意事项

工作区仍然不是干净状态。不要清理或回滚不相关文件。

重要注意：

- `README.md` 仍显示 deleted。不要恢复，除非用户明确要求。
- 多个 recall/runtime/async pipeline 文件是本轮或前序工作新增的 untracked 文件。
- `ops/runbooks/目标.md`、evidence runbook、wording source、handoff 文件仍为 untracked。
- `readme-history/`、`简历建议5.md` 保持原状态，不要清理。
- 默认 Milvus collection `vortex_memory` 可能仍有维度风险；真实 benchmark 应继续使用 isolated collection。

## 下一轮最应该做什么

如果继续工程收尾，优先级建议：

1. 跑一次当前定向测试，确认新增 WebMvc async endpoint 测试也通过。
2. 跑 `mvn -DskipTests package`。
3. 如时间允许，跑 `mvn -pl vortex-app -am test` 或 full `mvn test`，但注意耗时和外部依赖。
4. 做一次 code review 风格检查，重点看 async pipeline 的状态保留、失败路径、benchmark readiness 口径和文档 claim boundaries。
5. 准备提交前，确认是否要把 `Stars XX` 替换为用户提供的真实 GitHub star 数。
