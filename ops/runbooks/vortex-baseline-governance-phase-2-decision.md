# Vortex Baseline Governance Phase 2 Decision

## Status

Decision recorded on 2026-06-02.

Phase 2 promotes the 30-case `v2.1-extended` eval from candidate audit evidence into an explicit strict baseline profile. It does not change the default `eval-cli verify <report>` profile.

## Decision

- Approved strict profile: `official-v2.1-extended-strict`
- Dataset: `classpath:llm-memory-eval-set-v2-1-extended.json`
- Dataset version: `v2.1-extended`
- Baseline evidence: `20260602-v2-1-extended-candidate-audit-generation-retry-001`
- Default `verify <report>` profile: keep `official-v2-strict`
- Compatibility profiles retained:
  - `official-v2-strict`
  - `official-v2.1-strict`
  - `contract-v2.1-candidate`
  - `candidate-v2.1-extended`

`candidate-v2.1-extended` remains as the historical audit-only profile used by the promotion evidence. New `v2.1-extended` eval reports should infer `official-v2.1-extended-strict` as both the baseline profile and strict verifier profile.

## Evidence

Promotion evidence:

- Audit stamp: `20260602-v2-1-extended-candidate-audit-generation-retry-001`
- Summary:
  - [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260602-v2-1-extended-candidate-audit-generation-retry-001/baseline-audit-summary.json:1)
  - [baseline-audit-summary.md](E:/1projects/claude/Vortex/ops/eval-reports/20260602-v2-1-extended-candidate-audit-generation-retry-001/baseline-audit-summary.md:1)
- Rounds: `3`
- Dataset: `classpath:llm-memory-eval-set-v2-1-extended.json`
- Model: `gpt-5.2`
- Base URL: `https://sub2.congmingai.com`
- L1 max tokens: `96`
- Eval system prompt SHA-256: `e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3`

Validated result:

- `OverallPassed = true`
- `AuditGate.Passed = true`
- `ProfileGate.Passed = true`
- `EvalSuccessCount = 3/3`
- `EvalFailureCount = 0`
- `VerifierSkippedCount = 3`
- `CaseFailureCount = 0`
- `CaseFailureGroupCount = 0`
- `RuntimeErrorTypeCounts = {}`
- `TransientRuntimeErrorCount = 0`
- `Baseline-NoMemory = 0/30` in all three rounds
- `Vortex-Memory = 30/30` in all three rounds
- `Vortex-RecoveredMemory = 30/30` in all three rounds
- `RecoveredAccuracy = 1.0` in all three rounds
- `RecoveredL2HitRate = 1.0` in all three rounds

Real retry evidence in the promoted audit:

- `v2-002 / Vortex-RecoveredMemory / attempts=3`
- `v2-004 / Vortex-RecoveredMemory / attempts=3`

The retry evidence matters because the final audit completed with no runtime failures after transient generation retry handling was added.

Post-promotion evidence status:

- Attempted audit stamp: `20260602-v2-1-extended-official-strict-audit-001`
- Result: not accepted as baseline evidence
- Reason: generation API quota was exhausted during the run
- Details:
  - Round 1 completed the eval process but hit repeated `generation_http_429` runtime errors after the first subset of cases.
  - Round 2 and Round 3 failed during generation preflight with `API_KEY_QUOTA_EXHAUSTED`.
  - The report environment correctly recorded:
    - `baselineProfileId = official-v2.1-extended-strict`
    - `strictVerifierProfileId = official-v2.1-extended-strict`
  - Because `EvalSuccessCount = 1/3`, `ProfileGate.Passed = false`, `AuditGate.Passed = false`, and `StrictVerifierPassed = false`, this artifact must remain diagnostic only.
- Attempted audit stamp: `20260602-v2-1-extended-official-strict-audit-002`
- Result: not accepted as baseline evidence
- Reason: generation API preflight returned `401 API_KEY_DISABLED` for all three rounds
- Details:
  - No round produced an eval report.
  - The script-level profile settings were correct:
    - `BaselineProfile = official-v2.1-extended-strict`
    - `StrictVerifierProfile = official-v2.1-extended-strict`
  - Because `EvalSuccessCount = 0/3`, this artifact must remain diagnostic only.

