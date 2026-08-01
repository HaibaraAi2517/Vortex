# Vortex Runtime Recovery Benchmark Evidence - 2026-06-27

This note records the first defensible runtime recovery benchmark result.

## Benchmark Scope

- Command: `runtime-recovery-benchmark`
- Run date: 2026-06-27 Asia/Shanghai
- Report timestamp: UTC in filename, `20260626-172433`
- Total cases: 4
- Passed cases: 4
- Failed cases: 0
- Success rate: `1.0000`
- Average latency: `132.75 ms`

## Evidence Files

- JSON: `ops/eval-reports/20260627-runtime-recovery-benchmark-001/runtime-recovery-benchmark-20260626-172433.json`
- Markdown: `ops/eval-reports/20260627-runtime-recovery-benchmark-001/runtime-recovery-benchmark-20260626-172433.md`

The report filenames use UTC timestamps. The local run date was 2026-06-27
Asia/Shanghai.

## Covered Capabilities

- Task DAG checkpoint and recover
- Recovery after process-local task cache eviction
- Repeated recover idempotency
- Branch and merge state recovery
- Application Execution ID replay idempotency

## Excluded Capabilities

- Tool failure runtime recovery
- LLM timeout task-level resume
- Conversation state snapshot
- Tool execution state snapshot
- Full async memory extraction/summary/embedding/index pipeline recovery

## Result Summary

| Case | Capability | Passed |
| --- | --- | --- |
| runtime-recovery-001 | checkpoint-recover | true |
| runtime-recovery-002 | repeated-recover-idempotency | true |
| runtime-recovery-003 | branch-merge-recover | true |
| runtime-recovery-004 | execution-id-replay | true |

## Recommended Wording

Use this wording when a compact metric is needed:

> In the first deterministic runtime recovery benchmark covering Task DAG
> checkpoint/recover, cache-eviction recovery, repeated recover idempotency,
> branch/merge recovery, and Execution ID replay idempotency, Vortex passed
> 4/4 cases for a covered-case recovery success rate of 100%.

Chinese README/resume wording:

> 在首版 deterministic runtime recovery benchmark 中，覆盖 Task DAG
> checkpoint/recover、进程内状态清空后恢复、重复 recover 幂等、branch/merge
> 状态恢复和 Execution ID replay 幂等，4/4 cases 通过，covered-case recovery
> success rate 为 100%。

## Boundaries

Do not rewrite this result as:

- Complete Agent Runtime recovery success rate.
- Tool Failure recovery success rate.
- LLM Timeout task-level resume success rate.
- Conversation or Tool state snapshot coverage.

The current metric is only for the covered deterministic runtime recovery cases.
The next implementation step should extend runtime snapshot coverage to
Conversation and Tool state, then add failure/resume cases to this benchmark.

## Run Command

The first real run used an isolated Milvus collection because the default
`vortex_memory` collection is still dim=4 while the current BGE-Small config is
dim=512.

```powershell
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260627-runtime-recovery-benchmark-001'
$env:VORTEX_WAL_DIR='E:/tmp/vortex-runtime-recovery-benchmark-20260627-001/wal'
$env:MINIO_KEY_PREFIX='runtime-recovery-benchmark/20260627-001/'
$env:VORTEX_EXECUTION_ID_BACKEND='MEMORY'
$env:VORTEX_SCHEDULER_ENABLED='false'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_runtime_recovery_20260627_001'
java -jar .\vortex-app\target\vortex-app-0.1.0-eval-cli.jar runtime-recovery-benchmark
```
---

# Extended Runtime Recovery Benchmark Evidence - 2026-06-28

This follow-up run extends the first runtime recovery benchmark from Task DAG
and Execution ID coverage into runtime snapshot state for Conversation, Tool
failure, and LLM timeout retry state.

## Extended Benchmark Scope

- Command: `runtime-recovery-benchmark`
- Run date: 2026-06-28 Asia/Shanghai
- Report timestamp: UTC in filename, `20260628-104936`
- Total cases: 7
- Passed cases: 7
- Failed cases: 0
- Success rate: `1.0000`
- Average latency: `92.7143 ms`

## Extended Evidence Files

- JSON: `ops/eval-reports/20260628-runtime-recovery-benchmark-002/runtime-recovery-benchmark-20260628-104936.json`
- Markdown: `ops/eval-reports/20260628-runtime-recovery-benchmark-002/runtime-recovery-benchmark-20260628-104936.md`

The report filenames use UTC timestamps. The local run date was 2026-06-28
Asia/Shanghai.

## Extended Covered Capabilities

- Task DAG checkpoint and recover
- Recovery after process-local task cache eviction
- Repeated recover idempotency
- Branch and merge state recovery
- Application Execution ID replay idempotency
- Conversation state snapshot and recovery
- Tool failure runtime recovery
- LLM timeout task-level retry recovery

## Extended Excluded Capabilities

- Full async memory extraction/summary/embedding/index pipeline recovery

## Extended Result Summary

| Case | Capability | Passed |
| --- | --- | --- |
| runtime-recovery-001 | checkpoint-recover | true |
| runtime-recovery-002 | repeated-recover-idempotency | true |
| runtime-recovery-003 | branch-merge-recover | true |
| runtime-recovery-004 | execution-id-replay | true |
| runtime-recovery-005 | conversation-state-recover | true |
| runtime-recovery-006 | tool-failure-recover | true |
| runtime-recovery-007 | llm-timeout-retry-recover | true |

## Updated Recommended Wording

Use this wording when a compact metric is needed:

