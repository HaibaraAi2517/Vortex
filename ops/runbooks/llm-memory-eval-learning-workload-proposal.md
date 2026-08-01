# LLM Memory Eval Learning Workload Proposal

## Status

Proposed on 2026-06-08. First deterministic implementation completed on 2026-06-09.
Accepted hard-governance JSON evidence was promoted on 2026-06-09:

```text
ops/eval-fixtures/learning/20260609-learning-v1-agent-feedback-hard-governance-001/
```

This proposal defines the next benchmark after the v3.1 official strict memory/recovery baseline. It does not replace or modify `official-v3.1-real-agent-workload-strict`.

## Goal

The v3.1 baseline proves that Vortex memory and L2 recovery materially improve real LLM answers in a controlled long-task Agent workload. It does not prove that adaptive learning improves retrieval or eviction quality over time.

The next useful benchmark is a dedicated learning workload:

```text
Given repeated feedback in the same namespace, does AdaptiveWeightLearner improve recall ranking and eviction decisions for later similar tasks?
```

The proposed profile id is:

```text
learning-v1-agent-feedback-audit
```

## Non-Goals

1. Do not merge this workload into the v3.1 official strict baseline.
2. Do not make this the default governance check until it has passed a candidate phase.
3. Do not require a real LLM in the first implementation.
4. Do not use answer text judging as the primary signal for learning v1.
5. Do not tune production learning thresholds from one synthetic run.

## Existing Primitives

The repository already has the core signals needed for this workload:

1. `RecallSessionRecord` stores active, shadow, and baseline ranked fragment ids.
2. `AdaptiveWeightLearner.recordFeedback(...)` consumes `usedFragmentIds`, `answerAccepted`, and `regretRate`.
3. `ShadowEvaluationTracker` records active/shadow/baseline recall NDCG, eviction utility, composite score, relative lift, baseline lift, win rates, sample count, and promotion eligibility.
4. `AdaptiveWeightLearner.metricsSnapshot(...)` exposes answer reward, regret penalty, grounding, precision, coverage, and reward by active/shadow/baseline profile.
5. `DockerComposeIT.feedbackDrivesWeightEvolution` already proves the update path moves with repeated feedback, but it is not a benchmark-grade workload.

## Recommended First Harness

Use a deterministic, no-generation harness first.

The runner should call memory store / recall / feedback paths and judge success by fragment ids, not generated text. This keeps the first benchmark independent from provider timeouts, model routing, API quota, and prompt-contract drift.

The workload can later add an optional real LLM answer phase, but learning v1 should be accepted or rejected by ranking and feedback metrics.

## Workload Shape

Each run should use a unique namespace prefix and a fixed set of scenario templates.

Each scenario has these phases:

1. Seed fragments.
2. Run calibration recalls before feedback.
3. Submit feedback marking the actually useful fragments.
4. Run later probe recalls with similar but not identical queries.
5. Apply L1 token pressure so eviction ranking is measured.
6. Record active/shadow/baseline metrics before and after feedback.

The first profile should contain 5 scenario groups:

1. Preference disambiguation: similar fragments differ only in user/team preference.
2. Current-vs-history: historical distractors are semantically close to the current state.
3. Tool policy: safe current tool policy competes with older unsafe policy.
4. Branch continuation: accepted branch fragments compete with rejected branch fragments.
5. Multi-fragment synthesis: the correct answer requires two useful fragments, not one.

## Scenario Contract

A scenario should declare:

```json
{
  "scenarioId": "learning-v1-tool-policy-001",
  "namespace": "learning-v1-agent-feedback",
  "memoryScenario": "CHAT",
  "topK": 4,
  "tokenBudget": 64,
  "fragments": [
    {
      "fragmentId": "rhea-current-policy",
      "content": "For production ledger debugging, use the read-only SQL console.",
      "relevant": true
    },
    {
      "fragmentId": "rhea-old-policy",
      "content": "Earlier sandbox debugging allowed the write-enabled SQL console.",
      "relevant": false
    }
  ],
  "calibrationQueries": [
    "Which SQL console should Rhea use for the current ledger investigation?"
  ],
  "feedback": {
    "usedFragmentIds": ["rhea-current-policy"],
    "answerAccepted": true,
    "regretRate": 0.0
  },
  "probeQueries": [
    "For the production ledger issue, which SQL console is allowed?"
  ]
}
```

The final dataset format can differ from this sketch, but it should preserve the same concepts: fixed relevant ids, distractors, calibration recalls, feedback, and later probes.

## Metrics

The report should include run-level and scenario-level metrics:

