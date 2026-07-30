# Vortex Main-Path Latency And L1 Write-Through Evidence - 2026-07-28

Run date: 2026-07-28 Asia/Shanghai

This note supersedes the 2026-06-29 latency numbers for current resume wording.
The async path now performs a synchronous raw-memory L1 write-through before
returning, then processes extraction, summary, final splitting/embedding, L2
indexing, and L3 archival in the bounded background pipeline.

## Canonical Run

- Cases: `100` measured cases per mode, `200` total measured results
- Warmup: `10` cases per mode
- Modes: `SYNC_BASELINE`, `ASYNC_PIPELINE`
- Namespace isolation: one namespace per `mode x case`
- L1 capacity: `65536` tokens
- Embedding: local BGE-Small, `dim=512`
- Storage: Docker-backed Milvus and MinIO
- Workers: `4`
- Queue capacity: `8`
- Configured rejection policy: `CALLER_RUNS`
- External LLM generation: excluded

Evidence files:

- JSON: `ops/eval-reports/20260728-main-path-latency-write-through-005/async-pipeline-latency-benchmark-20260728-134949.json`
- Markdown: `ops/eval-reports/20260728-main-path-latency-write-through-005/async-pipeline-latency-benchmark-20260728-134949.md`

The raw report directory is ignored by Git. This tracked note is the durable
summary; the raw reports remain local evidence assets.

## Results

| Mode | Cases | Errors | Main Success | L1 Visible At Return | L2/L3 Persistence | Main Avg Ms | Main P50 Ms | Main P95 Ms | Main P99 Ms | Write Submit P95 Ms | Readiness Avg Ms | Readiness P95 Ms | Readiness P99 Ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 100 | 0 | 100.00% | 100.00% | 100.00% | 747.9457 | 800.3775 | 815.4482 | 818.8216 | 679.3135 | 747.9550 | 815.4557 | 818.8301 |
| ASYNC_PIPELINE | 100 | 0 | 100.00% | 100.00% | 100.00% | 258.2654 | 257.5744 | 265.4898 | 268.6525 | 129.7963 | 904.9752 | 1054.3335 | 1064.7306 |

Derived results:

- Average main-path reduction: `65.47%` (`747.9457 ms -> 258.2654 ms`).
- Measured P99 reduction: `67.19%` (`818.8216 ms -> 268.6525 ms`).
- Async readiness lag average: `646.7099 ms`.
- Both modes returned the newly submitted write in L1 at the return boundary
  for `100/100` cases.
- Both modes reached L2/L3 readiness for `100/100` cases.

## Read-Your-Own-Write Contract

`AsyncMemoryPipeline.submit` now stages the raw input in L1 before publishing
the accepted status. The write-through fragment is locally embedded so normal
hybrid recall can rank it. Its caller-visible pin deadline is captured and
replaced with an internal `Long.MAX_VALUE` pin while background processing is
in flight, preventing capacity admission from evicting the transient entry.
Background processing creates the final fragments, waits for L2/L3 persistence,
and only then removes the transient L1 entries. If background processing fails,
the transient entry remains available for diagnosis and recall while its pin
state is restored to the caller's original deadline.

The focused test deliberately blocks extraction after submission and verifies
that hybrid recall can still return the just-submitted content before the
background pipeline is allowed to continue:

`AsyncMemoryPipelineTest.submitShouldMakeWriteRecallableBeforeBackgroundProcessingCompletes`

The failure-path test verifies that an unpinned write is retained after a
simulated L2 persistence failure without leaking the internal permanent pin:

`AsyncMemoryPipelineTest.asyncFailureShouldRestoreOriginalPinStateAndRetainWriteThroughContent`

## Backpressure Boundary

The canonical run used a bounded queue with capacity `8` and configured
`CALLER_RUNS`. Its separate 14-write probe completed `14/14` with `0` errors,
but synchronous L1 write-through throttled submissions enough that the queue
did not saturate (`max queue=1`, `CallerRuns=0`). This run therefore proves the
bounded configuration and successful burst completion, not CallerRuns
activation. Do not claim that this canonical run exercised CallerRuns.

## Non-Canonical Diagnostic Runs

Do not cite `-001`, `-002`, `-003`, or `-004` as final evidence:

- `-001` used a shared namespace and filled the 32768-token L1 during the
  expanded workload, mixing capacity pressure into the latency comparison.
- `-002` increased L1 capacity but still reused eight service tags across 100
  cases, allowing earlier writes to crowd later TopK results.
- `-003` made service tags unique but retained one shared namespace. Namespace
  quota reclamation moved seeds to L2, where semantic TopK was applied before
  tag filtering, so later target seeds were not guaranteed to enter the
  candidate set.
- `-004` fixed the protocol artifacts and is valid for the earlier write-through
  behavior, but predates the internal pin that protects transient entries for
  the full background-processing window.

The canonical `-005` run removes the protocol artifacts with one namespace per
mode and case and measures the internally pinned write-through behavior.

## Reproduction Command

```powershell
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260728-main-path-latency-write-through-005'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_main_path_ryow_20260728_005'
$env:MINIO_KEY_PREFIX='main-path-latency-write-through/20260728-005/'
$env:VORTEX_WAL_DIR='E:/tmp/vortex-main-path-latency-write-through-20260728-005/wal'
$env:BGE_MODEL_PATH='models/bge-small-zh'
$env:VORTEX_STORAGE_L1_MAX_TOKENS='65536'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_FRAGMENTS='100'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_WARMUP_FRAGMENTS='10'
$env:VORTEX_EVAL_ASYNC_PIPELINE_BENCHMARK_ASYNC_PARALLELISM='4'
$env:VORTEX_MEMORY_PIPELINE_MAX_WORKERS='4'
$env:VORTEX_MEMORY_PIPELINE_QUEUE_CAPACITY='8'
$env:VORTEX_SCHEDULER_ENABLED='false'
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar async-pipeline-latency-benchmark
```

## Resume-Safe Wording

> Moved memory extraction, summary, final-fragment embedding, L2 indexing, and
> L3 archival to a bounded background pipeline while synchronously writing a
> raw-memory representation to L1 for read-your-own-write. In a 100-case-per-mode
> Docker-backed Milvus/MinIO benchmark, measured main-path P99 fell from
> 818.82 ms to 268.65 ms (-67.19%) and average latency fell from 747.95 ms to
> 258.27 ms (-65.47%); main-path success, L1 visibility at return, and L2/L3
> readiness were all 100%.

## Claim Boundaries

Allowed:

- Quote the 100-case measured main-path average and P99 values above.
- Claim synchronous L1 visibility at the async return boundary for 100/100
  canonical cases.
- Report L2/L3 readiness separately from main-path latency.

Not allowed:

- Do not reuse the old `1172.50 ms -> 220.34 ms` result after this behavior
  change; it did not include synchronous raw-memory L1 write-through.
- Do not call this full Agent E2E latency; external LLM generation is excluded.
- Do not call this production P99; it is a deterministic 100-case benchmark.
- Do not say all embedding work is asynchronous. Raw-memory L1 embedding is
  synchronous; final processed-fragment embedding remains in the background.
- Do not say the canonical run exercised CallerRuns.
