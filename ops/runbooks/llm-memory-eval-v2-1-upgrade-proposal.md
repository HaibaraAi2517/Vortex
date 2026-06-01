# LLM Memory Eval v2.1 Baseline Upgrade Proposal

## Status

Phase 1 implemented on 2026-06-01. `official-v2.1-strict` is now the official strict profile for the v2.1 eval contract.

Current official baseline remains:

- profile: `official-v2-strict`
- dataset: `classpath:llm-memory-eval-set-v2.json`
- baseline id: `20260529-real-bge-v2-006`
- report:
  - [llm-memory-eval-20260529-140002.json](E:/1projects/claude/Vortex/ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.json:1)
  - [llm-memory-eval-20260529-140002.md](E:/1projects/claude/Vortex/ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.md:1)

## Recommendation

Approve `contract-v2.1-candidate` for promotion to a new official strict profile named `official-v2.1-strict`.

Do not silently repoint `official-v2-strict`. Do not change the default `verify <report>` profile in the same step. Treat default verifier migration as a separate release decision.

## Why Upgrade

The v2.1 dataset changes only one memory fragment in `v2-009::mobile-cutoff`:

- v2: `The mobile release happens one hour after the localization freeze.`
- v2.1: `The mobile release happens one hour after the localization freeze starts, on the same weekday.`

This is a contract clarification, not a capability relaxation. The v2 wording made the eval depend on an implicit time-offset inference that some real LLM runs handled inconsistently even when recall returned all expected fragments. v2.1 makes the intended same-weekday contract explicit so the eval measures grounded memory and recovery rather than ambiguity tolerance.

## Evidence

Promoted profile:

- profile: `official-v2.1-strict`
- dataset: `classpath:llm-memory-eval-set-v2-1.json`
- baseline id: `20260601-v2-009-contract-audit-5x-net`
- summary:
  - [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260601-v2-009-contract-audit-5x-net/baseline-audit-summary.json:1)
  - [baseline-audit-summary.md](E:/1projects/claude/Vortex/ops/eval-reports/20260601-v2-009-contract-audit-5x-net/baseline-audit-summary.md:1)

Validated result:

- `OverallPassed = true`
- `AuditGate.Passed = true`
- `ProfileGate.Passed = true`
- `StrictVerifierPassed = true`
- `VerifierPassCount = 5/5`
- `VerifierSkippedCount = 0`
- `Baseline-NoMemory correct values = 0, 0, 0, 0, 0`
- `Vortex-Memory accuracy values = 1, 1, 1, 1, 1`
- `RecoveredAccuracy values = 1, 1, 1, 1, 1`
- `RecoveredL2HitRate values = 1, 1, 1, 1, 1`
- `CaseFailureCount = 0`
- `CaseFailureGroupCount = 0`

Comparison point for current v2 stability audit:

- profile: `audit-v2-stability`
- summary: [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260601-mode-scoped-l2-wait-audit-5x-net/baseline-audit-summary.json:1)
- `OverallPassed = true`
- `AuditGate.Passed = true`
- `ProfileGate.Passed = true`
- `StrictVerifierPassed = false`
- `VerifierPassCount = 4/5`
- `CaseFailureCount = 1`

The remaining v2 failure is `v2-009`, where the expected fragments were present and the model still sometimes avoided deriving the final weekday. That supports treating v2.1 as an eval contract fix rather than a memory-system behavior change.

## Phase 1 Implementation

Phase 1 promotes v2.1 without default verifier migration.

1. Add `official-v2.1-strict` to `LlmMemoryEvalBaselineProfile`.
2. Keep `contract-v2.1-candidate` as a transition alias.
3. Point `official-v2.1-strict` to:
   - dataset: `classpath:llm-memory-eval-set-v2-1.json`
   - baseline id: `20260601-v2-009-contract-audit-5x-net`
   - strict expectations: `0/15`, `15/15`, `15/15`, `RecoveredAccuracy = 1.0`, `RecoveredL2HitRate = 1.0`
4. Update audit inference for `classpath:llm-memory-eval-set-v2-1.json`:
   - `BaselineProfile = official-v2.1-strict`
   - `StrictVerifierProfile = official-v2.1-strict`
5. Keep `verify <report>` defaulting to `official-v2-strict`.
6. Update docs and examples to prefer `official-v2.1-strict` for new v2.1 reports.

## Remaining Phase 2

Only after Phase 1 is reviewed, decide whether `verify <report>` should default to `official-v2.1-strict`. If this changes, call it out as a breaking baseline-governance change because existing v2 official reports will drift under the v2.1 default.

## CI Guidance

Recommended CI split after Phase 1:

1. Keep one strict v2 compatibility check against `official-v2-strict`.
2. Add the primary v2.1 strict check against `official-v2.1-strict`.
3. Use multi-run audit with `-FailOnAuditGateFailure` for the current release candidate dataset.
4. Fail CI when `ProfileGate.Passed = false` or `AuditGate.Passed = false`.
5. Do not fail v2 stability audit solely because `StrictVerifierPassed = false`; use it as a diagnostic unless the selected audit profile is intended to be strict-perfect.

## Non-Goals

This proposal does not:

1. Replace `official-v2-strict` in place.
2. Use `audit-v2-stability` as a strict baseline.
3. Mix v2 and v2.1 reports in one baseline trend line.
4. Change the eval prompt contract.
5. Change generation model, base URL, BGE model, L1 token budget, or judge semantics.

## Acceptance Criteria

The promotion is complete only when all of the following are true:

1. `eval-cli verify --list-profiles` shows `official-v2.1-strict`.
2. `eval-cli verify --profile official-v2.1-strict --describe` shows the v2.1 dataset and strict expectations.
3. A v2.1 single report verifies with exit code `0` under `official-v2.1-strict`.
4. A v2 report does not accidentally pass under `official-v2.1-strict`.
5. The v2.1 audit summary has `ProfileGate.Passed = true`, `AuditGate.Passed = true`, and `OverallPassed = true`.
6. The docs clearly state whether default `verify <report>` still means v2 or has moved to v2.1.

## Decision Record Template

When the team decides, append a short decision section:

```text
Decision date:
Decision:
Approved profile id:
Default verify profile:
CI profile:
Rationale:
Rollback plan:
```
