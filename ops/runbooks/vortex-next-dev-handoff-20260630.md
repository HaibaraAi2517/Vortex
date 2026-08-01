# Vortex 下一轮开发交接状态

生成日期：2026-06-30（Asia/Shanghai）

本文件用于下一次对话继续开发。下一次建议直接让 coding agent 先读本文件：

```text
请先完整阅读 ops/runbooks/vortex-next-dev-handoff-20260630.md，然后从“下一步最应该做什么”开始继续开发。
```

## 当前总状态

`ops/开发计划.md` 中前两项 P0 高风险指标修复已经完成，P1 hybrid recall ablation 也已经完成首轮可引用证据：

- P0 Runtime Recovery：32-case deterministic fault-injection matrix，`32/32` passed。
- P0 Main-path Latency：废弃旧 `99.99%` admission-only 口径，改为真实 main-path benchmark，P99 `1172.50 ms -> 220.34 ms`，平均 `829.40 ms -> 186.64 ms`，平均降低 `77.50%`。
- P1 Recall Ablation：五模式 ablation 已实现并重跑 canonical `-003` 证据，Hybrid+Rerank 相对 Vector+Rerank 的 Recall@5 从 `0.7917` 到 `0.9500`，absolute lift `+0.1583`，relative lift `+20.00%`。

当前最重要的口径边界：

- 不要再说“主链路延迟降低 `99.99%`”。这是旧 async admission/enqueue-only 口径。
- Recall ablation 的 `+20.00%` 只能说 deterministic retrieval Recall@5 relative lift，不能说 LLM answer accuracy 或端到端 Agent quality。
- Runtime recovery 的 `32/32` 只能说 covered deterministic cases，不是生产故障全集。
- `README.md` 当前在 git status 中是 deleted；不要擅自恢复或重写，除非用户明确要求。
- 工作区很脏，包含大量 modified/untracked 文件；不要 reset、checkout 或清理不相关文件。

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

允许 claim：

> 覆盖 Service Restart / Tool Failure / LLM Exception / State Integrity / Concurrency 五类异常共 32 个 deterministic recovery cases，基于 checkpoint/WAL/runtime-state 与 Execution ID 幂等恢复，benchmark 中 32/32 通过状态一致性校验。

不允许 claim：

- 不要说完整生产故障全集恢复成功率 100%。
- 不要说外部 process-manager crash-loop orchestration 已覆盖。
- 不要说跨历史二进制版本 snapshot schema migration 已覆盖。
- 不要说 full async memory extraction/summary/embedding/index pipeline recovery 已覆盖。

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

允许 claim：

> 将 Memory 抽取 / 摘要 / Embedding / L1 admission / L2 indexing / L3 archive 移出请求主路径；在 Docker-backed Milvus/MinIO deterministic main-path benchmark 中，主路径 P99 从 `1172.50 ms` 降至 `220.34 ms`，平均主路径 latency 从 `829.40 ms` 降至 `186.64 ms`，主路径与 L2/L3 readiness 成功率均为 `100.00%`。

不允许 claim：

- 不要再说主链路降低 `99.99%`。
- 不要说完整 Agent end-to-end latency，因为没有包含真实 LLM generation。
- 不要说生产 p95/p99 行为，只能说 deterministic benchmark。
- 不要隐藏 async readiness latency；本次 async readiness P95 是 `972.1643 ms`，readiness lag avg 是 `585.9576 ms`。

## P1 任务 3：Hybrid Recall Ablation Benchmark

状态：已完成首轮 canonical 证据，并做过 self-check 修复。

证据文档：

- `ops/runbooks/vortex-recall-ablation-benchmark-evidence-20260630.md`

canonical 最新报告：

- JSON：`ops/eval-reports/20260630-recall-ablation-benchmark-v3-1-003/recall-benchmark-20260630-094550.json`
- Markdown：`ops/eval-reports/20260630-recall-ablation-benchmark-v3-1-003/recall-benchmark-20260630-094550.md`

运行条件：

- Dataset：`classpath:llm-memory-eval-set-v3-1-real-agent-workload.json`
- Cases：`20`
- Runs：`100`
- Modes：`KeywordOnly`, `VectorOnly`, `Vector+Rerank`, `Hybrid`, `Hybrid+Rerank`
- Evaluation K：`1`, `3`, `5`, `10`
- Primary TopK：`5`
- Storage/runtime：Docker-backed Milvus/MinIO + local BGE-Small embeddings
- Semantic paging：`VORTEX_PAGING_ENABLED=false`，用于隔离 retrieval ablation，避免 L2 page-fault 改变 L1 state 后污染跨 mode 对照
- Generation：disabled

最新结果：