1. `sampleCountBefore` / `sampleCountAfter`
2. `activeUpdateCountBefore` / `activeUpdateCountAfter`
3. `pendingRecallSessions`
4. `activeAverageNdcgBefore` / `activeAverageNdcgAfter`
5. `shadowAverageNdcg`
6. `baselineAverageNdcg`
7. `activeEvictionUtility`
8. `shadowEvictionUtility`
9. `baselineEvictionUtility`
10. `shadowRelativeLift`
11. `baselineRelativeLift`
12. `shadowWinRate`
13. `baselineWinRate`
14. `activeSelectionPrecision`
15. `activeSelectionCoverage`
16. median rank of relevant fragments before and after feedback
17. probe recall hit rate for all required relevant fragments

The most important question is not only whether Vortex returns a relevant fragment. It is whether repeated feedback moves relevant fragments earlier and makes relevant fragments less likely to appear in the eviction candidates.

## Candidate Gate

For the first candidate gate, keep thresholds conservative:

1. `scenarioCount >= 5`
2. `feedbackSampleCount >= 30`
3. `pendingRecallSessions = 0`
4. `activeUpdateCountAfter > activeUpdateCountBefore`
5. `probeAllRelevantHitRate >= 0.90`
6. `activeAverageNdcgAfter >= activeAverageNdcgBefore`
7. `medianRelevantRankAfter <= medianRelevantRankBefore`
8. no scenario has `activeSelectionCoverage = 0.0` after feedback

Do not require shadow promotion in v1. Promotion depends on configured thresholds and windows; v1 should prove measurable learning signal before it proves production deployment promotion.

The hard governance run uses a stricter candidate threshold:

1. `rankImprovedScenarioCount >= 5`
2. `ndcgImprovedScenarioCount >= 5`
3. `probeAverageNdcg >= 0.90`

This makes the run prove that every scenario starts with an initial distractor recall and reaches correct probe ranking after feedback.

## Report Artifacts

Generated reports should go under:

```text
ops/eval-reports/<stamp>/
```

If a candidate is promoted later, only minimum JSON evidence should be copied into:

```text
ops/eval-fixtures/learning/<stamp>/
```

Do not commit generated Markdown as fixture evidence unless a governance decision explicitly needs it.

## Implementation Plan

1. Add a small learning workload dataset under `vortex-app/src/main/resources/`.
2. Add a learning eval runner in `vortex-app/src/main/java/com/vortex/app/eval/`.
3. Reuse existing HMC store / recall / feedback APIs instead of inventing a parallel learner.
4. Add a JSON report writer and Markdown summary writer.
5. Add unit tests for metric calculation and gate evaluation.
6. Add one integration test that runs a small deterministic learning scenario without real generation.
7. Add an ops script only after the runner is stable.
8. Keep the default baseline governance script unchanged until a promoted learning fixture exists.

Implemented entry points:

1. `java -jar vortex-app/target/vortex-app-0.1.0-eval-cli.jar learning`
2. `java -jar vortex-app/target/vortex-app-0.1.0-eval-cli.jar learning verify <report.json>`
3. `ops/run-learning-memory-eval.ps1`
4. `ops/run-learning-governance-check.ps1`

The learning governance script now defaults to verifying the accepted evidence stamp:

```text
20260609-learning-v1-agent-feedback-hard-governance-001
```

CI now replays the promoted learning fixture without generating a new run:

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-learning-governance-check.ps1 `
  -SkipMavenTest `
  -SkipPackage `
  -SkipLearningRun
```

The full local/release check can still run the deterministic hard workload and then verify both the generated report and the promoted fixture:

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-learning-governance-check.ps1
```

## Risk Controls

1. Use a per-run `vortex.kernel.learning.shadow-persistence-path` so old local state cannot contaminate results.
2. Use unique namespaces for every run.
3. Clean generated output only under the current stamp.
4. Record learning properties in the report, including learning rate, warmup recalls, promotion threshold, promotion window, and minimum samples before promotion.
5. Record active and shadow profile names and arm indices so a later tuning change can be diagnosed.
6. Keep the first gate no-generation and deterministic; add real LLM answer quality only after ranking metrics are stable.

## Acceptance Definition

The proposal is ready to implement when:

1. The profile id is accepted as `learning-v1-agent-feedback-audit`.
2. The first dataset schema is agreed.
3. The runner is explicitly scoped as no-generation by default.
4. The report schema includes active/shadow/baseline ranking and eviction metrics.
5. The candidate gate does not alter the current v3.1 official strict governance path.
