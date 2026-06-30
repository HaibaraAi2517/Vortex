# Vortex 下一轮开发交接状态

生成日期：2026-06-26（Asia/Shanghai）

本文件用于下一次对话继续开发。下一轮建议先读：

- `ops/runbooks/vortex-next-dev-handoff-20260626.md`
- `ops/runbooks/vortex-next-dev-handoff-20260625.md`
- `ops/runbooks/目标.md`
- `ops/runbooks/未完成内容.md`

## 当前主结论

本轮把上一轮新增的 recall benchmark 从“可运行 harness”推进成了有真实 Docker/Milvus/BGE 证据的 benchmark。

最重要结论：

- 在 `classpath:llm-memory-eval-set-v3-1-real-agent-workload.json` 上，Hybrid recall 相对 Vector-only 有可复现实测提升。
- `Recall@5`：Vector-only `0.6667`，Hybrid `0.8583`，absolute lift `+0.1917`，relative lift `+28.75%`。
- `Recall@1`：Vector-only `0.2667`，Hybrid `0.3833`，absolute lift `+0.1167`，relative lift `+43.75%`。
- 这个结论只适用于 deterministic Milvus-backed recall benchmark，不等价于真实 LLM answer accuracy 提升。
- Hybrid recall 召回更强，但本轮实测平均 latency 更高；不能据此宣称“主链路延迟降低”。

目标文案里 `Recall@K +XX%` 现在可以谨慎填成：

> 在自建 deterministic recall benchmark（v3.1 real-agent workload，Milvus + BGE-Small，shared namespace candidate pool）中，Hybrid 相对 Vector-only 的 Recall@5 relative lift 为 `+28.75%`。

不要扩大表述为所有场景、所有 K、端到端 LLM 质量或线上 latency。

## 本轮完成内容

### 1. 修正 Recall Benchmark 方法论

上一轮新增的 `RecallBenchmarkRunner` 可以跑，但最初每个 case 都被隔离到单独 namespace，候选池太小，Vector-only 容易满分，benchmark 没有足够区分度。

本轮修正：

- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkRunner.java`
  - 按原始 dataset namespace 分组。
  - 同一 namespace 下所有 case 的 fragments 一次性写入同一个 run-scoped namespace。
  - 每个 case 在共享候选池里分别跑 Vector-only / Hybrid recall。
  - 结束后清理本轮写入的 fragments。
  - `topK` 严格使用 `vortex.eval.recall-top-k`，不再自动抬高到 expected fragment count。
- `vortex-app/src/test/java/com/vortex/app/eval/RecallBenchmarkRunnerTest.java`
  - 新增 `runShouldBenchmarkCasesAgainstSharedNamespaceCandidatePool`，锁住共享候选池行为。

关键位置：

- `RecallBenchmarkRunner.run(...)`：按 namespace group case。
- `RecallBenchmarkRunner.storeCaseFragments(...)`：批量写入 namespace candidate pool。
- `RecallBenchmarkRunner.runSingleCase(...)`：固定 configured `topK`。

### 2. 生成真实 Milvus-backed Recall Benchmark 报告

Docker compose 当前可用，Milvus / etcd / MinIO 均运行健康。

注意：默认 Milvus collection `vortex_memory` 已存在且维度是 `4`，当前 BGE-Small 配置维度是 `512`。为了不破坏现有数据，本轮没有 drop 默认 collection，而是用独立 collection 运行 benchmark：

- `vortex_memory_recall_20260626_001`
- `vortex_memory_recall_20260626_002`

真实报告：

- `ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001/recall-benchmark-20260625-162328.json`
- `ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001/recall-benchmark-20260625-162328.md`
- `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.json`
- `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.md`

文件名里的 `20260625` 来自 report writer 使用 UTC timestamp；本地运行时间是 2026-06-26 00:23/00:24（Asia/Shanghai）。

### 3. 实测指标

Dataset：

- `classpath:llm-memory-eval-set-v3-1-real-agent-workload.json`
- total cases：20
- total runs：40
- modes：`VORTEX_VECTOR_ONLY`, `VORTEX_MEMORY`
- generation：未启用；只测 recall，不测 LLM 生成。

`Recall@1` 报告：

| Mode | Recall@K | Absolute Lift | Relative Lift | Case Hit Rate | NDCG | Avg Latency Ms | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Vortex-VectorOnly | 0.2667 | 0.0000 | 0.0000 | 0.5500 | 0.5500 | 187.55 | 0 |
| Vortex-Memory | 0.3833 | 0.1167 | 0.4375 | 0.8000 | 0.8000 | 245.55 | 0 |

`Recall@5` 报告：

| Mode | Recall@K | Absolute Lift | Relative Lift | Case Hit Rate | NDCG | Avg Latency Ms | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Vortex-VectorOnly | 0.6667 | 0.0000 | 0.0000 | 0.9000 | 0.5907 | 199.20 | 0 |
| Vortex-Memory | 0.8583 | 0.1917 | 0.2875 | 1.0000 | 0.7825 | 298.30 | 0 |

Interpretation：

- Hybrid 对 recall 和 NDCG 有明确提升。
- Hybrid latency 高于 Vector-only，属于预期成本；不能拿这组数据证明 latency 降低。
- 如果要写简历或 README，推荐使用 `Recall@5 +28.75% relative lift`，并注明 benchmark 条件。

## 不应再使用的报告

以下两份是修正前跑出来的 per-case namespace 版本，候选池太小，Vector-only 容易满分，不能作为 `Recall@K +XX%` 证据：

- `ops/eval-reports/20260625-recall-benchmark-real-001/...`
- `ops/eval-reports/20260625-recall-benchmark-real-002-v3-1/...`

这些报告可以保留为调试记录，但不要引用为最终证据。

## 已验证命令

定向测试：

```powershell
mvn -pl vortex-app -am "-Dtest=RecallBenchmarkRunnerTest,RecallBenchmarkReportWriterTest,LlmMemoryEvalCliApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