Accepted post-promotion evidence:

- Audit stamp: `20260602-v2-1-extended-official-strict-audit-008`
- Summary:
  - [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260602-v2-1-extended-official-strict-audit-008/baseline-audit-summary.json:1)
  - [baseline-audit-summary.md](E:/1projects/claude/Vortex/ops/eval-reports/20260602-v2-1-extended-official-strict-audit-008/baseline-audit-summary.md:1)
- Rounds: `3`
- Eval parallelism: `60`
- Dataset: `classpath:llm-memory-eval-set-v2-1-extended.json`
- Model: `gpt-5.2`
- Base URL: `https://sub2.congmingai.com`
- L1 max tokens: `96`
- Result:
  - `OverallPassed = true`
  - `AuditGate.Passed = true`
  - `ProfileGate.Passed = true`
  - `StrictVerifierPassed = true`
  - `VerifierPassCount = 3/3`
  - `EvalSuccessCount = 3/3`
  - `CaseFailureCount = 0`
  - `TransientRuntimeErrorCount = 0`
  - `Baseline-NoMemory = 0/30` in all three rounds
  - `Vortex-Memory = 30/30` in all three rounds
  - `Vortex-RecoveredMemory = 30/30` in all three rounds
  - `RecoveredAccuracy = 1.0` in all three rounds
  - `RecoveredL2HitRate = 1.0` in all three rounds

Earlier post-promotion audit stamps `005`, `006`, and `007` are retained as diagnostic tuning evidence for eval concurrency and judge false-positive hardening. They are not the accepted post-promotion baseline evidence.

## Strict Expectations

`official-v2.1-extended-strict` verifies a single 30-case report with these exact expectations:

- `environment.datasetLocation = classpath:llm-memory-eval-set-v2-1-extended.json`
- `environment.generationBaseUrl = https://sub2.congmingai.com/v1`
- `environment.generationModel = gpt-5.2`
- `environment.l1MaxTokens = 96`
- `environment.evalSystemPromptSha256 = e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3`
- modes exactly:
  - `Baseline-NoMemory`
  - `Vortex-Memory`
  - `Vortex-RecoveredMemory`
- `Baseline-NoMemory = 0/30`
- `Vortex-Memory = 30/30`
- `Vortex-RecoveredMemory = 30/30`
- `Vortex-RecoveredMemory.recoveredAccuracy = 1.0`
- `Vortex-RecoveredMemory.recoveredL2HitRate = 1.0`

## Migration Rules

1. Keep default `verify <report>` on `official-v2-strict`.
2. Use `--profile official-v2.1-extended-strict` for 30-case extended strict verification.
3. Keep v2 compatibility checks available under `official-v2-strict`.
4. Keep v2.1 compatibility checks available under `official-v2.1-strict`.
5. Treat `candidate-v2.1-extended` as historical audit evidence, not the profile for new official extended reports.
6. Do not commit ignored eval report artifacts unless a release decision explicitly promotes the artifact into tracked baseline evidence.

## Rollback Plan

If the extended strict profile causes governance or CI friction:

1. Revert `v2.1-extended` dataset inference back to `candidate-v2.1-extended` and an empty strict verifier profile.
2. Keep `official-v2.1-extended-strict` available only for explicit manual verification, or remove it if no longer needed.
3. Leave the default `verify <report>` unchanged on `official-v2-strict`.
4. Re-run the v2 and v2.1 strict tests to confirm compatibility profiles still pass.

## Acceptance Criteria

Phase 2 is complete when:

1. `eval-cli verify --list-profiles` shows `official-v2.1-extended-strict`.
2. `eval-cli verify --profile official-v2.1-extended-strict --describe` shows the 30-case strict expectations.
3. A synthetic 30-case extended report passes `LlmMemoryEvalBaselineVerifier` under `official-v2.1-extended-strict`.
4. `v2.1-extended` dataset inference returns:
   - baseline profile: `official-v2.1-extended-strict`
   - strict verifier profile: `official-v2.1-extended-strict`
5. The default verifier remains `official-v2-strict`.
6. Existing `official-v2-strict` and `official-v2.1-strict` tests still pass.
