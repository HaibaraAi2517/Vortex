# Vortex Baseline Governance Phase 3 Decision

## Status

Decision recorded on 2026-06-03.

Phase 3 promotes the 12-case `v3-real-agent-workload` eval from candidate audit evidence into an explicit strict baseline profile. It does not change the default `eval-cli verify <report>` profile.

## Decision

- Approved strict profile: `official-v3-real-agent-workload-strict`
- Dataset: `classpath:llm-memory-eval-set-v3-real-agent-workload.json`
- Dataset version: `v3-real-agent-workload`
- Baseline evidence: `20260603-v3-real-agent-workload-audit-002`
- Default `verify <report>` profile: keep `official-v2-strict`
- Compatibility profile retained: `audit-v3-real-agent-workload`

`audit-v3-real-agent-workload` remains as the historical audit-only profile used before promotion. New `v3-real-agent-workload` eval reports should infer `official-v3-real-agent-workload-strict` as both the baseline profile and strict verifier profile.

## Evidence

Promotion evidence:

- Audit stamp: `20260603-v3-real-agent-workload-audit-002`
- Summary:
  - [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260603-v3-real-agent-workload-audit-002/baseline-audit-summary.json:1)
  - [baseline-audit-summary.md](E:/1projects/claude/Vortex/ops/eval-reports/20260603-v3-real-agent-workload-audit-002/baseline-audit-summary.md:1)
- Rounds: `3`
- Dataset: `classpath:llm-memory-eval-set-v3-real-agent-workload.json`
- Model: `gpt-5.2`
- Base URL: `https://sub2.congmingai.com`
- L1 max tokens: `96`
- Eval parallelism: `24`
- Eval system prompt SHA-256: `e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3`

Validated result:

- `OverallPassed = true`
- `AuditGate.Passed = true`
- `ProfileGate.Passed = true`
- `EvalSuccessCount = 3/3`
- `CaseFailureCount = 0`
- `CaseFailureGroupCount = 0`
- `RuntimeErrorTypeCounts = {}`
- `TransientRuntimeErrorCount = 0`
- `Baseline-NoMemory = 0/12` in all three rounds
- `Vortex-Memory = 12/12` in all three rounds
- `Vortex-RecoveredMemory = 12/12` in all three rounds
- `RecoveredAccuracy = 1.0` in all three rounds
- `RecoveredL2HitRate = 1.0` in all three rounds

Accepted post-promotion evidence:

- Audit stamp: `20260603-v3-real-agent-workload-official-strict-audit-001`
- Summary:
  - [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260603-v3-real-agent-workload-official-strict-audit-001/baseline-audit-summary.json:1)
  - [baseline-audit-summary.md](E:/1projects/claude/Vortex/ops/eval-reports/20260603-v3-real-agent-workload-official-strict-audit-001/baseline-audit-summary.md:1)
- Rounds: `3`
- Dataset: `classpath:llm-memory-eval-set-v3-real-agent-workload.json`
- Model: `gpt-5.2`
- Base URL: `https://sub2.congmingai.com`
- L1 max tokens: `96`
- Eval parallelism: `24`
- Result:
  - `OverallPassed = true`
  - `AuditGate.Passed = true`
  - `ProfileGate.Passed = true`
  - `StrictVerifierPassed = true`
  - `VerifierPassCount = 3/3`
  - `EvalSuccessCount = 3/3`
  - `CaseFailureCount = 0`
  - `TransientRuntimeErrorCount = 0`
  - `Baseline-NoMemory = 0/12` in all three rounds
  - `Vortex-Memory = 12/12` in all three rounds
  - `Vortex-RecoveredMemory = 12/12` in all three rounds
  - `RecoveredAccuracy = 1.0` in all three rounds
  - `RecoveredL2HitRate = 1.0` in all three rounds

Rejected predecessor:

- Audit stamp: `20260603-v3-real-agent-workload-audit-001`
- Result: not accepted as baseline evidence
- Reason: recurring failures were dataset/judge-contract false positives, not memory recovery misses.
- Remediation: historical distractor checks were narrowed to reject stale current-state claims, and brittle long `mustContain` phrases were split into semantic anchors.

## Strict Expectations

`official-v3-real-agent-workload-strict` verifies a single 12-case report with these exact expectations:

- `environment.datasetLocation = classpath:llm-memory-eval-set-v3-real-agent-workload.json`
- `environment.generationBaseUrl = https://sub2.congmingai.com/v1`
- `environment.generationModel = gpt-5.2`
- `environment.l1MaxTokens = 96`
- `environment.evalSystemPromptSha256 = e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3`
- modes exactly:
  - `Baseline-NoMemory`
  - `Vortex-Memory`
  - `Vortex-RecoveredMemory`
- `Baseline-NoMemory = 0/12`
- `Vortex-Memory = 12/12`
- `Vortex-RecoveredMemory = 12/12`
- `Vortex-RecoveredMemory.recoveredAccuracy = 1.0`
- `Vortex-RecoveredMemory.recoveredL2HitRate = 1.0`

## Migration Rules

1. Keep default `verify <report>` on `official-v2-strict`.
2. Use `--profile official-v3-real-agent-workload-strict` for v3 strict verification.
3. Treat `audit-v3-real-agent-workload` as historical audit evidence, not the profile for new official v3 reports.
4. Do not commit ignored eval report artifacts unless a release decision explicitly promotes the artifact into tracked baseline evidence.

## Rollback Plan

If the v3 strict profile causes governance or CI friction:

1. Revert `v3-real-agent-workload` dataset inference back to `audit-v3-real-agent-workload` and an empty strict verifier profile.
2. Keep `official-v3-real-agent-workload-strict` available only for explicit manual verification, or remove it if no longer needed.
3. Leave the default verifier unchanged on `official-v2-strict`.
4. Re-run v2, v2.1, v2.1 extended, and v3 profile tests to confirm compatibility.

## Acceptance Criteria

Phase 3 is complete when:

1. `eval-cli verify --list-profiles` shows `official-v3-real-agent-workload-strict`.
2. `eval-cli verify --profile official-v3-real-agent-workload-strict --describe` shows the 12-case strict expectations.
3. A synthetic 12-case v3 report passes `LlmMemoryEvalBaselineVerifier` under `official-v3-real-agent-workload-strict`.
4. `v3-real-agent-workload` dataset inference returns:
   - baseline profile: `official-v3-real-agent-workload-strict`
   - strict verifier profile: `official-v3-real-agent-workload-strict`
5. The default verifier remains `official-v2-strict`.
6. Existing v2, v2.1, and v2.1 extended strict tests still pass.
