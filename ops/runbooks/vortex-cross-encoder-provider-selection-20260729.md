# Vortex Cross-Encoder Provider Selection - 2026-07-29

## Status

Evaluated on the dev partition and rejected by the frozen gate. Validation and reserve remain
sealed, and VectorOnly remains the selected default. The complete result and five failed rules are
recorded in `vortex-cross-encoder-dev-decision-20260729.md`.

## Provider And Artifact

- Provider: local ONNX Runtime Java `1.18.0`, `CPUExecutionProvider`
- Model repository: `cross-encoder/ms-marco-MiniLM-L6-v2`
- Repository revision: `c5ee24cb16019beea0893ab7796b1df96625c6b8`
- Source artifact: `onnx/model_quint8_avx2.onnx`
- Local artifact: `models/ms-marco-MiniLM-L6-v2-c5ee24cb/model.onnx`
- Model bytes: `23200716`
- Model SHA-256: `c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9`
- Tokenizer source/local artifact: `tokenizer.json`
- Tokenizer bytes: `711396`
- Tokenizer SHA-256: `d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66`
- Precision: unsigned INT8 AVX2 model artifact
- Device: CPU; the installed NVIDIA GPU is not used by this provider

The repository and revision are pinned rather than resolved through a floating remote alias. The
adapter recomputes both local hashes during bean initialization and fails startup on mismatch.
The upstream `config.json` contains a stale `_name_or_path` value referring to an L12 model, while
the same file declares `num_hidden_layers=6`. Identity is therefore based on repository, immutable
revision, exact filename, byte length, and SHA-256 rather than that descriptive field.

## Frozen Provider Settings

| Setting | Value |
| --- | --- |
| Pair input | query as sequence A, document content as sequence B |
| Maximum sequence length | 512 |
| Truncation | tokenizer longest-first |
| Padding | dynamic per sub-batch |
| Batch size | 16 |
| Inter-op threads | 1 |
| Intra-op threads | 8 |
| Execution mode | sequential |
| Graph optimization | all |
| Provider | CPU |
| Whole-request timeout | 5000 ms |
| Candidate source | Vector baseline |
| Candidate pool | baseline-ranked Top40 |
| Ties | preserve baseline order |

The adapter is conditional on `VORTEX_RECALL_CROSS_ENCODER_ENABLED=true`; the application default
remains disabled. Missing files, hash drift, invalid metadata, schema mismatch, timeout, score-count
mismatch, and non-finite scores are hard failures with no baseline or linear-fusion fallback.

## Contract Verification

`OnnxCrossEncoderScoringServiceTest` covers batch ordering, metadata, count validation, finite-score
validation, timeout cancellation, error propagation, missing artifact failure, invalid metadata,
and an opt-in real-artifact smoke test. The smoke test loaded the pinned artifact, reproduced its
SHA-256, emitted two finite logits in input order, and scored the relevant Paris passage above an
unrelated passage.

## Dev Boundary

- Dataset: `longmemeval-reranker-dev-120-case-isolated.json`
- Dataset SHA-256: `df6f58a141b6b4b54e478c04f87d38bb4b56d9601d913f5dd7f6114fa7a6a5f6`
- Split manifest SHA-256: `379ca45a6437ae2f06b13448651adf4e0f259b375067bdda8aeb11ccc7abba98`
- Modes: `VECTOR_ONLY,VECTOR_RERANK,VECTOR_CROSS_ENCODER`
- Generation: disabled
- Validation: sealed until DEV passes every frozen rule and a separate immutable lock record is
  created with an adapter commit and accepted evidence hashes
- Reserve: untouched

The `20260729-cross-encoder-dev-minilm-l6-int8-001` launch used the superseded manifest v1 and
was rejected before the first case or model score because four abstention cases had no expected
evidence fragments. It is startup-failure evidence only. The first eligible model run uses
manifest v2 and a new isolated `-002` run ID.
