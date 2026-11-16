# Roadmap

The first users are individual developers running personal agents locally. The primary product
is durable memory through Python/MCP/REST. Task recovery remains an advanced integration, not a prerequisite.

## Implemented in the current working-tree alpha

- One-process Lucene storage: BM25/CJK bigrams, HNSW vectors and durable local state.
- Full-corpus keyword search, including natural-language hybrid queries.
- Explicit namespace/key facts with atomic revision CAS, history, expiry, retraction and deletion.
- Protection against replaced/deleted memories returning from L1, background writes or restart.
- Source-installable Python package and official MCP SDK stdio adapter.
- Python `MemorySession` and Spring AI `VortexMemoryAdvisor` automatic retrieval, bounded fact extraction and revision-aware updates; Codex/MCP shares the same store.
- A real BGE smoke path with 300 distractors, forced restart and MCP calls.
- Trial ZIP packaging, pinned model downloads with SHA-256, and a Windows/Linux CI workflow.
- Full-haystack LongMemEval-S hypothesis generation, with gold/evidence labels excluded from generation.
- A 100-case synthetic Chinese cross-session dataset and three-arm quality runner, with full request/response evidence and separate execution failures.

These are implementation and verification capabilities. They do not establish real user adoption,
answer quality, production throughput or multi-user security.

## Before publishing the first personal-memory release

1. Keep clean-checkout Java, Windows/Linux local-memory, packaging and container checks passing. Pinned test models must be downloaded before Maven verification.
2. Address the development-set failures documented in the [Chinese memory baseline](docs/memory-quality-baseline-20261117.md): missing extraction context for references, quoted or third-party facts becoming user facts, and correct recalled facts losing their field/subject meaning before answering. Keep the original baseline; compare new runs without changing the frozen data or silently discarding failed calls. The confirmation attempt was interrupted by provider HTTP 402; a clean full comparison still requires available model API access.
3. Run LongMemEval-S with a frozen answer model and the official answer judge. Publish dataset/model hashes,
   full-corpus size, answer accuracy by category, abstention/errors and latency. The current runner emits
   no accuracy until that scoring step is complete; the historical oracle result is not reused.
4. Set a new server release version, build immutable release artifacts and publish the Python wheel/Maven
   artifacts only after registry ownership and signing credentials are configured.
5. Offer the ZIP or single-container build to 5–10 consenting personal Agent developers. This is a target,
   not a claim that any users have already adopted Vortex. Do not collect their memory content.

## Pilot acceptance targets

- A new user can install, write, update and recall a fact from the guide without author intervention.
- Track time to first successful recall and where installation fails.
- At least three pilot users choose to continue using their integration across sessions for a week.
- Every wrong-memory report can be reproduced from a user-approved minimal example.
- Prioritize silent data loss, stale facts and failed onboarding over new features.

Pilot feedback should answer whether the stable-key contract and automatic update behavior are understandable.
Evaluate mistaken attribution, missed updates, provenance and user-visible commit results before expanding
automatic extraction beyond its configured fields. The synthetic same-model baseline is not independent human validation.

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
