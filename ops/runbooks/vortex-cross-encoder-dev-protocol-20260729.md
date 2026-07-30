# Vortex Cross-Encoder Dev Protocol - 2026-07-29

## 1. Status And Scope

This document freezes the P3 development protocol before any Cross-Encoder model is selected or
run. The provider-neutral scoring contract, Top40 reranker path, benchmark modes, diagnostics,
analysis, and decision gate are implemented. No model provider or default model is bundled, and
this work has not downloaded a model or run dev, validation, or reserve model evaluation.

The first permitted model evaluation is dev-only. Validation remains sealed until one exact
provider, model version, model artifact SHA-256, candidate-pool protocol, and decision rule have
been selected on dev and recorded. Reserve remains untouched.

## 2. Data Boundary

| Partition | Path | SHA-256 | Policy |
| --- | --- | --- | --- |
| dev | `ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-dev-120-case-isolated.json` | `df6f58a141b6b4b54e478c04f87d38bb4b56d9601d913f5dd7f6114fa7a6a5f6` | Model selection and dev decisions only |
| validation | `ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-validation-120-case-isolated.json` | `9c3a328cb023677c355acae073592eeb41ea0a2571cbfb3b74ee8634c2ea3834` | One final run after the complete configuration is frozen |
| reserve | `ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-reserve-99-case-isolated.json` | `8363b6af2c59e7514f4c4dd6ce7ebb012eb752bd843b9bb394c58e101f8d88bc` | Untouched positive-evidence reserve |

Split manifest:

- Path: `ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-splits-manifest.json`
- SHA-256: `379ca45a6437ae2f06b13448651adf4e0f259b375067bdda8aeb11ccc7abba98`
- Cases: 120 dev, 120 validation, and 99 reserve cases, one namespace per case, with no
  overlap with the 140 historically used IDs
- Retrieval eligibility: every partition case has at least one positive evidence fragment
- Abstention quarantine: 21 unused cases with no positive evidence are excluded from retrieval
  metrics and recorded in `abstention-quarantine-case-ids.json`

The initial `-001` dev launch on manifest v1 was rejected by dataset validation before any
provider score call because four dev abstention cases had empty `expectedFragments`. Manifest v2
fixes the governance defect rather than fabricating labels or weakening the runner invariant.

Do not inspect validation or reserve results, use their labels in prompts, or call a model with
their queries or fragment content during development.

## 3. Model Input Contract

`CrossEncoderScoringService` is the only provider-facing interface. An implementation receives:

```text
query: String
documents: List<String> in baseline-rank order
```

The service returns exactly one finite `double` score per document in the same order. It must also
return non-empty model name, version, and a 64-hex SHA-256 for the exact model artifact used.

The model input must not contain fragment IDs, expected fragment IDs, tags, importance, benchmark
labels, failure categories, answer labels, or any other supervision derived from the case. The
adapter may perform provider-required tokenization and batching, but it must preserve input and
output order.

Invalid score length, null/NaN/infinite scores, invalid metadata, or a missing scorer is a hard
failure. `CROSS_ENCODER` never falls back to `LINEAR_SCORE_FUSION` or the baseline order.

## 4. Candidate And Ranking Protocol

The primary decision comparison is:

```text
Vector+CrossEncoderReranker vs VectorOnly
```

The fixed protocol is:

- Primary metric cutoff: Top5
- Additional metric cutoffs: 1, 3, 5, and 10
- Candidate source: the same Vector baseline used by `VectorOnly`
- Candidate ordering before model scoring: baseline rank
- Candidate pool: first 40 baseline-ranked candidates, or all candidates when fewer than 40 exist
- Audit strategy value: `VECTOR_BASELINE_TOP_40`
- Cross-Encoder ranking: descending model score
- Equal model scores: preserve baseline order
- Model input: query plus document content only
- Generation: disabled; this is retrieval evaluation, not answer-generation evaluation

The dev benchmark modes are frozen to:

```text
VECTOR_ONLY,VECTOR_RERANK,VECTOR_CROSS_ENCODER
```

`VECTOR_RERANK` is retained as an audit control for the existing linear score fusion. Hybrid
Cross-Encoder is outside the primary gate and may only be added as a separately reported dev
experiment. It must not replace the Vector baseline pool in the primary decision.

## 5. Required Evidence

Every candidate run must preserve:

- dataset path and SHA-256;
- report path and SHA-256;
- split manifest and manifest checksum;
- model name, model version, and exact artifact SHA-256;
- `rerankerType=CROSS_ENCODER`;
- candidate strategy `VECTOR_BASELINE_TOP_40` and limit `40`;
- preselection/input/output counts, independent score distinct count, changed positions, TopK
  membership changes, and changed-order rate;
- average, P50, P95, and P99 mode latency;
- Cross-Encoder scoring P95 and P99 latency;
- Java/OS/architecture/CPU/max-heap snapshot plus explicit hardware and GPU descriptions;
- errors and case-isolation audit results.

`VORTEX_EVAL_HARDWARE_DESCRIPTION` and `VORTEX_EVAL_GPU_DESCRIPTION` are mandatory even when no GPU
is used. In that case, set the GPU description to an explicit value such as `none; CPU inference`.

## 6. Frozen Decision Gate

The primary comparison must pass every rule. A failure means the Cross-Encoder remains disabled.