| Mode | Recall@5 | Lift vs Vector+Rerank | Relative Lift | Case Hit Rate | All Expected Rate | Precision@5 | MRR | NDCG@5 | Avg Latency Ms | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| VectorOnly | 0.7917 | 0.0000 | 0.0000 | 1.0000 | 0.6000 | 0.3400 | 0.7750 | 0.6754 | 248.5000 | 0 |
| Vector+Rerank | 0.7917 | 0.0000 | 0.0000 | 1.0000 | 0.6000 | 0.3400 | 0.7750 | 0.6754 | 249.3000 | 0 |
| KeywordOnly | 0.9333 | 0.1417 | 0.1789 | 1.0000 | 0.8500 | 0.4000 | 0.8917 | 0.8352 | 685.6000 | 0 |
| Hybrid | 0.9500 | 0.1583 | 0.2000 | 1.0000 | 0.9000 | 0.4100 | 0.8750 | 0.8378 | 371.8500 | 0 |
| Hybrid+Rerank | 0.9500 | 0.1583 | 0.2000 | 1.0000 | 0.9000 | 0.4100 | 0.8750 | 0.8343 | 360.5500 | 0 |

允许 claim：

> 在 deterministic recall ablation benchmark（v3.1 real-agent workload，Docker-backed Milvus/MinIO + BGE-Small，shared namespace candidate pool，关闭 semantic paging 以隔离检索行为）中，Hybrid+Rerank 相对 Vector+Rerank 的 Recall@5 从 `0.7917` 提升到 `0.9500`，absolute lift 为 `+0.1583`，relative lift 为 `+20.00%`；五种检索模式共 `100` 次运行，错误数为 `0`。

不允许 claim：

- 不要说 LLM answer accuracy 提升 `20.00%`。
- 不要说线上生产 recall 提升 `20.00%`。
- 不要说端到端 Agent quality 提升 `20.00%`。
- 不要说 rerank alone 有提升；本轮 `VectorOnly` 和 `Vector+Rerank` aggregate metrics 相同。
- 不要说 hybrid retrieval 降低 latency；本 benchmark 中 hybrid modes 平均 latency 高于 vector-only modes。

## 本轮 self-check 后修复

本轮对 P1 recall ablation 做了 code-review 风格自查，并修复了两个会影响证据可信度的问题：

- `RecallBenchmarkRunner.computeMetrics`：Precision@K 改为标准 `matched / K`，不是 `matched / returned.size()`；新增 fewer-than-K 返回场景的测试。
- `RecallOrchestrator`：恢复 selected L2 recall fragments 对 `pagingManager.handlePageFault(fragmentId, namespace)` 的调用，避免 benchmark 改动破坏生产 semantic paging 行为；新增 L2 selected fragment 触发 page-fault 的测试。

此外保留了 async pipeline status lifecycle 修复：running statuses 不会被 `max-statuses` 淘汰，已覆盖失败/status 测试。
## 本轮提交前 diff review 结果

已完成 focused diff review，重点覆盖：

- `RecallOrchestrator`：确认 selected L2 fragment 仍触发 `handlePageFault`；benchmark run 使用 `VORTEX_PAGING_ENABLED=false` 时 paging manager 会 no-op，且每个 mode 前 `clearL1(namespace)` 会清掉 recall/admit 回 L1 的 mode 间副作用。
- `RecallBenchmarkRunner`：确认 Precision@K 使用标准 `matched/K`；shared namespace candidate pool 和 per-mode L1 reset 保持 ablation 对照边界。
- `AsyncMemoryPipeline`：确认 running statuses 不会被 terminal status 淘汰；失败路径会发布 `FAILED` status 并保留已完成 stages。
- `application.yml` / `docker-compose.yml`：确认新增配置主要是 Redis execution-id、memory-pipeline 参数、eval vector-only 默认 mode 和 async benchmark 参数；Redis backend 默认仍是 `MEMORY`，不会默认强依赖 Redis。

本轮 review 中修复了一个配置一致性问题：`LlmMemoryEvalProperties` 的 Java 默认 eval modes 已同步加入 `VORTEX_VECTOR_ONLY`，与 `application.yml` 默认值保持一致。修复过程中曾短暂引入 UTF-8 BOM 导致 javac 失败，已去除并通过 focused/full tests 验证。

## 已实现/改动的主要代码范围

Recall ablation / hybrid retrieval：

- `vortex-common/src/main/java/com/vortex/common/dto/RecallQuery.java`
- `vortex-common/src/main/java/com/vortex/common/dto/RecallDiagnostics.java`
- `vortex-common/src/main/java/com/vortex/common/dto/RetrievalMode.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/RecallOrchestrator.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/KeywordRecallIndex.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HybridRecallReranker.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallAblationMode.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkReportWriter.java`
- `vortex-app/src/test/java/com/vortex/app/eval/RecallBenchmarkRunnerTest.java`
- `vortex-app/src/test/java/com/vortex/app/eval/RecallBenchmarkReportWriterTest.java`
- `vortex-kernel/src/test/java/com/vortex/kernel/hmc/RecallOrchestratorTest.java`

