# Vortex Recall Benchmark Evidence - 2026-06-26

This note records the current defensible Recall@K evidence for Vortex wording.

## Benchmark Scope

- Dataset: `classpath:llm-memory-eval-set-v3-1-real-agent-workload.json`
- Cases: 20
- Runs: 40
- Modes: `VORTEX_VECTOR_ONLY`, `VORTEX_MEMORY`
- Retrieval comparison: vector-only control vs hybrid retrieval
- Storage/runtime: Docker-backed Milvus with BGE-Small embeddings
- Candidate pool: shared run-scoped namespace per original dataset namespace
- Generation: disabled; this is deterministic recall evaluation, not LLM answer generation

## Evidence Files

- TopK=1 JSON: `ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001/recall-benchmark-20260625-162328.json`
- TopK=1 Markdown: `ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001/recall-benchmark-20260625-162328.md`
- TopK=5 JSON: `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.json`
- TopK=5 Markdown: `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.md`

The report filenames use UTC timestamps. The local run date was 2026-06-26
Asia/Shanghai.

## Summary

| TopK | Mode | Recall@K | Absolute Lift | Relative Lift | Case Hit Rate | NDCG | Avg Latency Ms | Errors |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | Vortex-VectorOnly | 0.2667 | 0.0000 | 0.0000 | 0.5500 | 0.5500 | 187.55 | 0 |
| 1 | Vortex-Memory | 0.3833 | 0.1167 | 0.4375 | 0.8000 | 0.8000 | 245.55 | 0 |
| 5 | Vortex-VectorOnly | 0.6667 | 0.0000 | 0.0000 | 0.9000 | 0.5907 | 199.20 | 0 |
| 5 | Vortex-Memory | 0.8583 | 0.1917 | 0.2875 | 1.0000 | 0.7825 | 298.30 | 0 |

## Recommended Wording

Use this wording when a compact metric is needed:

> In a deterministic Milvus + BGE-Small recall benchmark on the v3.1
> real-agent workload, hybrid retrieval improved Recall@5 over vector-only
> from 0.6667 to 0.8583, a +28.75% relative lift.

Chinese resume/README wording:

> 在自建 deterministic recall benchmark（v3.1 real-agent workload，Milvus +
> BGE-Small，shared namespace candidate pool）中，Hybrid Retrieval 相对
> Vector-only 的 Recall@5 从 0.6667 提升到 0.8583，relative lift 为 +28.75%。

## Boundaries

Do not rewrite this result as:

- LLM answer accuracy improved by +28.75%.
- Online or end-to-end latency improved.
- Recall improves in every workload, every TopK, or every deployment.

This benchmark shows deterministic retrieval quality under the conditions above.
It also shows higher average latency for hybrid retrieval than vector-only in
this run, so it cannot support a "main path latency reduction" claim.