| Rule | DEV | VALIDATION |
| --- | ---: | ---: |
| Paired Recall@5 delta | `>= +0.020` | `>= +0.015` |
| Recall 95% bootstrap CI lower bound | `> 0` | `> 0` |
| Paired NDCG delta | `>= +0.010` | `>= +0.010` |
| NDCG 95% bootstrap CI lower bound | `>= 0` | `>= 0` |
| Paired MRR delta | `>= -0.005` | `>= -0.005` |
| Cases with changed order | `>= 10%` | `>= 10%` |
| Added mode P95 latency | `<= 250 ms` | `<= 250 ms` |
| Added mode P99 latency | `<= 500 ms` | `<= 500 ms` |
| Errors | `0` | `0` |

Both phases also require 120 cases, the manifest-matched partition path and SHA-256, one consistent
model/version/hash, Cross-Encoder type, and the Vector Top40 pool. Validation additionally requires
the expected model, version, and SHA-256 to be passed explicitly to the gate.

Confidence intervals use 20,000 case-level paired bootstrap iterations with seed `20260729`.
Thresholds are fixed before model selection and must not be weakened after viewing dev results.

## 7. Verification Before A Model Run

These commands are safe because they use unit or synthetic data only:

```powershell
mvn -q -pl vortex-kernel,vortex-app -am `
  "-Dtest=CrossEncoderRerankerTest,RecallOrchestratorTest,RecallBenchmarkRunnerTest,RecallBenchmarkReportWriterTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/test-recall-decision-analyzer.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/test-reranker-decision-gate.ps1
```

The first real dev run is blocked until a concrete `CrossEncoderScoringService` provider and exact
model artifact have been reviewed. The repository intentionally has no default scorer, so running
the command below before installing a provider must fail instead of silently producing a baseline.

## 8. Dev Run Template

After the provider and exact model artifact are frozen, record their provider-specific settings in
the evidence directory and run with an isolated collection, object prefix, and WAL directory:

```powershell
mvn -q -pl vortex-app -am -DskipTests package
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/<dev-run-id>'
$env:VORTEX_EVAL_DATASET_LOCATION='file:E:/1projects/claude/Vortex/ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-dev-120-case-isolated.json'
$env:VORTEX_EVAL_RECALL_ABLATION_MODES='VECTOR_ONLY,VECTOR_RERANK,VECTOR_CROSS_ENCODER'
$env:VORTEX_EVAL_RECALL_TOP_K='5'
$env:VORTEX_EVAL_RECALL_TOKEN_BUDGET='4096'
$env:VORTEX_RECALL_CROSS_ENCODER_CANDIDATE_POOL_LIMIT='40'
$env:VORTEX_EVAL_HARDWARE_DESCRIPTION='<cpu, memory, and host description>'
$env:VORTEX_EVAL_GPU_DESCRIPTION='<gpu model or none; CPU inference>'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='<isolated-dev-collection>'
$env:MINIO_KEY_PREFIX='<isolated-dev-prefix>/'
$env:VORTEX_WAL_DIR='E:/tmp/<isolated-dev-run>/wal'
$env:BGE_MODEL_PATH='models/bge-small-zh'
$env:VORTEX_GENERATION_ENABLED='false'
$env:VORTEX_SCHEDULER_ENABLED='false'
$env:VORTEX_PAGING_ENABLED='false'
java -jar ./vortex-app/target/vortex-app-0.1.0-SNAPSHOT-eval-cli.jar recall-benchmark
```

Provider-specific model location, tokenizer, batching, device, thread count, and precision must be
declared next to this template once a provider is chosen. Never use an unpinned remote model alias
as the model version or infer the artifact hash from its name.

Analyze the generated report and enforce case isolation:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/analyze-recall-decision.ps1 `
  -ReportPath 'ops/eval-reports/<dev-run-id>/<recall-report>.json' `
  -DatasetPath 'ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-dev-120-case-isolated.json' `
  -OutputDirectory 'ops/eval-reports/<dev-run-id>' `
  -BootstrapIterations 20000 `
  -RandomSeed 20260729 `
  -RequireCaseIsolatedReturns

powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/validate-reranker-decision.ps1 `
  -AnalysisPath 'ops/eval-reports/<dev-run-id>/recall-decision-analysis.json' `
  -SplitManifestPath 'ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-splits-manifest.json' `
  -Phase DEV
```

## 9. Validation Lock

Before unsealing validation, create an immutable decision record containing the selected provider,
adapter commit, model name/version/SHA-256, provider settings, modes, Vector Top40 protocol, all
thresholds above, hardware target, and the accepted dev report/analysis SHA-256 values.

The validation gate must be called with the exact locked metadata:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/validate-reranker-decision.ps1 `
  -AnalysisPath 'ops/eval-reports/<validation-run-id>/recall-decision-analysis.json' `
  -SplitManifestPath 'ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-splits-manifest.json' `
  -Phase VALIDATION `
  -ExpectedModel '<locked-model>' `
  -ExpectedModelVersion '<locked-version>' `
  -ExpectedModelSha256 '<locked-64-hex-sha256>'
```

Do not run this command or the validation benchmark during provider exploration. Reserve has no
approved model-run procedure in this phase.

## 10. Current Next Action

The pinned MiniLM-L6 AVX2 INT8 CPU candidate completed the dev protocol and failed five rules:
Recall delta, Recall CI lower bound, NDCG CI lower bound, added P95 latency, and added P99 latency.
VectorOnly remains the default and validation is not authorized. See
`vortex-cross-encoder-dev-decision-20260729.md`.

A future experiment must select a separately pinned model/artifact and use a new isolated dev run.
Do not weaken thresholds, reinterpret this rejected run as a validation candidate, or call the
validation/reserve partitions.
