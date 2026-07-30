# LongMemEval Reranker Split Governance - 2026-07-29

## 1. Status

P3 data governance is complete. This work did not run a model benchmark and did not use
validation cases for model selection or threshold tuning.

The LongMemEval oracle source has 500 unique cases. A structured audit of historical datasets,
reports, fixtures, and application/test resources found 140 previously used case IDs across 15
JSON sources. Of the remaining 360 cases, 339 contain at least one positive evidence fragment and
21 are abstention cases with no positive retrieval label. Manifest schema v2 assigns only eligible
cases to deterministic, mutually exclusive, case-isolated retrieval partitions:

| Partition | Cases | Intended use |
| --- | ---: | --- |
| dev | 120 | Model selection and decision-threshold development |
| validation | 120 | Sealed final validation after the model and thresholds are frozen |
| reserve | 99 | Untouched positive-evidence reserve |
| abstention quarantine | 21 | Excluded from positive-evidence Recall; no approved model run |

Manifest v1 assigned all 360 unused IDs directly to three 120-case partitions. The first dev CLI
launch exposed four empty `expectedFragments` cases and failed before any model score call.
Schema v2 quarantines all 21 abstention cases instead of fabricating labels, skipping cases, or
weakening the 120-case decision gate.

## 2. Source And Audit

- Source: `E:/tmp/longmemeval/longmemeval_oracle.json`
- Source SHA-256:
  `821a2034d219ab45846873dd14c14f12cfe7776e73527a483f9dac095d38620c`
- Source cases: 500
- Historical used-case union: 140
- Matched historical JSON sources: 15
- Used-case manifest SHA-256:
  `61244ed04d6a45d213457cbde3e725dc22183e9cf8a950194eb77131082fa461`

The audit recursively parses JSON and extracts only `caseId` and `question_id` values that
intersect the 500 source IDs. It scans:

- `ops/datasets`
- `ops/eval-reports`
- `ops/eval-fixtures`
- `vortex-app/src/main/resources`
- `vortex-app/src/test/resources`

The output directory is explicitly excluded so regeneration cannot classify its own partitions
as historical usage. Validation reruns the same audit and rejects source-path, matched-count,
case-union, or matched-file SHA-256 drift.

## 3. Deterministic Allocation

Generation parameters:

- Seed: `vortex-longmemeval-reranker-v1-20260729`
- Hash: SHA-256 over UTF-8 `seed|case|caseId`
- Retrieval eligibility: at least one `has_answer` / evidence flag in the source record
- Split order: `dev`, `validation`, `reserve`
- Quota algorithm: sequential proportional largest-remainder allocation for 120-case dev and
  validation, with SHA-256 tie-breaking; all remaining eligible cases go to reserve
- Abstention policy: deterministic quarantine outside retrieval partitions
- Namespace: `longmemeval-reranker-v1-<split>-<caseId>`
- Namespace policy: one unique namespace per case

| Category | Available | dev | validation | reserve |
| --- | ---: | ---: | ---: | ---: |
| knowledge-update | 52 | 18 | 19 | 15 |
| multi-session | 105 | 37 | 37 | 31 |
| single-session-assistant | 36 | 13 | 13 | 10 |
| single-session-preference | 10 | 3 | 4 | 3 |
| single-session-user | 44 | 16 | 15 | 13 |
| temporal-reasoning | 92 | 33 | 32 | 27 |
| Total retrieval-eligible | 339 | 120 | 120 | 99 |

Repeated generation against the formal source produced the same manifest SHA-256:
`379ca45a6437ae2f06b13448651adf4e0f259b375067bdda8aeb11ccc7abba98`.

## 4. Artifacts

Output directory:

`ops/datasets/generated/longmemeval-reranker-splits-v1/`

| Artifact | SHA-256 |
| --- | --- |
| `dev-case-ids.json` | `0fc0f94636453b3d49176f7f78988850925f1a4a8058ddefb51533235865deba` |
| `longmemeval-reranker-dev-120-case-isolated.json` | `df6f58a141b6b4b54e478c04f87d38bb4b56d9601d913f5dd7f6114fa7a6a5f6` |
| `validation-case-ids.json` | `33ca41d2cb7f1af110578a0d3bff2db3ec2106569bf2ae9b35276449bc727c43` |
| `longmemeval-reranker-validation-120-case-isolated.json` | `9c3a328cb023677c355acae073592eeb41ea0a2571cbfb3b74ee8634c2ea3834` |
| `reserve-case-ids.json` | `f18b3a2dfe443598209eac0f56e8f13047933d965d1e89da3705f888de648cfa` |
| `longmemeval-reranker-reserve-99-case-isolated.json` | `8363b6af2c59e7514f4c4dd6ce7ebb012eb752bd843b9bb394c58e101f8d88bc` |
| `abstention-quarantine-case-ids.json` | `b83dad6bb412fbfeda447d53766d28444ac3da6014a07ff168f2794478083164` |
| `used-case-ids.json` | `61244ed04d6a45d213457cbde3e725dc22183e9cf8a950194eb77131082fa461` |
| `longmemeval-reranker-splits-manifest.json` | `379ca45a6437ae2f06b13448651adf4e0f259b375067bdda8aeb11ccc7abba98` |

Generated external dataset conversions remain ignored by Git. Promote them only through an
explicit review and force-add; do not use `git add .`.

## 5. Gates And Reproduction

Implemented scripts:

- `ops/datasets/convert-longmemeval.ps1`: exact include-list conversion with stable ordering
- `ops/datasets/prepare-longmemeval-reranker-splits.ps1`: audit, allocation, conversion, hashes
- `ops/datasets/validate-longmemeval-splits.ps1`: independent governance validation
- `ops/datasets/test-longmemeval-reranker-splits.ps1`: synthetic regression

Synthetic regression result:

| Gate | Result |
| --- | --- |
| Valid path | PASS |
| Deterministic regeneration | PASS |
| Used/dev/validation/reserve overlap rejection | PASS |
| Abstention quarantine | PASS |
| Empty retrieval evidence rejection | PASS |
| Authorized dev evidence accepted after run | PASS |
| Validation/reserve/quarantine usage rejection | PASS |
| Namespace isolation rejection | PASS |
| Output SHA-256 drift rejection | PASS |

Formal validation result:

| Gate | Result |
| --- | --- |
| Complete 500-case source coverage | PASS |
| Pairwise partition isolation | PASS |
| 120/120/99 unique retrieval namespaces | PASS |
| 21 abstention cases quarantined | PASS |
| Positive evidence required for every retrieval case | PASS |
| Historical audit replay | PASS |
| Post-dev historical audit, 2 authorized dev sources | PASS |
| Source, manifest, ID-list, dataset, and generator hash checks | PASS |

Reproduce:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/datasets/test-longmemeval-reranker-splits.ps1

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/datasets/prepare-longmemeval-reranker-splits.ps1 -SourcePath E:/tmp/longmemeval/longmemeval_oracle.json

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/datasets/validate-longmemeval-splits.ps1 -ManifestPath ./ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-splits-manifest.json -SourcePath E:/tmp/longmemeval/longmemeval_oracle.json

## 6. Operational Boundary

Do not run the validation partition until the Cross-Encoder model, candidate-pool protocol,
decision thresholds, latency budget, and bootstrap-CI acceptance criteria are frozen in writing.
The immediate next development input is the dev partition only. The old formal 120-case recall
benchmark remains frozen and must not be rerun for tuning.