- `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`

打包：

```powershell
mvn -DskipTests package
```

结果：

- `BUILD SUCCESS`
- eval CLI jar 已更新：`vortex-app/target/vortex-app-0.1.0-SNAPSHOT-eval-cli.jar`

完整 app 模块测试：

```powershell
mvn -pl vortex-app -am test
```

结果：

- `Tests run: 129, Failures: 0, Errors: 0, Skipped: 0`

空白检查：

```powershell
git diff --check
```

结果：

- 通过。
- 仅有既有 LF/CRLF warning。

## 真实 benchmark 运行命令

`Recall@1`：

```powershell
$env:LOGGING_LEVEL_COM_VORTEX='INFO'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_recall_20260626_001'
$env:VORTEX_EVAL_DATASET_LOCATION='classpath:llm-memory-eval-set-v3-1-real-agent-workload.json'
$env:VORTEX_EVAL_MODES='VORTEX_VECTOR_ONLY,VORTEX_MEMORY'
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001'
$env:VORTEX_EVAL_RECALL_TOP_K='1'
$env:VORTEX_EVAL_RECALL_TOKEN_BUDGET='1024'
$env:BGE_MODEL_PATH='models/bge-small-zh'
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar recall-benchmark
```

`Recall@5`：

```powershell
$env:LOGGING_LEVEL_COM_VORTEX='INFO'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_recall_20260626_002'
$env:VORTEX_EVAL_DATASET_LOCATION='classpath:llm-memory-eval-set-v3-1-real-agent-workload.json'
$env:VORTEX_EVAL_MODES='VORTEX_VECTOR_ONLY,VORTEX_MEMORY'
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001'
$env:VORTEX_EVAL_RECALL_TOP_K='5'
$env:VORTEX_EVAL_RECALL_TOKEN_BUDGET='1024'
$env:BGE_MODEL_PATH='models/bge-small-zh'
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar recall-benchmark
```

## 当前工作区状态

当前工作区仍然不是干净状态，且包含用户/上一轮已有状态。下一轮不要误清理。

重要状态：

- `README.md` 仍显示 deleted。不要恢复或删除，除非用户明确要求。
- `ops/runbooks/目标.md`、`ops/runbooks/未完成内容.md`、`ops/runbooks/上一次对话.txt` 是用户给的上下文/交接文件，目前仍为 untracked。
- `readme-history/`、`简历建议5.md`、`ops/runbooks/vortex-project-status-20260609.md` 也保持原状态。
- 本轮新生成的 `ops/eval-reports/20260626-recall-benchmark-*` 没出现在 `git status --short`，大概率被 ignore，但文件已存在。

