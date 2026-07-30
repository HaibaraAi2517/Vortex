# LongMemEval Converter Provenance Reconciliation - 2026-07-30

## Status

Accepted for the historical schema-v2 split manifest only. The historical manifest, partition
files, Cross-Encoder DEV reports, and their recorded SHA-256 values remain unchanged. This
reconciliation does not authorize a validation or reserve model run.

## Incident

The historical manifest
`379ca45a6437ae2f06b13448651adf4e0f259b375067bdda8aeb11ccc7abba98` records converter raw-file
SHA-256 `a27e285242f5371726aea3446680322afe8a1882cee21a270117b3d85825deac`. The repository converter
observed during reconciliation has raw-file SHA-256
`8a3a350549d01efe76ee4b04a5dd92db44a6a101bce27930e3b25f57f341ca83`.

The exact `a27e...deac` preimage was not committed, so its byte-level cause cannot be reconstructed
or honestly classified as only a line-ending change. Rewriting the historical manifest would also
rewrite the identity referenced by the DEV decision and provider records. Both shortcuts are
rejected.

## Reconciliation Evidence

The accepted structured record is:

`ops/datasets/governance/longmemeval-reranker-splits-v1-converter-reconciliation.json`

Record SHA-256:
`c1098f32e372772b4a3dc62ee226469bc3d55a769c2d8973d4cbfd243b4087b0`.

The replacement converter canonicalization removes an optional UTF-8 BOM and normalizes CRLF or
CR to LF without changing any other content. Its canonical text SHA-256 is
`2881b4ca9f082d9f81b63d68ab9a18b4a4a201689f6459b1e0d6d9d00daa66ff`.

The validator re-runs that exact canonical converter against the historical source and the exact
case-ID manifest for every partition. All regenerated datasets are byte-identical:

| Partition | Case-ID SHA-256 | Regenerated dataset SHA-256 |
| --- | --- | --- |
| dev | `0fc0f94636453b3d49176f7f78988850925f1a4a8058ddefb51533235865deba` | `df6f58a141b6b4b54e478c04f87d38bb4b56d9601d913f5dd7f6114fa7a6a5f6` |
| validation | `33ca41d2cb7f1af110578a0d3bff2db3ec2106569bf2ae9b35276449bc727c43` | `9c3a328cb023677c355acae073592eeb41ea0a2571cbfb3b74ee8634c2ea3834` |
| reserve | `f18b3a2dfe443598209eac0f56e8f13047933d965d1e89da3705f888de648cfa` | `8363b6af2c59e7514f4c4dd6ce7ebb012eb752bd843b9bb394c58e101f8d88bc` |

This establishes output equivalence for all governed retrieval partitions. It does not recreate
the missing historical script bytes.

## Enforcement

Raw converter hash drift still fails by default. Reconciliation is accepted only when the caller
explicitly supplies the record and all of these checks pass:

- record schema, type, and status are exact;
- historical manifest, source, and historical converter hashes match;
- replacement converter resolves inside the repository and its canonical hash matches;
- dev, validation, and reserve case-ID hashes, dataset hashes, and namespace bases match exactly;
- all three partitions are regenerated in a temporary directory and reproduce byte-identical
  dataset SHA-256 values;
- the record explicitly forbids manifest rewriting, partition mutation, validation model runs,
  and reserve model runs.

There is no generic skip flag. A missing record, a different converter, a changed partition, a
different output, or an expanded authorization boundary is a hard failure.

## Reproduction

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File ./ops/datasets/validate-longmemeval-splits.ps1 `
  -ManifestPath ./ops/datasets/generated/longmemeval-reranker-splits-v1/longmemeval-reranker-splits-manifest.json `
  -SourcePath E:/tmp/longmemeval/longmemeval_oracle.json `
  -ReconciliationPath ./ops/datasets/governance/longmemeval-reranker-splits-v1-converter-reconciliation.json
```

Expected provenance result:

`RECONCILED_CANONICAL_HASH_AND_OUTPUT_EQUIVALENCE`

Future split manifests should store repository-relative script paths and canonical text hashes
before they are used by a model run. This historical reconciliation must not be copied to a new
manifest or used to unseal validation or reserve.
