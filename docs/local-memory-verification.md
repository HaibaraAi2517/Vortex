# Local memory alpha verification

Verified locally on **2026-11-16 (Asia/Shanghai)** on Windows, Java 21.0.10, Maven 3.9.9,
Python 3.13.7 and official MCP Python SDK 1.30.0. This is a working-tree alpha on
`codex/local-memory-adoption`, not the published v0.2.0 release.

## Results

| Check | Result |
| --- | --- |
| `mvn -B verify` | 643 tests, 0 failures/errors/skips: common 38, storage 43, kernel 384, langchain4j 6, app 172 |
| Python client, MCP protocol and full-haystack runner tests | 11 passed |
| Real BGE packaged-service smoke | 8 checks passed with 300 distractor memories; 72.40 seconds for the complete script, not per-request latency |
| Extracted ZIP smoke in its own working directory | 8 checks passed; 16.72 seconds, without the distractor load |
| Browser workbench with real API/BGE | Demonstration, Chinese recall, revision history, CRUD, conflict handling, reload, deleted-key recreation and mobile layout passed |
| Fresh pinned model download (previous run, 2026-11-14) | BGE ONNX and tokenizer downloaded into a new directory and passed SHA-256 validation; this run reused those files |
| Compose configuration (previous run, 2026-11-14) | Configuration validation passed; Docker was not exercised in this run |

The real-model checks cover startup without external storage, Chinese keyword/hybrid recall, empty keyword
results for unrelated text, replaced L1 revisions excluded from all retrieval modes, HTTP 409 on stale updates,
durability after a forced process kill/restart, and official MCP client calls into the running service.
Additional Java tests exercise expiry, namespace authorization, concurrent CAS, history/deletion,
late writes after restart, complete Milvus iterator traversal, and long unpunctuated Unicode chunking.

New catalog tests cover key-ordered cursor pagination, namespace filtering before the limit, expired/retracted/deleted
visibility, and an actual schema-1 index upgraded and reopened with its revisions, history and search preserved.
The public HTML/CSS/JS shell was tested with security enabled; catalog reads still require the token and an allowed namespace.

Browser verification used Ego Lite against the packaged service at loopback, with a separate
`data/vortex-workbench` directory. The demo really wrote and recalled Beijing before replacing it with Shanghai.
The resulting keyword recall contained Shanghai and excluded Beijing. A separate tea fact exercised save,
an external concurrent update returning 409 while retaining the unsaved draft, retraction, deletion,
reading the deletion tombstone, and recreation at the correct revision. Test tea contents were deleted afterwards.
HTML-like memory content was displayed literally without creating an image or executing its event handler.
The 390px mobile view had no horizontal overflow. Namespace deep links and generated Python/MCP examples were checked.
The browser is a retrieval/management interface; these checks do not measure generated-answer quality.

The new facts contract is explicit: the caller/Agent supplies a stable key. Appending arbitrary text does not
infer contradictions. History is an audit of revisions; only the current fact endpoint and ordinary recall
represent present state. This is not an automatic semantic memory-consolidation claim.

## Reviewable artifacts

Generated locally, ignored by Git:

- `target/vortex-local-alpha.zip` — 307.4 MiB, containing the service, BGE model, Python wheel and launchers.
- `target/vortex-local-alpha.zip.sha256` — archive checksum.
- `target/local-memory-smoke.json` — real-model smoke evidence tied to the exact JAR hash.
- `target/local-bundle-smoke.json` — independent extracted-archive verification.
- `target/workbench-maven.log` — full reactor test/build output.
- `target/workbench-browser-verification.json` — observed browser checks tied to this JAR.
- `target/workbench-desktop.png`, `target/workbench-mobile.png`, `target/workbench-history.png` — browser captures.
- `sdk/python/dist/vortex_agent_memory-0.3.0a1-py3-none-any.whl` — installable client and optional MCP adapter.

JAR SHA-256:

```text
686ada3e584a5b6ad9374884dbd5cd7de10b3bfd2b3b6482f47a02b982677623
```

ZIP SHA-256:

```text
fe725cf73e45e3a967cbe1dd8ee7f7665a21a78b818ea2afc65c50f2d3e8c803
```

The packager rejects a missing/failed smoke report or a report for a different JAR. Rebuilding may change
hashes; regenerate evidence and the archive together. The server POM remains at its existing base version,
while the ZIP is explicitly labeled a working-tree alpha; set the new server version before a public release.

The interactive workbench from this run uses `http://127.0.0.1:8080/` and `data/vortex-workbench`.
After stopping that service, restart it with the same data directory from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\ops\run-local.ps1 -SkipBuild -DataDir .\data\vortex-workbench
```

The normal launcher defaults to `data/vortex`, which is a different directory. Do not start two processes
against the same local index. The retained browser demo is in namespace `demo-2026-11-16-867d6c29`;
the rest of the automated checks used separate temporary directories and ports.

## Not verified or published

- The image build and live Milvus/MinIO/Redis integration tests were not run here. The previous run
  found Docker Desktop's daemon unavailable and checked only Compose syntax.
- Windows/Linux CI configuration was added; GitHub-hosted runs have not been executed in this session.
- The LongMemEval-S generator was tested for full-haystack handling and absence of gold/evidence leakage,
  but no real answer-model run or official judge run was performed. Answer accuracy remains unmeasured.
- No GitHub release, Maven Central or PyPI publication, external user recruitment, or adoption result is claimed.

The ONNX binaries and interview guide were removed from the Git index while keeping their local copies.
Existing Git history and large historical evaluation reports were retained; a separate reviewed history/evidence
migration would be needed to shrink old repository objects without breaking provenance links.