本轮相关新增/修改集中在：

- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkRunner.java`
- `vortex-app/src/test/java/com/vortex/app/eval/RecallBenchmarkRunnerTest.java`
- 上一轮新增的 recall benchmark CLI/report 相关类仍在 untracked 状态。

## 当前仍不能宣称完成的目标项

仍不能宣称：

- `恢复成功率 XX%`：还没有统一 runtime recovery benchmark 结果。
- `主链路延迟降低 XX%`：本轮 recall benchmark 反而显示 Hybrid latency 高于 Vector-only；需要单独 async/sync pipeline latency benchmark。
- 完整 Agent Runtime：Task DAG/WAL/checkpoint 已有，但 Conversation / Tool 状态没有完整纳入统一 runtime snapshot。
- Tool Failure 恢复：还没有完整 ToolExecution runtime 和失败恢复闭环。
- LLM Timeout 任务级恢复：generation/eval 有 timeout 处理，但没有任务级 resume flow。
- Memory 抽取 / 摘要 / Embedding / 索引完整异步 pipeline：L2/L3 异步持久化已有，但完整 pipeline 还不完整。
- Frequency 维度评分：当前更准确是 recency + similarity + importance，另有 pin/regret/namespace quota。

可以谨慎宣称：

- Hybrid Retrieval Pipeline 已有关键词召回 + 向量召回 + rerank + diagnostics。
- 有 deterministic benchmark 对比 Vector-only。
- 在 v3.1 real-agent workload 的 Milvus-backed recall benchmark 中，`Recall@5` relative lift 为 `+28.75%`。

## 下一轮推荐开发顺序

### 优先级 1：整理证据资产和目标文案

目标：把 `目标.md` 中的 Recall@K 占位变成严谨表述。

建议：

- 在 README/简历文案中只写 benchmark 条件明确的数字。
- 引用 `Recall@5 +28.75% relative lift`。
- 不要写“LLM answer accuracy 提升 +28.75%”。
- 不要写“延迟降低”。

### 优先级 2：补 Runtime Recovery Benchmark

目标：产出 `恢复成功率 XX%`。

建议 benchmark case：

- checkpoint 后清空进程内状态，再 recover。
- repeated recover 幂等。
- Execution ID replay 不产生重复节点/重复 checkpoint。
- Tool failure 状态恢复。
- LLM timeout 后保留 task/conversation/tool 状态并 resume。

输出建议：

- `ops/eval-reports/<run-id>/runtime-recovery-benchmark.json`
- `ops/eval-reports/<run-id>/runtime-recovery-benchmark.md`

### 优先级 3：补 Runtime Snapshot 的 Conversation / Tool 状态

目标：对齐 `目标.md` 的 “Task / Conversation / Memory / Tool 状态持久化”。

建议新增：

- `ConversationState`
- `ToolExecutionState`
- `ToolExecutionStatus`
- Runtime snapshot/WAL operation 覆盖 conversation append、tool start/success/failure、llm call started、llm timeout/retry。

### 优先级 4：补 async pipeline latency benchmark

目标：产出“主链路延迟降低 XX%”。

需要先定义两个模式：

- sync baseline：store 阻塞到 embedding + L2 + L3 完成。
- async pipeline：主链路完成 L1 admission，embedding/index/archive 后台完成。

输出：

- p50 / p95 / p99 store latency
- recall readiness latency
- persistence success rate
- async 相对 sync 的主链路降低比例

## 下一轮注意事项

- 运行真实 benchmark 时，若默认 `vortex_memory` 仍是 dim=4，不要直接 drop；优先用 `VORTEX_STORAGE_L2_MILVUS_COLLECTION=<new_collection>`。
- 如果确实要迁移默认 collection，必须显式设置 drop confirm token，且要确认旧数据可丢弃。
- `RecallBenchmarkRunner` 已经改为共享 namespace candidate pool；不要回退到 per-case namespace。
- report writer 使用 UTC filename stamp，和本地日期可能相差一天。
- 不要清理用户的 untracked runbooks / readme-history / `简历建议5.md`。
- 不要恢复 `README.md` 的 deleted 状态，除非用户明确要求。
