# P1 Reliability Regression and Evaluation

Date: 2026-09-05

## Scope

Fix the seven P1 findings from the repository review without changing recall
weights, ranking defaults, model artifacts, or the sealed validation/reserve
datasets. The three P2 findings (update deduplication, empty context WAL values,
and LLM-evaluation feedback IDs) are outside this change.

## Changes and Regression Coverage

| Finding | Change | Regression |
| --- | --- | --- |
| Checkpoint races with acknowledged mutations | Reuse the per-task checkpoint lock across validation, WAL append, state application, dirty tracking, lifecycle transitions and facade recovery | Paused WAL append for DAG, conversation and branch writes; same-task checkpoint waits while another task checkpoints |
| Branch graph changes missing from DELTA | Mark fork nodes/edges and merge nodes dirty | FULL -> branch/merge -> DELTA -> recover preserves nodes, edges and cursor |
| Merge WAL changes node identity | Persist mergeNodeId and reuse its timestamp/identity on replay | Subsequent edge referencing the merge node survives WAL replay |
| Milvus expression injection | Encode namespace/ID as a JSON string literal; preserve stored namespace; reject foreign candidates and enrichment | Quotes, backslashes and control characters; foreign L2/keyword/L3 candidates; real Milvus literal search and precise deletion |
| Deleted memories resurrect from background writes | Serialize delete against in-flight persistence; persist a deletion generation; reject stale queued/DLQ tasks | Queued and in-flight writes, restart, ID reuse, failed delete and retry |
| MinIO checkpoint delete swallows failures | Reuse strict object deletion, ignoring only explicit missing-object errors | Inject delete failure separately for binary, JSON and metadata objects |
| DAG lock-order inversion | Use the edge monitor for adjacency rebuild and avoid the unlocked dirty fast path | Deterministic reader/writer lock interleaving completes without deadlock |

## Verification

- All five modules: 620 unit tests, zero failures/errors/skips.
- Isolated real Milvus 2.4.4 and MinIO integration suite: 14 tests, zero failures/errors/skips.
- FullLifecycleIT remains excluded by the existing default integration profile.
- Unit log: ../../target/p1-verify.log
- Integration log: ../../target/p1-554b173689/integration.log
- The first MinIO regression failed against the old implementation because no
  exception was propagated, then passed after strict deletion was applied.

The integration stack used fresh containers, a unique Compose project, random
localhost ports, per-test collections/prefixes, and isolated local WAL/DLQ
directories. Only resources belonging to that run were removed.

## Operational Compatibility

Deletion generations are stored next to the configured processed-key file in
a directory named <processed-key-file>.fragments. Keep this directory with the
WAL/DLQ data across restarts. It is not the bounded processed-key cache: do not
trim it while stale writes or old DLQ records may still exist. Explicit storage
of a deleted ID reopens it at the newer generation; old queued writes remain
invalid.

Older DLQ entries without a generation are read as generation zero. A downgrade
to an older consumer must account for the new generation field and must not
replay old writes after dropping deletion fences. These locks and local fences
provide single-instance coordination, not a distributed multi-writer protocol.

Older MERGE_BRANCH WAL records lack the original merge node ID. Their fallback
identity is now deterministic, but the original ID cannot be reconstructed
from that record alone. This change does not repair previously lost nodes,
already-truncated WAL, or corrupted historical checkpoints.

## Evaluation Protocol

Baseline source: d7d260de3e7671f214f092e48d9179bfcbcfc613, exported to target/p1-baseline.
Candidate: the current uncommitted P1 patch.

- Local BGE-Small, 512 dimensions; real Milvus and MinIO.
- Public DEV partition only: 120 cases.
- VectorOnly and guarded Hybrid+RRF; TopK=5, token budget=4096.
- Read-only recall; generation, cloud embedding, paging, scheduler and feedback disabled.
- Separate collection, object prefix, local persistence paths and report directory per variant/benchmark.
- No changes to the frozen ranking weights or candidate selection rules.
- Baseline then candidate, on the same machine; a single pass is a regression
  measurement, not a statistically established performance improvement.

The repository CLI currently configures a non-web application while its security
configuration requires HttpSecurity. Both benchmark variants use the same
workaround: servlet application mode, 127.0.0.1, ephemeral port. The temporary
server closes at the end of each command. This unrelated CLI issue is not fixed.

Artifact SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| Baseline eval JAR | 6c4f0309483e09f82aafcecb8b569984ba540f9e5688d1160f9377e1f207513e |
| P1 eval JAR | e7441020636256b638d4b4308799f5e4a8dd1e62f0cfab4a6fceb7124c974982 |
| BGE model | 69a0b846f4f116b5e6aabf9546ea6754d02264f3211a13a1bd69b31b8040749a |
| Tokenizer | 48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26 |
| DEV dataset | df6f58a141b6b4b54e478c04f87d38bb4b56d9601d913f5dd7f6114fa7a6a5f6 |

## Paired Results

Baseline and candidate completed on the same host. Both recall modes ran
120/120 cases with zero errors and `ChangedCaseCount=0`.

| Mode | Variant | Recall@5 | MRR | NDCG | Avg ms | P95 ms | P99 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| VectorOnly | baseline | 0.818611 | 0.710417 | 0.692846 | 240.81 | 336 | 391 |
| VectorOnly | fixed | 0.818611 | 0.710417 | 0.692846 | 231.38 | 320 | 384 |
| Hybrid+RRF | baseline | 0.818611 | 0.711111 | 0.693200 | 249.55 | 328 | 373 |
| Hybrid+RRF | fixed | 0.818611 | 0.711111 | 0.693200 | 233.30 | 305 | 353 |

Recall quality and returned ordering are unchanged. These single-pass latency
differences are observational only. Admission remained 100% successful at all
tested parallelism levels; fixed throughput was 5171/6459/6704/9281 ops/s at
1/2/4/8 threads versus 3361/3721/4857/8576. Runtime recovery stayed 32/32,
average 106.59 ms versus 108.84 ms.

The async pipeline retained 24/24 success and persistence success rate 1.0,
but main-path latency increased: average `434.32 ms`, P95 `578.76 ms`, P99
`615.79 ms`, versus baseline `366.38/476.33/489.31 ms`; readiness average was
`1032.44 ms` versus `959.63 ms`. No ranking or concurrency tuning is promoted
from this evidence. The guarded `HYBRID + RRF` default remains unchanged; the
persistence/delete coordination path needs repeated profiling before further
optimization.

Reports are under `target/p1-d06ddb876b/` in the baseline/fixed benchmark folders.
