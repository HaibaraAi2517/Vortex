# Roadmap

The first users are individual developers running personal agents locally. The primary product
is durable memory through Python/MCP/REST. Task recovery remains an advanced integration, not a prerequisite.

## Implemented in the current working-tree alpha

- One-process Lucene storage: BM25/CJK bigrams, HNSW vectors and durable local state.
- Full-corpus keyword search, including natural-language hybrid queries.
- Explicit namespace/key facts with atomic revision CAS, history, expiry, retraction and deletion.
- Protection against replaced/deleted memories returning from L1, background writes or restart.
- Source-installable Python package and official MCP SDK stdio adapter.
- A real BGE smoke path with 300 distractors, forced restart and MCP calls.
- Trial ZIP packaging, pinned model downloads with SHA-256, and a Windows/Linux CI workflow.
- Full-haystack LongMemEval-S hypothesis generation, with gold/evidence labels excluded from generation.

These are implementation and verification capabilities. They do not establish real user adoption,
answer quality, production throughput or multi-user security.

## Before publishing the first personal-memory release

1. Review the source diff and packaged trial ZIP. Run the configured Windows/Linux CI and container build.
2. Run LongMemEval-S with a frozen answer model and the official answer judge. Publish dataset/model hashes,
   full-corpus size, answer accuracy by category, abstention/errors and latency. The current runner emits
   no accuracy until that scoring step is complete; the historical oracle result is not reused.
3. Set a new server release version, build immutable release artifacts and publish the Python wheel/Maven
   artifacts only after registry ownership and signing credentials are configured.
4. Offer the ZIP or single-container build to 5–10 consenting personal Agent developers. This is a target,
   not a claim that any users have already adopted Vortex. Do not collect their memory content.

## Pilot acceptance targets

- A new user can install, write, update and recall a fact from the guide without author intervention.
- Track time to first successful recall and where installation fails.
- At least three pilot users choose to continue using their integration across sessions for a week.
- Every wrong-memory report can be reproduced from a user-approved minimal example.
- Prioritize silent data loss, stale facts and failed onboarding over new features.

Pilot feedback should answer whether the stable-key contract is understandable and whether users need
automatic fact extraction. If extraction is added, evaluate mistaken merges, missed updates and provenance
before enabling it by default.

## Subsequent work, driven by pilot evidence

- Broader embedding choices and independent English/multilingual quality evaluation.
- Capacity/latency measurements for larger local corpora, including index growth and backup/restore.
- Export/import and a migration path for existing Milvus users; atomic facts on another backend only when needed.
- Separate the evaluation CLI from the application artifact and move large raw reports to versioned release assets
  while retaining compact manifests and reproducible evidence links.
- Real tenant identities and authorization only if a team-hosted product becomes a demonstrated need.
- Keep recovery benchmarks and legacy distributed integration tests, without turning them into the default onboarding.

The ONNX binaries and interview preparation guide are no longer tracked in the current tree; local copies
were preserved. Historical Git objects and published evidence are not rewritten as part of this change.
