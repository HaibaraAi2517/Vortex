# Vortex Main-Path Latency Benchmark Evidence - 2026-06-29

Run date: 2026-06-29 Asia/Shanghai

This document replaces the earlier `99.99% latency reduction` wording for the
main request path. The prior number only measured async admission/enqueue for
memory ingest. This run measures the request main path as:

`request -> hybrid retrieval -> rerank -> prompt/context assembly -> return payload`

The benchmark excludes external LLM generation. It separately reports the
background async memory pipeline readiness latency.

## Summary

In a deterministic main-path latency benchmark backed by Docker Milvus and
MinIO, moving memory extraction, summary, embedding, L1 admission, L2 indexing,
and L3 archive work out of the measured request path reduced average main-path
latency from `829.3997 ms` to `186.6363 ms`.

Relative main-path latency reduction: `77.50%` (`0.7750`).

Main-path success rate: `100.00%` for both modes.

Persistence/readiness success rate: `100.00%` for both modes.

## Evidence Files

- JSON report: `ops/eval-reports/20260629-main-path-latency-benchmark-003/async-pipeline-latency-benchmark-20260629-151448.json`
- Markdown report: `ops/eval-reports/20260629-main-path-latency-benchmark-003/async-pipeline-latency-benchmark-20260629-151448.md`

Superseded earlier async-ingest-only evidence:

- `ops/eval-reports/20260628-async-memory-pipeline-latency-benchmark-002/async-pipeline-latency-benchmark-20260628-135001.json`
- `ops/runbooks/vortex-async-pipeline-latency-benchmark-evidence-20260628.md`

## Run Conditions

- CLI command: `async-pipeline-latency-benchmark`
- Main path: request, hybrid retrieval, rerank, prompt/context assembly, return payload
- Excluded: external LLM generation
- Background async pipeline: extraction, summary, semantic split, embedding, L1 admission, L2 index, L3 archive
- Fragment count: `16`
- Warmup fragment count: `2`
- Modes: `SYNC_BASELINE`, `ASYNC_PIPELINE`
- Embedding: local BGE-Small, `dim=512`
- L2: Docker-backed Milvus collection `vortex_memory_main_path_latency_20260629_003`
- L3: Docker-backed MinIO key prefix `main-path-latency-benchmark/20260629-003/`
- Memory pipeline workers: `4`
- Memory pipeline queue capacity: `8`
- Backpressure policy: `CALLER_RUNS`

Command:

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

## Main Path Result Table

| Mode | Main Avg Ms | Main P50 Ms | Main P95 Ms | Main P99 Ms | Recall P95 Ms | Prompt P95 Ms | Write Submit P95 Ms | Main Success | Persistence Success | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 829.3997 | 863.9291 | 1172.5000 | 1172.5000 | 485.2810 | 13.1784 | 952.8537 | 1.0000 | 1.0000 | 0 |
| ASYNC_PIPELINE | 186.6363 | 184.6629 | 220.3377 | 220.3377 | 206.9066 | 13.5065 | 0.1981 | 1.0000 | 1.0000 | 0 |

Derived metric:

| Metric | Value |
| --- | ---: |
| Sync average main-path latency | 829.3997 ms |
| Async average main-path latency | 186.6363 ms |
| Relative main-path latency reduction | 0.7750 |
| Percent reduction | 77.50% |
| Sync main-path P99 | 1172.5000 ms |
| Async main-path P99 | 220.3377 ms |
| Sync returned fragment average | 5.0000 |
| Async returned fragment average | 5.0000 |

## Background Pipeline And Readiness

| Mode | Pipeline Avg Ms | Pipeline P95 Ms | Pipeline TPS | Readiness P95 Ms | Readiness Lag Avg Ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 155.8646 | 180.0704 | 6.4158 | 1172.5084 | 0.0162 |
| ASYNC_PIPELINE | 151.5252 | 164.6405 | 6.5996 | 972.1643 | 585.9576 |

Async readiness is intentionally separate from main-path latency. The async
request returns after enqueue, while L2/L3 readiness completes later.

## Backpressure Probe

The backpressure probe is separate from the main-path percentile population.
It submits a burst to the async pipeline and waits for readiness afterward.

| Policy | Queue Capacity | Submitted | Completed | Errors | CallerRuns During Benchmark | Max Queue | Saturated | Submit P95 Ms | Submit P99 Ms | Readiness P95 Ms | Readiness P99 Ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: |
| CALLER_RUNS | 8 | 14 | 14 | 0 | 2 | 8 | true | 195.2956 | 195.2956 | 1226.8611 | 1226.8611 |

## Recommended Claim

English:

> In a deterministic main-path benchmark under Docker-backed Milvus/MinIO,
> moving memory extraction, summary, embedding, L1 admission, L2 indexing, and
> L3 archival off the request path reduced measured main-path P99 latency from
> 1172.50 ms to 220.34 ms and average main-path latency from 829.40 ms to
> 186.64 ms, while main-path success and L2/L3 readiness success remained
> 100.00%.

Chinese:

> 在 deterministic main-path benchmark（Docker-backed Milvus/MinIO）中，将
> Memory 抽取、摘要、Embedding、L1 admission、L2 indexing 与 L3 archive
> 从请求主路径移到后台后，主路径 P99 latency 从 1172.50 ms 降至
> 220.34 ms，平均主路径 latency 从 829.40 ms 降至 186.64 ms；主路径成功率
> 与 L2/L3 readiness 成功率均为 100.00%。

Resume-short Chinese:

> 将 Memory 抽取 / 摘要 / Embedding / L2 indexing / L3 archive 移出主路径；
> 在 Docker-backed Milvus/MinIO main-path benchmark 中，主路径 P99 从
> 1172.50 ms 降至 220.34 ms，平均主路径 latency 从 829.40 ms 降至
> 186.64 ms，主路径与 L2/L3 readiness 成功率均为 100%。

## Claim Boundaries

Allowed:

- Claim measured main-path latency reduction for request -> retrieval -> rerank -> prompt/context assembly -> return payload.
- Claim background pipeline readiness separately from main-path latency.
- Claim `CALLER_RUNS` backpressure policy was exercised by a separate burst probe.
- Claim L2/L3 readiness success for all 16 measured cases in both modes.

Not allowed:

- Do not claim `99.99%` main-path latency reduction.
- Do not claim full Agent execution latency, because no real LLM generation is included.
- Do not claim production p95/p99 behavior from this deterministic benchmark.
- Do not hide async readiness latency; async readiness P95 was `972.1643 ms` and readiness lag average was `585.9576 ms`.

## Validation

Focused tests:

```powershell
mvn -pl vortex-kernel,vortex-app -am '-Dtest=AsyncMemoryPipelineTest,AsyncPipelineLatencyBenchmarkRunnerTest,AsyncPipelineLatencyBenchmarkReportWriterTest,LlmMemoryEvalCliApplicationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result:

- `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0`

Package:

```powershell
mvn -pl vortex-app -am -DskipTests package
```

Result:

- `BUILD SUCCESS`