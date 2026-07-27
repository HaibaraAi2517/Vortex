# Vortex Benchmark Evidence

This page summarizes the benchmark results that are safe to cite from the
current repository state. Every headline metric below links to a committed
evidence runbook with scope, boundaries, and reproduction commands.

## Headline Results

| Area | Safe wording | Evidence |
| --- | --- | --- |
| Hybrid recall | In a deterministic Docker-backed Milvus + BGE-Small recall ablation on the v3.1 real-agent workload, `Hybrid+Rerank` improved Recall@5 over `Vector+Rerank` from `0.7917` to `0.9500`, a `+0.1583` absolute and `+20.00%` relative lift, with `0/100` benchmark run errors across five retrieval modes. | [Recall ablation evidence](../ops/runbooks/vortex-recall-ablation-benchmark-evidence-20260630.md) |
| Main-path latency | In a deterministic main-path benchmark under Docker-backed Milvus/MinIO, moving memory extraction, summary, embedding, L1 admission, L2 indexing, and L3 archival off the request path reduced measured P99 from `1172.50 ms` to `220.34 ms` and average latency from `829.40 ms` to `186.64 ms`. | [Main-path latency evidence](../ops/runbooks/vortex-main-path-latency-benchmark-evidence-20260629.md) |
| Runtime recovery | In the deterministic runtime recovery benchmark, Vortex passed `32/32` covered fault-injection cases across service restart, tool failure, LLM exception, state integrity, and concurrency categories. | [Runtime recovery evidence](../ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md) |

These numbers are benchmark evidence, not production guarantees.

## Recall Ablation

Benchmark scope:

- Dataset: `classpath:llm-memory-eval-set-v3-1-real-agent-workload.json`
- Cases: `20`
- Runs: `100`
- Modes: `KeywordOnly`, `VectorOnly`, `Vector+Rerank`, `Hybrid`, `Hybrid+Rerank`
- Primary K: `5`
- Storage/runtime: Docker-backed Milvus/MinIO with local BGE-Small embeddings
- Semantic paging: disabled with `VORTEX_PAGING_ENABLED=false`
- Generation: disabled; this is deterministic retrieval evaluation

| Mode | Recall@5 | Precision@5 | MRR | NDCG@5 | Avg Latency Ms | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| VectorOnly | 0.7917 | 0.3400 | 0.7750 | 0.6754 | 248.5000 | 0 |
| Vector+Rerank | 0.7917 | 0.3400 | 0.7750 | 0.6754 | 249.3000 | 0 |
| KeywordOnly | 0.9333 | 0.4000 | 0.8917 | 0.8352 | 685.6000 | 0 |
| Hybrid | 0.9500 | 0.4100 | 0.8750 | 0.8378 | 371.8500 | 0 |
| Hybrid+Rerank | 0.9500 | 0.4100 | 0.8750 | 0.8343 | 360.5500 | 0 |

Boundary: this supports deterministic retrieval-quality wording only. It does
not prove LLM answer accuracy lift, online production recall lift, or
end-to-end Agent quality lift.

## Main-Path Latency

Measured request path:

```text
request -> hybrid retrieval -> rerank -> prompt/context assembly -> return payload
```

External LLM generation is excluded. Background async memory pipeline readiness
is reported separately in the evidence runbook.

| Mode | Main Avg Ms | Main P50 Ms | Main P95 Ms | Main P99 Ms | Main Success | Persistence Success | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SYNC_BASELINE | 829.3997 | 863.9291 | 1172.5000 | 1172.5000 | 1.0000 | 1.0000 | 0 |
| ASYNC_PIPELINE | 186.6363 | 184.6629 | 220.3377 | 220.3377 | 1.0000 | 1.0000 | 0 |

Boundary: this is not a full Agent execution latency claim because real LLM
generation is not part of the measured path.

## Runtime Recovery

Benchmark scope:

- Command: `runtime-recovery-benchmark`
- Random seed recorded in report: `20260629`
- Total cases: `32`
- Passed cases: `32`
- Failed cases: `0`

| Category | Cases | Passed | Failed | Success Rate | Avg Latency Ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Service restart | 6 | 6 | 0 | 1.0000 | 121.6667 |
| Tool failure | 8 | 8 | 0 | 1.0000 | 46.1250 |
| LLM exception | 6 | 6 | 0 | 1.0000 | 49.3333 |
| State integrity | 7 | 7 | 0 | 1.0000 | 51.5714 |
| Concurrency | 5 | 5 | 0 | 1.0000 | 89.0000 |

Boundary: this is covered deterministic recovery behavior over the current
checkpoint/WAL/runtime-state surface. It does not claim complete production
recovery coverage.

## Pending Public-Dataset Promotion

The execution plan asks for public dataset cross-validation. This README/docs
pass intentionally does not promote a public-dataset headline metric yet,
because the committed evidence promoted by the current repository state is the
deterministic v3.1 workload evidence above.

Add a public LongMemEval or other public-dataset result here only after the
dataset conversion, report artifacts, model/base URL disclosure, and boundary
notes are committed and reviewed.

## Do Not Claim

- Do not claim `99.99%` main-path latency reduction.
- Do not claim `20.00%` LLM answer accuracy improvement.
- Do not claim online production recall improvement.
- Do not claim complete production recovery coverage.
- Do not quote local coverage or test-count numbers in public docs until the
  latest reports are regenerated and committed or otherwise reproducibly
  verified.
