# Vortex Commit Plan - 2026-06-30

This plan groups the current dirty worktree into reviewable commit/PR units. It
is intentionally conservative: do not stage unrelated deleted/untracked files
unless the user explicitly approves them.

## Do Not Stage Automatically

- `README.md` deletion. It is currently deleted in the worktree; do not restore
  or commit the deletion unless explicitly requested.
- `readme-history/`.
- `简历建议5.md`.
- User/context docs with Chinese names unless the user asks to include them:
  - `ops/开发计划.md`
  - `ops/runbooks/上一次对话.txt`
  - `ops/runbooks/对外目标.md`
  - `ops/runbooks/目标.md`
  - `ops/runbooks/未完成内容.md`

## Commit 1: Runtime Recovery Matrix

Suggested message:

```text
Add deterministic runtime recovery matrix benchmark
```

Primary files:

- `vortex-app/pom.xml` if this commit owns Redis dependency for execution-id support
- `docker-compose.yml` if this commit owns the Redis service
- `vortex-app/src/main/resources/application.yml` runtime execution-id config only
- `vortex-app/src/main/java/com/vortex/app/runtime/`
- `vortex-app/src/main/java/com/vortex/app/controller/TaskController.java`
- `vortex-app/src/main/java/com/vortex/app/controller/TaskExceptionHandler.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkExecutionService.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkReportWriter.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryBenchmarkRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RuntimeRecoveryTaskCacheEvictor.java`
- `vortex-common/src/main/java/com/vortex/common/model/ActionLogEntry.java`
- `vortex-common/src/main/java/com/vortex/common/model/TaskState.java`
- `vortex-common/src/main/java/com/vortex/common/model/ConversationMessage.java`
- `vortex-common/src/main/java/com/vortex/common/model/ConversationState.java`
- `vortex-common/src/main/java/com/vortex/common/model/ToolExecutionState.java`
- `vortex-common/src/main/java/com/vortex/common/model/ToolExecutionStatus.java`
- `vortex-common/src/main/java/com/vortex/common/model/LlmCallState.java`
- `vortex-common/src/main/java/com/vortex/common/model/LlmCallStatus.java`
- `vortex-common/src/main/java/com/vortex/common/serialization/KryoSerializer.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/CheckpointDelta.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/DirtySetTracker.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/IncrementalCheckpointManager.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/RecoveryEngine.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/SnapshotService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/RuntimeMutationService.java`
- Runtime/snapshot/controller tests touched by those files
- `ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md`

Notes:

- `application.yml`, `docker-compose.yml`, and `vortex-app/pom.xml` overlap with
  async/benchmark work. If staging interactively is impractical, put Redis and
  execution-id config in this commit and memory-pipeline/eval config in later
  commits.

## Commit 2: Async Memory Pipeline And Main-Path Latency Benchmark

Suggested message:

```text
Move memory ingest pipeline off request path
```

Primary files:

