# Recall Ranking V2 Evaluation - 2026-08-03

## Status

Production is promoted to guarded `HYBRID + RRF` by default.

The frozen `HYBRID_RRF` candidate passed both the read-only 120-case dev gate
and the sealed 120-case validation gate. `HYBRID_RRF_MMR` did not produce
independent quality lift and is not promoted.

Validation was executed exactly once with the lock below. Reserve remains
untouched. Explicit `VECTOR_ONLY + LEGACY` remains the rollback path.

## Frozen Candidate

- Candidate mode: `HYBRID_RRF`
- Baseline mode: `VECTOR_ONLY`
- TopK: 5
- Token budget: 4096
- RRF rank constant: 60
- Vector weight: 1.00
- Strong exact-feature keyword weight: 0.15
- Structured date/version keyword weight: 0.08
- Plain-number keyword weight: 0
- Relevance weight: 0.90
- Maximum memory-prior weight: 0.10
- Memory prior: 50% importance, 30% freshness, 20% decayed recall frequency
- Generation, scheduler, and paging: disabled
- Evaluation recall: read-only
- Model: local BGE-Small

No candidate weight, detector rule, ranking formula, evaluation protocol, or
gate may change after this lock without returning to dev and creating a new
candidate.

## Read-Only Evaluation Protocol

The benchmark stores setup fragments synchronously in L2/L3, clears L1 before
each run, and calls `recallReadOnlyForEvaluation()`.

Read-only evaluation performs real query embedding, Milvus retrieval, tag
filtering, ranking, token-budget selection, and diagnostics. It suppresses:

- L1 admission and recall reinforcement
- L2/L3 persistence after recall
- importance and last-access reinforcement
- decayed recall-frequency writes
- page fault and prefetch
- adaptive-learning recall sessions
- runtime SLO mutations

The hard isolation invariant is: when `keywordFusionWeight=0`, RRF and
VectorOnly must return exactly the same ordered fragment IDs for every case.

## Acceptance Gates

These thresholds were frozen before validation output was viewed:

| Gate | Requirement |
| --- | --- |
| Completeness | All partition cases complete; errors = 0 |
| Recall@5 | Candidate >= VectorOnly |
| MRR | Candidate >= VectorOnly |
| NDCG@5 | Candidate >= VectorOnly |
| Case-level Recall | wins >= losses and losses = 0 |
| Case-level NDCG | wins >= losses |
| No-keyword invariant | zero order changes when keyword weight is 0 |
| Trigger boundary | only frozen exact-feature reasons; plain numbers disabled |
| Added P95 | <= 10 ms and <= 5% |
| Added P99 | <= 20 ms and <= 5%; absolute P99 <= 500 ms |

Validation failure on any gate keeps `VECTOR_ONLY + LEGACY` as default.
Thresholds must not be weakened after validation.

## Dev Evidence

Dataset:

`ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-dev-120-case-isolated.json`

Canonical report:

`ops/eval-reports/20260803-recall-ranking-v2-readonly-dev-006/recall-benchmark-20260802-165122.json`

| Mode | Errors | Recall@5 | MRR | NDCG | Avg ms | P95 ms | P99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| VectorOnly | 0 | 0.818611 | 0.710417 | 0.692846 | 176.06 | 204 | 213 |
| Hybrid+RRF | 0 | 0.818611 | 0.711111 | 0.693200 | 174.38 | 203 | 213 |
| Hybrid+RRF+MMR | 0 | 0.818611 | 0.711111 | 0.693200 | 174.14 | 202 | 209 |

RRF versus VectorOnly:

- Recall wins/losses: 0 / 0
- MRR wins/losses: 1 / 0
- NDCG wins/losses: 1 / 0
- No-keyword order changes: 0
- Keyword triggers: 1
- Only changed case: `10e09553`, reason `STRUCTURED_NUMBER`, weight 0.08
- Plain-money case `gpt4_18c2b244`: reason `NONE`, weight 0

MMR versus RRF:

- Order changes: 1
- Recall, MRR, and NDCG lift: 0

Dev gate result: PASS for RRF; MMR rejected.

## Validation Lock

Lock written before the validation partition was opened.

