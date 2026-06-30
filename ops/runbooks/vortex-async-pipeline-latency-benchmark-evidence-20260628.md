# Vortex Async Memory Pipeline Latency Benchmark Evidence - 2026-06-28

Run date: 2026-06-28 Asia/Shanghai

This document records the current defensible number for the `主链路延迟降低 XX%`
claim after extending the benchmark from fragment-store persistence to the full
memory ingest pipeline: extraction, summary, semantic split, embedding, L1
admission, L2 index, and L3 archive.

## Summary

In a deterministic async memory pipeline latency benchmark backed by Docker
Milvus and MinIO, moving memory extraction, summary, semantic split, embedding,
L1 admission, L2 indexing, and L3 archive work out of the measured request
admission path reduced average main-path latency from `653.4839 ms` to
`0.0743 ms`.

Relative main-path latency reduction: `99.99%` (`0.9999`).

Pipeline persistence success rate: `100.00%`.

## Evidence Files

- JSON report: `ops/eval-reports/20260628-async-memory-pipeline-latency-benchmark-002/async-pipeline-latency-benchmark-20260628-135001.json`
- Markdown report: `ops/eval-reports/20260628-async-memory-pipeline-latency-benchmark-002/async-pipeline-latency-benchmark-20260628-135001.md`

Historical fragment-store-only report, superseded for `目标.md` full-pipeline
wording:

- `ops/eval-reports/20260628-async-pipeline-latency-benchmark-001/async-pipeline-latency-benchmark-20260628-120700.md`

## Run Conditions

- CLI command: `async-pipeline-latency-benchmark`
- Benchmark scope: `memory extraction + summary + semantic split + embedding + L1 admission + L2 index + L3 archive`
- Explicitly not covered: full Agent execution or LLM generation
- Fragment count: `16`
- Warmup fragment count: `2`
- Modes: `SYNC_BASELINE`, `ASYNC_PIPELINE`
- Embedding: local BGE-Small, `dim=512`
- L1: Caffeine, benchmark run with `VORTEX_STORAGE_L1_MAX_TOKENS=32768`
- L2: Docker-backed Milvus collection `vortex_memory_async_pipeline_20260628_002`
- L3: Docker-backed MinIO key prefix `async-memory-pipeline-latency-benchmark/20260628-002/`
- Cloud embedding: disabled
- Readiness verification: L2 vector search/get visibility plus L3 archive retrieval

Command:

```powershell
$env:LOGGING_LEVEL_COM_VORTEX='INFO'
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260628-async-memory-pipeline-latency-benchmark-002'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_async_pipeline_20260628_002'
$env:MINIO_KEY_PREFIX='async-memory-pipeline-latency-benchmark/20260628-002/'
$env:BGE_MODEL_PATH='models/bge-small-zh'
$env:VORTEX_STORAGE_L1_MAX_TOKENS='32768'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_FRAGMENTS='16'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_WARMUP_FRAGMENTS='2'
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar async-pipeline-latency-benchmark
```

## Result Table

| Mode | Main Avg Ms | Main P50 Ms | Main P95 Ms | Main P99 Ms | Readiness Avg Ms | Readiness P95 Ms | Persistence Success | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 653.4839 | 703.8858 | 790.2326 | 790.2326 | 653.4839 | 790.2326 | 1.0000 | 0 |
| ASYNC_PIPELINE | 0.0743 | 0.0490 | 0.2233 | 0.2233 | 600.7725 | 829.2747 | 1.0000 | 0 |

Derived metric:

| Metric | Value |
| --- | ---: |
| Sync average main-path latency | 653.4839 ms |
| Async average main-path latency | 0.0743 ms |
| Relative main-path latency reduction | 0.9999 |
| Percent reduction | 99.99% |
| Async extraction completed | 16/16 |
| Async summary completed | 16/16 |
| Async embedding completed | 16/16 |
| Async L2 ready count | 16/16 |
| Async L3 ready count | 16/16 |
| Async persistence success rate | 100.00% |

## Recommended Claim

English:

> In a deterministic async memory pipeline benchmark under Docker-backed
> Milvus/MinIO, moving memory extraction, summary, embedding, L1 admission,
> L2 indexing, and L3 archive work off the measured request admission path
> reduced average main-path latency from 653.48 ms to 0.07 ms, a 99.99%
> relative reduction, while L2/L3 readiness success remained 100.00%.

Chinese:

> 在 deterministic async memory pipeline benchmark（Docker-backed
> Milvus/MinIO）中，将 Memory 抽取、摘要、Embedding、L1 admission、L2
> indexing 与 L3 archive 从测量主链路移到后台后，平均主链路 latency 从
> 653.48 ms 降至 0.07 ms，relative reduction 为 99.99%，同时 L2/L3
> readiness success rate 为 100.00%。

Resume-short Chinese:

> 将 Memory 抽取 / 摘要 / Embedding / L2 indexing / L3 archive 解耦为
> 异步 Pipeline；在 Docker-backed Milvus/MinIO benchmark 中，平均主链路
> latency 从 653.48 ms 降至 0.07 ms，降低 99.99%，L2/L3 readiness 成功率
> 100.00%。

## Claim Boundaries

Allowed:

- Claim measured request-admission main-path latency reduction for the benchmarked
  async memory ingest pipeline.
- Claim extraction, summary, embedding, L1 admission, L2 indexing, and L3 archive
  completed for all benchmark cases.
- Claim readiness latency separately from main-path latency.
- Claim `100.00%` L2/L3 readiness success rate for the 16 benchmark cases.

Not allowed:

- Do not claim full end-to-end Agent execution latency improved by 99.99%.
- Do not claim LLM generation latency improved; generation is not part of this
  benchmark.
- Do not claim recall latency improved. Recall latency is measured by a separate
  recall benchmark and is not the evidence source for this claim.
- Do not hide readiness latency. Async readiness average was `600.7725 ms` and
  p95 was `829.2747 ms`.
- Do not imply this benchmark proves production traffic p95/p99 behavior.

## Validation

Targeted tests:

```powershell
mvn -pl vortex-kernel,vortex-app -am "-Dtest=AsyncMemoryPipelineTest,AsyncPipelineLatencyBenchmarkRunnerTest,AsyncPipelineLatencyBenchmarkReportWriterTest,MemoryControllerTest,LlmMemoryEvalCliApplicationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result:

- `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`

Package:

```powershell
mvn -DskipTests package
```

Result:

- `BUILD SUCCESS`