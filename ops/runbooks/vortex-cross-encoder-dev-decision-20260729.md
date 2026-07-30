# Vortex Cross-Encoder DEV Decision - 2026-07-29

## Decision

Status: **REJECTED**. Keep `VectorOnly` as the default recall strategy. Do not run validation or
reserve with this model/configuration.

The first eligible dev-only run used the pinned `cross-encoder/ms-marco-MiniLM-L6-v2` AVX2 INT8
ONNX artifact on CPU. It completed 120 cases and 360 paired runs with zero errors and zero foreign
returned fragments. The model changed candidate order in 120/120 cases, so the experiment was
identifiable, but it failed five frozen DEV rules.

## Frozen Inputs

- Run: `20260729-cross-encoder-dev-minilm-l6-int8-002`
- Dataset SHA-256: `df6f58a141b6b4b54e478c04f87d38bb4b56d9601d913f5dd7f6114fa7a6a5f6`
- Split manifest schema/hash: `2` / `379ca45a6437ae2f06b13448651adf4e0f259b375067bdda8aeb11ccc7abba98`
- Modes: `VECTOR_ONLY,VECTOR_RERANK,VECTOR_CROSS_ENCODER`
- Candidate strategy: `VECTOR_BASELINE_TOP_40`, limit `40`
- Model revision: `c5ee24cb16019beea0893ab7796b1df96625c6b8`
- Model SHA-256: `c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9`
- Provider: ONNX Runtime Java 1.18.0 CPU, batch 16, max length 512, 8 intra-op threads
- Bootstrap: 20,000 paired case-level iterations, seed `20260729`

## Results

| Metric | VectorOnly | Cross-Encoder | Delta / evidence |
| --- | ---: | ---: | --- |
| Recall@5 | 0.8186 | 0.8258 | +0.0072, 95% CI [-0.0497, +0.0640] |
| Case hit | 0.9333 | 0.9250 | -0.0083 |
| MRR | 0.7104 | 0.7506 | +0.0401 |
| NDCG | 0.6928 | 0.7352 | +0.0423, 95% CI [-0.0137, +0.0986] |
| Average latency | 197.78 ms | 948.26 ms | +750.48 ms, 4.79x baseline |
| P95 latency | 241.00 ms | 1571.65 ms | +1330.65 ms |
| P99 latency | 288.39 ms | 1753.63 ms | +1465.24 ms |

Recall wins/ties/losses were `15/87/18`. Cross-Encoder scoring alone measured P95 `1350.97 ms`
and P99 `1519.50 ms`. Ordering changed in every case and the report recorded 2,587 distinct model
scores across candidate pools.

## Gate Outcome

Failed rules:

- Recall delta `+0.0072 < +0.0200`
- Recall CI lower `-0.0497 <= 0`
- NDCG CI lower `-0.0137 < 0`
- Added P95 latency `1330.65 ms > 250 ms`
- Added P99 latency `1465.24 ms > 500 ms`

The NDCG point delta, MRR non-regression, changed-order rate, error count, case count, model
metadata, Top40 strategy, environment snapshot, and case-isolation rules passed. Point improvements
in MRR/NDCG do not override the failed Recall uncertainty and latency rules.

## Evidence

- Raw report SHA-256: `7562433e3edf8cb8d4e16428d3a380c952f505301af9b179290c848283590409`
- Analysis SHA-256: `9462a8d2dc3e19a8b80aa8037a5c422a61f327cd5bb55e4b179baf74b24783e0`
- Provider settings SHA-256: `6a9e4551ec668e52d24b5502d966705643ffc74e4e0f5309dc70f7eb2b2cd8a8`
- Evidence directory: `ops/eval-reports/20260729-cross-encoder-dev-minilm-l6-int8-002/`

The earlier `-001` launch is not a model result. Manifest v1 contained abstention cases and the
runner rejected it before any score call. Manifest v2 quarantined 21 unlabeled abstention cases;
the post-run governance validator passed with exactly two authorized dev evidence sources and no
validation/reserve/quarantine usage.

## Boundary

No validation model run is authorized. No reserve model run is authorized. Thresholds were not
changed after viewing results. A future Cross-Encoder candidate requires a separately pinned
artifact and a new isolated dev run; this rejected configuration must not be reinterpreted as a
validation candidate.