- Git base HEAD: `f9aac2a57d46ee230c987a1bace32b910e2c46f3`
- Worktree: dirty; the immutable validation identity is the packaged JAR hash
- Dev report SHA-256:
  `0da85eecc797c311c7936c96c5d5e0d3c7008c3f748f6798f2d9cf8fef2a52e7`
- Dev dataset SHA-256:
  `df6f58a141b6b4b54e478c04f87d38bb4b56d9601d913f5dd7f6114fa7a6a5f6`
- Validation dataset SHA-256:
  `9c3a328cb023677c355acae073592eeb41ea0a2571cbfb3b74ee8634c2ea3834`
- Split manifest SHA-256:
  `379ca45a6437ae2f06b13448651adf4e0f259b375067bdda8aeb11ccc7abba98`
- Eval CLI JAR SHA-256:
  `547c0d5dfd22eff1ed09a527d159696c14af81f5c95b4b6b369d269560aa3f0c`
- BGE model SHA-256:
  `69a0b846f4f116b5e6aabf9546ea6754d02264f3211a13a1bd69b31b8040749a`
- BGE tokenizer SHA-256:
  `48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26`
- Java: 21.0.10
- OS: Windows 11 amd64
- Available processors: 32
- Max JVM heap: 4,213,178,368 bytes

Validation modes are frozen to:

```text
VECTOR_ONLY,HYBRID_RRF
```

The validation run used a new Milvus collection, MinIO prefix, WAL, DLQ,
processed-key file, and report directory.

## Validation Evidence

Dataset:

`ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-validation-120-case-isolated.json`

Canonical report:

`ops/eval-reports/20260803-recall-ranking-v2-readonly-validation-001/recall-benchmark-20260802-170327.json`

Report SHA-256:

`2de511cba37df24a5fb3f0cee05fae74a581be39f1433ed9d61c15eb72628727`

| Mode | Errors | Recall@5 | MRR | NDCG | Avg ms | P95 ms | P99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| VectorOnly | 0 | 0.782778 | 0.655556 | 0.637988 | 168.45 | 197 | 232 |
| Hybrid+RRF | 0 | 0.791111 | 0.658333 | 0.642154 | 167.33 | 197 | 212 |

RRF versus VectorOnly:

- Recall@5 delta: +0.008333
- MRR delta: +0.002778
- NDCG delta: +0.004167
- Recall wins/losses: 1 / 0
- MRR wins/losses: 1 / 0
- NDCG wins/losses: 1 / 0
- No-keyword order changes: 0
- Keyword triggers: 1
- Changed case: `6222b6eb`, reason `IDENTIFIER`, weight 0.15
- Average latency delta: -1.12 ms
- P95 delta: 0 ms
- P99 delta: -20 ms

Validation gate result: PASS.

## Final Decision

The default `RecallQuery` configuration is:

- Retrieval: `HYBRID`
- Ranking: `RRF`
- Reranker: disabled

The namespace fallback is guarded by the same exact-feature signal, so queries
without an approved signal retain the VectorOnly candidate boundary and Legacy
stable order. MMR remains opt-in. Reserve was not opened.

Post-validation promotion changes were limited to:

- `RecallQuery` defaults: `HYBRID + RRF`
- null-field fallback defaults in `RecallOrchestrator`
- namespace fallback now requires an enabled exact-feature keyword signal
- default-path tests and evidence documentation

No frozen detector rule, RRF weight, memory-prior weight, MMR setting, or
evaluation gate changed after validation.

Final promoted eval CLI JAR SHA-256:

`56435ae98dc06e2df37539dbcd2f67fdc8636d981f8d0bc267a8a45d62c6934e`

## Superseded Evidence

Reports produced before read-only evaluation are retained only as diagnostic
evidence. They are not valid promotion evidence because each recall mode could
reinforce and persist selected candidates before the next mode ran.

Affected runs include:

- `20260803-recall-ranking-v2-guarded-dev-003`
- `20260803-recall-ranking-v2-guarded-dev-004`
- `20260803-recall-ranking-v2-guarded-dev-005`

The old canonical `20260802-recall-ranking-v2-dev-002` also predates the
guarded detector and read-only protocol and remains historical only.
