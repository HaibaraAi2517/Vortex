# Vortex Benchmark Evidence

This page summarizes the benchmark results that are safe to cite from the
current repository state. Every headline metric below links to a committed
evidence runbook with scope, boundaries, and reproduction commands.

## Evidence Policy

- Performance and retrieval-quality claims must come from this page and its
  linked committed runbooks.
- Test counts and coverage are release-scoped verification, not benchmark
  metrics or guarantees about an arbitrary working tree. Cite them with the
  corresponding release notes; [`v0.1.1`](releases/v0.1.1.md) records
  `548` tests and `74.26%` aggregate line coverage.
- Roadmap items describe remaining work and do not override completed evidence.

## Headline Results

| Area | Safe wording | Evidence |
| --- | --- | --- |
| Recall | On the official LongMemEval oracle dataset, the 120-case case-isolated retrieval evaluation measured `VectorOnly` Recall@5 at `0.8094`, `+0.1856` over `KeywordOnly`, with a paired 95% CI of `[+0.1086, +0.2632]`. | [LongMemEval evaluation](../ops/runbooks/vortex-recall-longmemeval-evaluation-report-20260729.md) |
| Cross-Encoder | The locked ONNX DEV candidate changed ordering in `120/120` cases, but failed five frozen quality and latency gates. It was rejected; `VectorOnly` remains the Cross-Encoder promotion baseline, while the current public request default is guarded `HYBRID + RRF` with the additional reranker disabled. | [Cross-Encoder DEV decision](../ops/runbooks/vortex-cross-encoder-dev-decision-20260729.md) |
| Main-path latency | In the canonical deterministic write-through benchmark, async processing reduced measured main-path P99 from `818.82 ms` to `268.65 ms` (`-67.19%`). L1 was visible at return and L2/L3 eventually became ready in `100/100` cases. External LLM generation was excluded. | [Write-through latency evidence](../ops/runbooks/vortex-main-path-latency-write-through-evidence-20260728.md) |
| Runtime recovery | In the deterministic runtime recovery benchmark, Vortex passed `32/32` covered fault-injection cases across service restart, tool failure, LLM exception, state integrity, and concurrency categories. | [Runtime recovery evidence](../ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md) |

These numbers are benchmark evidence, not production guarantees.

The 2026-09-05 P1 reliability patch has a paired working-tree evaluation in
[P1 reliability regression](../ops/runbooks/vortex-p1-regression-20260905.md).

## Recall Retrieval Evaluation

Benchmark scope:

- Dataset: official LongMemEval oracle, converted to case-isolated retrieval cases
- Cases: `120`
- Runs: `600`
- Modes: `KeywordOnly`, `VectorOnly`, `Vector+Rerank`, `Hybrid`, `Hybrid+Rerank`
- Primary K: `5`
- Foreign returned fragments: `0`
- Generation: excluded; this evaluates retrieval of oracle evidence fragments

| Mode | Recall@5 | Case Hit Rate | MRR | NDCG | Avg Latency Ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| KeywordOnly | 0.6239 | 0.7333 | 0.5138 | 0.5065 | 2526.0 |
| VectorOnly | 0.8094 | 0.9333 | 0.6554 | 0.6493 | 163.1 |
| Vector+Rerank | 0.8094 | 0.9333 | 0.6554 | 0.6493 | 159.7 |
| Hybrid | 0.6317 | 0.7667 | 0.5349 | 0.5171 | 210.2 |
| Hybrid+Rerank | 0.7864 | 0.9167 | 0.6753 | 0.6635 | 210.3 |

The paired `VectorOnly` versus `KeywordOnly` Recall@5 delta was `+0.1856`,
with a 95% bootstrap CI of `[+0.1086, +0.2632]`.

Boundary: this is oracle fragment retrieval recall, not LLM answer accuracy.
It does not prove online production recall lift or end-to-end Agent quality.

## Current Recall Default

The current public request contract defaults to guarded `HYBRID + RRF`, with
the additional reranker disabled. That candidate passed the read-only 120-case
DEV gate and sealed 120-case validation gate documented in the
[Recall Ranking v2 evaluation](../ops/runbooks/vortex-recall-ranking-v2-evaluation-20260802.md).
Explicit `VECTOR_ONLY + LEGACY` remains the rollback and historical comparison
path. The earlier LongMemEval table above remains valid within its recorded
code, dataset, and protocol scope; it must not be restated as today's default.

## Cross-Encoder DEV Decision

The first locked candidate used ONNX Runtime CPU with the
`cross-encoder/ms-marco-MiniLM-L6-v2` INT8 artifact. On the 120-case DEV
partition it completed `360` runs with `0` errors and changed the baseline
ordering in `120/120` cases. The result was nevertheless rejected by the
pre-frozen decision gate:

| Measure | Result |
| --- | ---: |
| Recall@5 delta vs `VectorOnly` | +0.0072 |
| Recall 95% CI | [-0.0497, +0.0640] |
| NDCG delta / 95% CI | +0.0423 / [-0.0137, +0.0986] |
| Added P95 latency | +1330.65 ms |
| Added P99 latency | +1465.24 ms |

The candidate failed five gates: Recall delta, Recall CI lower bound, NDCG CI
lower bound, added P95 latency, and added P99 latency. `VectorOnly` therefore
remains the Cross-Encoder promotion baseline for that evidence. Validation and
reserve partitions were not opened for that rejected Cross-Encoder candidate.

## Main-Path Latency

The canonical run compares synchronous processing with async raw-memory L1
write-through. Async return includes local embedding and L1 visibility;
extraction, summary, final embedding, L2, and L3 complete in the background.

| Mode | Cases | Main Success | L1 Visible at Return | Eventual L2/L3 Ready | Avg Ms | P95 Ms | P99 Ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Sync | 100 | 100% | 100% | 100% | 747.95 | 815.45 | 818.82 |
| Async | 100 | 100% | 100% | 100% | 258.27 | 265.49 | 268.65 |

Boundary: external LLM generation is excluded. This local deterministic
benchmark is not a production P99 or full Agent execution latency claim.

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

## Do Not Claim

- Do not claim `99.99%` main-path latency reduction.
- Do not describe oracle fragment Recall@5 as LLM answer accuracy.
- Do not claim online production recall improvement.
- Do not claim production P99 or full Agent execution latency from the local
  write-through benchmark.
- Do not claim the rejected Cross-Encoder candidate passed DEV or is approved
  for validation.
- Do not claim complete production recovery coverage.
- Do not present the `v0.1.1` test count or coverage as a current-working-tree
  guarantee; cite those numbers only as versioned release verification.