> In the extended deterministic runtime recovery benchmark covering Task DAG
> checkpoint/recover, cache-eviction recovery, repeated recover idempotency,
> branch/merge recovery, Execution ID replay idempotency, Conversation state
> recovery, Tool failure state recovery, and LLM timeout retry recovery, Vortex
> passed 7/7 cases for a covered-case recovery success rate of 100%.

Chinese README/resume wording:

> 在扩展版 deterministic runtime recovery benchmark 中，覆盖 Task DAG
> checkpoint/recover、进程内状态清空后恢复、重复 recover 幂等、branch/merge
> 状态恢复、Execution ID replay 幂等、Conversation 状态恢复、Tool Failure
> 状态恢复与 LLM Timeout retry 状态恢复，7/7 cases 通过，covered-case
> recovery success rate 为 100%。

## Updated Boundaries

Do not rewrite this result as complete production recovery coverage. The current
metric is still limited to the deterministic benchmark cases above. It now
covers Conversation, Tool failure, and LLM timeout retry state, but it still does
not cover the full async memory extraction/summary/embedding/index pipeline
recovery.

## Extended Run Command

The run used an isolated WAL directory and report output directory. An isolated
Milvus collection was also configured to avoid touching the existing default
collection.

```powershell
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260628-runtime-recovery-benchmark-002'
$env:VORTEX_WAL_DIR='E:/tmp/vortex-runtime-recovery-benchmark-20260628-002/wal'
$env:MINIO_KEY_PREFIX='runtime-recovery-benchmark/20260628-002/'
$env:VORTEX_EXECUTION_ID_BACKEND='MEMORY'
$env:VORTEX_SCHEDULER_ENABLED='false'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_runtime_recovery_20260628_002'
java -jar .\vortex-app\target\vortex-app-0.1.0-eval-cli.jar runtime-recovery-benchmark
```

---

# Matrix Runtime Recovery Benchmark Evidence - 2026-06-29

This run expands the deterministic runtime recovery benchmark from 7 cases to a
32-case fault-injection matrix with category-level success rates. The success
definition is encoded in the JSON/Markdown report: a case passes only when the
recovered Task/DAG/context/conversation/memory reference/tool/LLM state matches
the expected pre-crash or WAL-replayed state; completed Execution-ID guarded work
must not run twice, and running work must remain resumable.

## Matrix Benchmark Scope

- Command: `runtime-recovery-benchmark`
- Run date: 2026-06-29 Asia/Shanghai
- Report timestamp: UTC in filename, `20260629-135453`
- Random seed recorded in report: `20260629`
- Total cases: 32
- Passed cases: 32
- Failed cases: 0
- Overall success rate: `1.0000`
- Total measured case latency: `2201 ms`
- Average measured case latency: `68.7813 ms`

## Matrix Evidence Files

- JSON: `ops/eval-reports/20260629-runtime-recovery-benchmark-003/runtime-recovery-benchmark-20260629-135453.json`
- Markdown: `ops/eval-reports/20260629-runtime-recovery-benchmark-003/runtime-recovery-benchmark-20260629-135453.md`

The report filenames use UTC timestamps. The local run date was 2026-06-29
Asia/Shanghai.

## Category Success Rates

| Category | Cases | Passed | Failed | Success rate | Avg latency ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Service restart | 6 | 6 | 0 | 1.0000 | 121.6667 |
| Tool failure | 8 | 8 | 0 | 1.0000 | 46.1250 |
| LLM exception | 6 | 6 | 0 | 1.0000 | 49.3333 |
| State integrity | 7 | 7 | 0 | 1.0000 | 51.5714 |
| Concurrency | 5 | 5 | 0 | 1.0000 | 89.0000 |

## Covered Matrix Capabilities

- Task DAG checkpoint and recover
- Recovery after process-local task cache eviction
- Repeated recover idempotency
- Branch and merge state recovery
- Application Execution ID replay idempotency
- Conversation state snapshot and recovery
- Tool failure runtime recovery
- LLM timeout task-level retry recovery
- Referenced memory-fragment state recovery
- Tool running/completed/failed state recovery
- LLM running/completed/timeout/retry state recovery
- Deterministic multi-task interleaving recovery

## Matrix Boundaries

Do not rewrite this result as complete production recovery coverage. This is a
32-case deterministic benchmark over the current checkpoint/WAL/runtime-state
surface. It does not claim coverage for external process-manager crash-loop
orchestration, cross-binary historical snapshot schema migration, or the full
async memory extraction/summary/embedding/index pipeline recovery.

## Matrix Run Command

The run used isolated report, WAL, MinIO key prefix, in-memory Execution ID
storage, and an isolated Milvus collection.

```powershell
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260629-runtime-recovery-benchmark-003'
$env:VORTEX_WAL_DIR='E:/tmp/vortex-runtime-recovery-benchmark-20260629-003/wal'
$env:MINIO_KEY_PREFIX='runtime-recovery-benchmark/20260629-003/'
$env:VORTEX_EXECUTION_ID_BACKEND='MEMORY'
$env:VORTEX_SCHEDULER_ENABLED='false'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_runtime_recovery_20260629_003'
java -jar .\vortex-app\target\vortex-app-0.1.0-eval-cli.jar runtime-recovery-benchmark
```

## Test Commands

```powershell
mvn -pl vortex-app -am '-Dtest=RuntimeRecoveryBenchmarkRunnerTest,RuntimeRecoveryBenchmarkReportWriterTest,ExecutionIdServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl vortex-app -am -DskipTests package
```