- `vortex-app/src/main/java/com/vortex/app/controller/MemoryController.java`
- `vortex-app/src/test/java/com/vortex/app/controller/MemoryControllerTest.java`
- `vortex-app/src/main/resources/application.yml` memory-pipeline and async benchmark config
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/AsyncMemoryPipeline.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryExtractionService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemorySummaryService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineRequest.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStage.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStatus.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/MemoryPipelineStatusCode.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/FragmentPersistenceManager.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HierarchicalMemoryController.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkExecutionService.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkReportWriter.java`
- `vortex-app/src/main/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkRunner.java`
- `vortex-app/src/test/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkReportWriterTest.java`
- `vortex-app/src/test/java/com/vortex/app/eval/AsyncPipelineLatencyBenchmarkRunnerTest.java`
- `vortex-kernel/src/test/java/com/vortex/kernel/hmc/AsyncMemoryPipelineTest.java`
- `ops/runbooks/vortex-async-pipeline-latency-benchmark-evidence-20260628.md`
- `ops/runbooks/vortex-main-path-latency-benchmark-evidence-20260629.md`

Notes:

- Current defensible latency claim is main-path P99 `1172.50 ms -> 220.34 ms`,
  average `829.40 ms -> 186.64 ms`, average reduction `77.50%`. Do not stage a
  docs change that reintroduces `99.99%` as main-path latency.

## Commit 3: Recall Ablation Benchmark

Suggested message:

```text
Add hybrid recall ablation benchmark
```

Primary files:

- `vortex-common/src/main/java/com/vortex/common/dto/RecallQuery.java`
- `vortex-common/src/main/java/com/vortex/common/dto/RecallDiagnostics.java`
- `vortex-common/src/main/java/com/vortex/common/dto/RetrievalMode.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/RecallOrchestrator.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/KeywordRecallIndex.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HybridRecallReranker.java`
- `vortex-kernel/src/test/java/com/vortex/kernel/hmc/RecallOrchestratorTest.java`
- `vortex-kernel/src/test/java/com/vortex/kernel/hmc/KeywordRecallIndexTest.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalMode.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalProperties.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalReportWriter.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalRunner.java`
- `vortex-app/src/test/java/com/vortex/app/eval/LlmMemoryEvalReportWriterTest.java`
- `vortex-app/src/test/java/com/vortex/app/eval/LlmMemoryEvalRunnerTest.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallAblationMode.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkExecutionService.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkReportWriter.java`
- `vortex-app/src/main/java/com/vortex/app/eval/RecallBenchmarkRunner.java`
- `vortex-app/src/test/java/com/vortex/app/eval/RecallBenchmarkReportWriterTest.java`
- `vortex-app/src/test/java/com/vortex/app/eval/RecallBenchmarkRunnerTest.java`
- `vortex-app/src/main/resources/application.yml` eval default `VORTEX_VECTOR_ONLY` only
- `ops/runbooks/vortex-recall-benchmark-evidence-20260626.md`
- `ops/runbooks/vortex-recall-ablation-benchmark-evidence-20260630.md`

Notes:

- Canonical ablation evidence is `20260630-recall-ablation-benchmark-v3-1-003`.
- Precision@K is standard `matched/K`; do not use older `-001` precision values.
- `-002` used paging and is not canonical ablation evidence.

## Commit 4: Eval CLI Command Wiring

Suggested message:

```text
Expose benchmark commands through eval CLI
```

Primary files:

- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalCliApplication.java`
- CLI tests if any are updated for command dispatch

Notes:

- This can also be folded into commits 1/2/3 if preferred, but separating it
  makes command-surface changes easier to review.

## Commit 5: Evidence And Handoff Docs

Suggested message:

```text
Document benchmark evidence and handoff state
```

Primary files:

- `ops/runbooks/vortex-next-dev-handoff-20260625.md`
- `ops/runbooks/vortex-next-dev-handoff-20260626.md`
- `ops/runbooks/vortex-next-dev-handoff-20260628.md`
- `ops/runbooks/vortex-next-dev-handoff-20260629.md`
- `ops/runbooks/vortex-next-dev-handoff-20260630.md`
- `ops/runbooks/vortex-project-status-20260609.md`
- `ops/runbooks/vortex-project-status-20260629.md`
- `ops/runbooks/vortex-current-target-wording-20260626.md`
- `ops/runbooks/vortex-current-target-wording-20260628.md`
- Evidence docs listed in commits 1-3 if they are not staged with code commits

Notes:

- Prefer staging evidence docs with the code they validate when possible.
- Keep handoff/status docs in a docs-only commit if code commits are already large.

## Verification Already Run

```powershell
mvn -pl vortex-app -am '-Dtest=RecallBenchmarkRunnerTest,RecallBenchmarkReportWriterTest,LlmMemoryEvalRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn test
```

Latest full result:

- Reactor `BUILD SUCCESS`.
- Common `37` tests passed; Storage `21` tests passed; App `141` tests passed.
- Finished at `2026-06-30T21:29:30+08:00`.

## Final Pre-Commit Checklist

1. Use explicit path-based `git add` for each group; avoid `git add .` in this worktree.
2. Review `application.yml`, `docker-compose.yml`, and `vortex-app/pom.xml` before staging because they span multiple feature groups.
3. Keep `README.md` deletion unstaged unless explicitly approved.
4. Run `mvn test` again after staging if any staged set is adjusted manually.
