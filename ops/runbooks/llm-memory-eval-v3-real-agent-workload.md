# LLM Memory Eval v3 Real Agent Workload

## Status

Official strict workload promoted on 2026-06-03.

This dataset answers the product question of whether Vortex memory still helps when the cases look more like long-running Agent work instead of isolated factual QA.

## Dataset

- Dataset location: `classpath:llm-memory-eval-set-v3-real-agent-workload.json`
- Dataset version: `v3-real-agent-workload`
- Baseline profile: `official-v3-real-agent-workload-strict`
- Strict verifier profile: `official-v3-real-agent-workload-strict`
- Historical audit-only profile: `audit-v3-real-agent-workload`
- Case count: 12

## Scope

The v3 workload covers:

1. Current-state updates over historical distractors.
2. Cross-task blockers and owners.
3. User preference changes.
4. Checkpoint continuation after recovery.
5. Branch-specific decision recall.
6. Alias resolution through current plans.
7. Long-context distractors.
8. Tool-policy and safety-sensitive recall.
9. Decision-rule application.
10. Planning priority recall.

## Governance

`audit-v3-real-agent-workload` remains as historical audit-only evidence. New v3 reports should infer `official-v3-real-agent-workload-strict` as both baseline profile and strict verifier profile.

The promotion gate was:

1. At least 3 real LLM audit rounds complete.
2. `ProfileGate.Passed = true`.
3. `AuditGate.Passed = true`.
4. Case failures, if any, are classified as recall, generation contract, or judge issues.
5. The accepted evidence stamp is recorded in this runbook.

## Audit History

### 20260603-v3-real-agent-workload-audit-001

Result: rejected for promotion.

- `EvalSuccessCount`: 3/3.
- `ProfileGate.Passed`: true.
- `AuditGate.Passed`: false.
- `Baseline-NoMemory`: 0/12 in all rounds.
- `RecoveredL2HitRate`: 1.0 in all rounds.
- Failure diagnosis: the recurring failures were dominated by dataset/judge-contract issues, not L2 recovery misses. The generated answers recalled the right fragments but were penalized for mentioning obsolete context as obsolete, or for semantically correct wording that did not exactly match long `mustContain` phrases.

Remediation before the next audit:

1. Keep v3 audit-only.
2. Narrow historical-distractor `mustNotContain` terms so they reject stale current-state claims, not explanatory historical context.
3. Split brittle long `mustContain` phrases into smaller semantic anchors for checkpoint and planning-priority cases.

### 20260603-v3-real-agent-workload-audit-002

Result: accepted promotion evidence.

- `OverallPassed`: true.
- `AuditGate.Passed`: true.
- `ProfileGate.Passed`: true.
- `EvalSuccessCount`: 3/3.
- `Baseline-NoMemory`: 0/12 in all rounds.
- `Vortex-Memory`: 12/12 in all rounds.
- `Vortex-RecoveredMemory`: 12/12 in all rounds.
- `RecoveredAccuracy`: 1.0 in all rounds.
- `RecoveredL2HitRate`: 1.0 in all rounds.
- `CaseFailureCount`: 0.
- `TransientRuntimeErrorCount`: 0.
- Summary:
  - [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260603-v3-real-agent-workload-audit-002/baseline-audit-summary.json:1)
  - [baseline-audit-summary.md](E:/1projects/claude/Vortex/ops/eval-reports/20260603-v3-real-agent-workload-audit-002/baseline-audit-summary.md:1)

## Recommended Strict Audit

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-llm-memory-baseline-audit.ps1 `
  -ApiKey '...' `
  -BaseUrl 'https://sub2.congmingai.com' `
  -Model 'gpt-5.2' `
  -Rounds 3 `
  -DatasetLocation 'classpath:llm-memory-eval-set-v3-real-agent-workload.json' `
  -AuditStamp '20260603-v3-real-agent-workload-official-strict-audit' `
  -EvalParallelism 24 `
  -SkipComposeUp `
  -FailOnAuditGateFailure
```

The script should infer `official-v3-real-agent-workload-strict` for both `BaselineProfile` and `StrictVerifierProfile`.

## Promotion Criteria

The promoted strict profile verifies:

1. `Baseline-NoMemory` remains low enough to prove memory lift.
2. `Vortex-Memory` mean accuracy is at least `0.85`.
3. `Vortex-RecoveredMemory` mean recovered accuracy is at least `0.95`.
4. `RecoveredL2HitRate` mean is at least `0.95`.
5. No recurring unclassified failures remain.

Use `audit-v3-real-agent-workload` only for historical audit evidence or exploratory runs where strict verification is intentionally disabled.