Async pipeline：

- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/AsyncMemoryPipeline.java`
- `vortex-kernel/src/test/java/com/vortex/kernel/hmc/AsyncMemoryPipelineTest.java`

Runtime recovery and main-path latency files remain as described in `ops/runbooks/vortex-next-dev-handoff-20260629.md`.

## 测试与验证状态

Focused recall/orchestrator self-check tests：

```powershell
mvn -pl vortex-kernel,vortex-app -am '-Dtest=RecallBenchmarkRunnerTest,RecallOrchestratorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：

- `RecallOrchestratorTest`：27 tests passed。
- `RecallBenchmarkRunnerTest`：5 tests passed。
- `BUILD SUCCESS`。

App/module wider tests earlier in this workstream：

```powershell
mvn -pl vortex-app -am test
```

结果：

- app stage reported `140` tests passed。

Package after latest recall fixes：

```powershell
mvn -pl vortex-app -am -DskipTests package
```

结果：

- `BUILD SUCCESS`。

Real Docker-backed recall ablation run：

```powershell
$env:LOGGING_LEVEL_COM_VORTEX='INFO'
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260630-recall-ablation-benchmark-v3-1-003'
$env:VORTEX_EVAL_DATASET_LOCATION='classpath:llm-memory-eval-set-v3-1-real-agent-workload.json'
$env:VORTEX_EVAL_RECALL_TOP_K='5'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_recall_ablation_20260630_003'
$env:MINIO_KEY_PREFIX='recall-ablation-benchmark/20260630-003/'
$env:VORTEX_WAL_DIR='E:/tmp/vortex-recall-ablation-benchmark-20260630-003/wal'
$env:BGE_MODEL_PATH='models/bge-small-zh'
$env:VORTEX_SCHEDULER_ENABLED='false'
$env:VORTEX_PAGING_ENABLED='false'
java -jar .\vortex-app\target\vortex-app-0.1.0-eval-cli.jar recall-benchmark
```

结果：

- `totalCases=20`
- `totalRuns=100`
- `errors=0`
- `Hybrid+Rerank` Recall@5：`0.9500`
- `Vector+Rerank` Recall@5：`0.7917`
- Absolute lift：`+0.1583`
- Relative lift：`+20.00%`

Full root test:

```powershell
mvn test
```

结果：

- Reactor `BUILD SUCCESS`。
- Common `37` tests passed；Storage `21` tests passed；App `141` tests passed。
- Kernel module completed successfully。
- Latest finish time：`2026-06-30T21:29:30+08:00`。

## Docker 状态

本轮为了跑真实 recall ablation，已经启动 Docker Desktop 并执行过 `docker compose up -d`。

最近已知 compose services healthy：

- `vortex-etcd`
- `vortex-milvus`
- `vortex-minio`
- `vortex-redis`

除非用户要求，不要自动停止这些服务；后续 benchmark 可能还会复用。

## 当前工作区状态

截至本 handoff 生成前，工作区仍然很脏：

- `README.md` deleted。不要恢复，除非用户明确要求。
- `docker-compose.yml`、`vortex-app/pom.xml`、`application.yml` 等有 modified。
- `vortex-app`、`vortex-common`、`vortex-kernel` 下大量源码和测试 modified/untracked。
- 多个 runbook/evidence/handoff 文件 untracked。
- `readme-history/`、`简历建议5.md` 等用户/前序上下文保持原状，不要清理。

特别注意：

- 不要用 `git reset --hard`、`git checkout --` 或批量删除来“清理”。
- 如果要提交，建议拆成多个 commit：runtime recovery、main-path latency/async pipeline、recall ablation、docs/evidence。
- `application.yml` 和 `docker-compose.yml` 提交前要单独 diff review，确认哪些是本轮必需，哪些是前序上下文。

## 下一步最应该做什么

1. 准备分组 commit 或 PR 描述，建议拆成：runtime recovery、main-path latency/async pipeline、recall ablation、docs/evidence。
2. 如果用户要对外材料，再统一更新 README/简历/目标文案；但 `README.md` 当前 deleted，不要在没有明确指令时恢复。
3. 如果继续扩展工程能力，下一个合理方向是 async memory pipeline recovery coverage，把 runtime recovery benchmark 的 excluded async pipeline recovery 补上。
4. 提交前再人工看一次 `application.yml` 和 `docker-compose.yml`，确认 Redis/execution-id 配置是否要和 runtime recovery commit 放在同一组。