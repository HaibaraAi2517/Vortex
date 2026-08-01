# Vortex Recall Ablation Benchmark Evidence - 2026-06-30

This note records the current five-mode recall ablation evidence for Vortex
hybrid retrieval wording.

## Benchmark Scope

- Dataset: `classpath:llm-memory-eval-set-v3-1-real-agent-workload.json`
- Cases: `20`
- Runs: `100`
- Modes: `KeywordOnly`, `VectorOnly`, `Vector+Rerank`, `Hybrid`, `Hybrid+Rerank`
- Evaluation K values: `1`, `3`, `5`, `10`
- Primary TopK: `5`
- Storage/runtime: Docker-backed Milvus/MinIO with local BGE-Small embeddings
- Candidate pool: shared run-scoped namespace per original dataset namespace
- Semantic paging: disabled with `VORTEX_PAGING_ENABLED=false` to isolate retrieval ablation from L1/page-fault side effects
- Generation: disabled; this is deterministic retrieval evaluation, not LLM answer generation

## Evidence Files

- JSON: `ops/eval-reports/20260630-recall-ablation-benchmark-v3-1-003/recall-benchmark-20260630-094550.json`
- Markdown: `ops/eval-reports/20260630-recall-ablation-benchmark-v3-1-003/recall-benchmark-20260630-094550.md`

The report filenames use UTC timestamps. The local run date was 2026-06-30
Asia/Shanghai.

## Primary Summary

Lift is calculated against `Vector+Rerank` because that is the vector-only
candidate-generation baseline with the rerank flag enabled.

| Mode | Recall@5 | Absolute Lift | Relative Lift | Case Hit Rate | All Expected Rate | Precision@5 | MRR | NDCG@5 | Avg Latency Ms | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| VectorOnly | 0.7917 | 0.0000 | 0.0000 | 1.0000 | 0.6000 | 0.3400 | 0.7750 | 0.6754 | 248.5000 | 0 |
| Vector+Rerank | 0.7917 | 0.0000 | 0.0000 | 1.0000 | 0.6000 | 0.3400 | 0.7750 | 0.6754 | 249.3000 | 0 |
| KeywordOnly | 0.9333 | 0.1417 | 0.1789 | 1.0000 | 0.8500 | 0.4000 | 0.8917 | 0.8352 | 685.6000 | 0 |
| Hybrid | 0.9500 | 0.1583 | 0.2000 | 1.0000 | 0.9000 | 0.4100 | 0.8750 | 0.8378 | 371.8500 | 0 |
| Hybrid+Rerank | 0.9500 | 0.1583 | 0.2000 | 1.0000 | 0.9000 | 0.4100 | 0.8750 | 0.8343 | 360.5500 | 0 |

## Metrics By K

Precision@K uses the standard denominator `K`, not the number of returned
fragments. This matters when a mode returns fewer than K fragments.

| Mode | K | Recall | Precision | MRR | NDCG |
| --- | ---: | ---: | ---: | ---: | ---: |
| VectorOnly | 1 | 0.2833 | 0.6000 | 0.6000 | 0.6000 |
| VectorOnly | 3 | 0.5833 | 0.4167 | 0.7750 | 0.5651 |
| VectorOnly | 5 | 0.7917 | 0.3400 | 0.7750 | 0.6754 |
| VectorOnly | 10 | 0.8167 | 0.1750 | 0.7750 | 0.6863 |
| Vector+Rerank | 1 | 0.2833 | 0.6000 | 0.6000 | 0.6000 |
| Vector+Rerank | 3 | 0.5833 | 0.4167 | 0.7750 | 0.5651 |
| Vector+Rerank | 5 | 0.7917 | 0.3400 | 0.7750 | 0.6754 |
| Vector+Rerank | 10 | 0.8167 | 0.1750 | 0.7750 | 0.6863 |
| KeywordOnly | 1 | 0.3750 | 0.8000 | 0.8000 | 0.8000 |
| KeywordOnly | 3 | 0.7417 | 0.5333 | 0.8917 | 0.7341 |
| KeywordOnly | 5 | 0.9333 | 0.4000 | 0.8917 | 0.8352 |
| KeywordOnly | 10 | 0.9583 | 0.2050 | 0.8917 | 0.8462 |
| Hybrid | 1 | 0.3833 | 0.8000 | 0.8000 | 0.8000 |
| Hybrid | 3 | 0.7167 | 0.5167 | 0.8750 | 0.7133 |
| Hybrid | 5 | 0.9500 | 0.4100 | 0.8750 | 0.8378 |
| Hybrid | 10 | 0.9750 | 0.2100 | 0.8750 | 0.8488 |
| Hybrid+Rerank | 1 | 0.3833 | 0.8000 | 0.8000 | 0.8000 |
| Hybrid+Rerank | 3 | 0.7250 | 0.5167 | 0.8750 | 0.7129 |
| Hybrid+Rerank | 5 | 0.9500 | 0.4100 | 0.8750 | 0.8343 |
| Hybrid+Rerank | 10 | 0.9750 | 0.2100 | 0.8750 | 0.8440 |

## Recommended Wording

Compact English wording:

> In a deterministic Docker-backed Milvus + BGE-Small recall ablation on the
> v3.1 real-agent workload, with semantic paging disabled to isolate retrieval
> behavior, Hybrid+Rerank improved Recall@5 over Vector+Rerank from `0.7917`
> to `0.9500`, a `+0.1583` absolute and `+20.00%` relative lift, with `0/100`
> benchmark run errors across five retrieval modes.

Compact Chinese wording:

> 在 deterministic recall ablation benchmark（v3.1 real-agent workload，
> Docker-backed Milvus/MinIO + BGE-Small，shared namespace candidate pool，
> 关闭 semantic paging 以隔离检索行为）中，Hybrid+Rerank 相对 Vector+Rerank
> 的 Recall@5 从 `0.7917` 提升到 `0.9500`，absolute lift 为 `+0.1583`，
> relative lift 为 `+20.00%`；五种检索模式共 `100` 次运行，错误数为 `0`。

## Boundaries

Do not rewrite this result as:

- LLM answer accuracy improved by `20.00%`.
- Online production recall improved by `20.00%`.
- End-to-end Agent quality improved by `20.00%`.
- Rerank alone improved vector retrieval in this run; `VectorOnly` and `Vector+Rerank` had identical aggregate metrics here.
- Hybrid retrieval improved latency. Hybrid modes had higher average latency than vector-only modes in this benchmark.
- A semantic paging result. The canonical `-003` run explicitly used `VORTEX_PAGING_ENABLED=false`.

The intermediate `-002` run used paging and is not canonical ablation evidence:
selected L2 recalls can trigger page faults and change L1 state across mode
runs. Use `-003` for retrieval ablation wording.

This benchmark supports deterministic retrieval-quality wording only under the
conditions above.

## Reproduction Command

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
