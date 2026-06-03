# LLM Memory Eval v3.1 Workload Proposal

## Status

Candidate workload created on 2026-06-03.

Phase 4 promoted v3.1 to an official strict profile after the real LLM multi-round audit passed. The promotion decision is recorded in:

- [vortex-baseline-governance-phase-4-decision.md](E:/1projects/claude/Vortex/ops/runbooks/vortex-baseline-governance-phase-4-decision.md:1)

## Dataset

- Dataset location: `classpath:llm-memory-eval-set-v3-1-real-agent-workload.json`
- Dataset version: `v3.1-real-agent-workload`
- Official baseline profile: `official-v3.1-real-agent-workload-strict`
- Official strict verifier profile: `official-v3.1-real-agent-workload-strict`
- Historical candidate profile: `candidate-v3.1-real-agent-workload`
- Case count: 20

## Scope

v3.1 extends v3 with harder long-task Agent memory patterns:

1. Multi-step current-state overwrites.
2. Namespace collision with identical entity names.
3. Branch-specific final decisions.
4. Checkpoint continuation without repeating old actions.
5. Tool policy changes and safety-sensitive recall.
6. User preference reversals.
7. Long-context distractors with similar stale facts.
8. Multi-fragment synthesis where one fragment is insufficient.
9. Alias and codename resolution through current plans.
10. Approval and escalation policy application.
11. Recency-sensitive incident ownership.
12. Explicit rejection of stale current-state claims.

## Contract Rules

- `mustContain` entries should be short semantic anchors, not brittle long sentences.
- `mustNotContain` should reject stale current-state claims, not explanatory historical context.
- At least 30 percent of cases should require two or more `expectedFragments`.
- Each case should have at least four memory fragments.
- Every case should include `failureCategories`.
- The candidate workload should pass deterministic runner regression before any real LLM audit.

## Promotion Gate

Promotion required all of these to be true:

1. At least 3 real LLM audit rounds complete.
2. `ProfileGate.Passed = true`.
3. `AuditGate.Passed = true`.
4. `Baseline-NoMemory` remains low enough to demonstrate memory lift.
5. `Vortex-Memory` mean accuracy is at least `0.85`.
6. `Vortex-RecoveredMemory` mean recovered accuracy is at least `0.95`.
7. `RecoveredL2HitRate` mean is at least `0.95`.
8. Recurring failures, if any, are classified as recall, generation contract, or judge issues.

Accepted evidence:

- Audit stamp: `20260603-v3-1-real-agent-workload-candidate-audit-003`
- `OverallPassed = true`
- `AuditGate.Passed = true`
- `ProfileGate.Passed = true`
- `CaseFailureCount = 0`
- `Baseline-NoMemory = 0/20` in all three rounds
- `Vortex-Memory = 20/20` in all three rounds
- `Vortex-RecoveredMemory = 20/20` in all three rounds
- `RecoveredL2HitRate = 1.0` in all three rounds

## Recommended Official Audit

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-llm-memory-baseline-audit.ps1 `
  -ApiKey '...' `
  -BaseUrl 'https://sub2.congmingai.com' `
  -Model 'gpt-5.2' `
  -Rounds 3 `
  -DatasetLocation 'classpath:llm-memory-eval-set-v3-1-real-agent-workload.json' `
  -AuditStamp 'v3-1-real-agent-workload-official-strict-audit' `
  -EvalParallelism 24 `
  -SkipComposeUp `
  -FailOnAuditGateFailure
```

The script should infer:

- `BaselineProfile = official-v3.1-real-agent-workload-strict`
- `StrictVerifierProfile = official-v3.1-real-agent-workload-strict`

## Non-Goals

- Do not change the default `eval-cli verify <report>` profile.
- Do not remove the historical `candidate-v3.1-real-agent-workload` profile.
- Do not commit generated audit reports unless a release decision explicitly promotes the artifact into tracked baseline evidence.
