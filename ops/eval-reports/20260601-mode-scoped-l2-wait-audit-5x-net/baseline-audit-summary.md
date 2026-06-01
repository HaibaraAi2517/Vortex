# LLM Memory Baseline Audit

- Audit Stamp: 20260601-mode-scoped-l2-wait-audit-5x-net
- GeneratedAt: 2026-06-01T11:35:20.6346179Z
- Baseline Id: 20260601-mode-scoped-l2-wait-audit-5x-net
- Baseline Profile: audit-v2-stability
- Dataset Version: v2
- Strict Verifier Profile: official-v2-strict
- Overall Passed: True
- Audit Gate Passed: True
- Profile Gate Passed: True
- Strict Verifier Passed: False
- Requested Rounds: 5
- Eval Success Count: 5
- Verifier Pass Count: 4
- Dataset Location: classpath:llm-memory-eval-set-v2.json
- Base URL: https://sub2.congmingai.com
- Model: gpt-5.2
- L1 Max Tokens: 96
- Total Duration Seconds: 5.296

## Aggregate

- Baseline-NoMemory correct values: 0, 0, 0, 0, 0
- Vortex-Memory accuracy values: 1, 1, 0.933333333333333, 1, 1
- RecoveredAccuracy values: 1, 1, 1, 1, 1
- RecoveredL2HitRate values: 1, 1, 1, 1, 1
- Case failure count: 1
- Case failure groups: 1

## Audit Gate

| Check | Passed | Expected | Actual | Details |
| --- | --- | --- | --- | --- |
| evalSuccessCount | True | 5/5 | 5/5 |  |
| environmentStable | True | all completed runs share dataset/baseUrl/model/l1MaxTokens/promptSha/modes | completedRuns=5 |  |
| baselineNoMemoryMaxCorrect | True | max <= 0 | values=[0, 0, 0, 0, 0] |  |
| vortexMemoryMeanAccuracy | True | >= 0.8500 | 0.9867 |  |
| recoveredMeanAccuracy | True | >= 0.9500 | 1.0000 |  |
| recoveredL2MeanHitRate | True | >= 0.9500 | 1.0000 |  |


## Profile Gate

| Check | Passed | Expected | Actual | Details |
| --- | --- | --- | --- | --- |
| datasetVersion | True | v2 | v2 |  |
| baselineProfileForDataset | True | audit-v2-stability | audit-v2-stability |  |
| strictVerifierProfileForDataset | True | official-v2-strict | official-v2-strict |  |
| baselineProfileDefinition | True | known profile for v2/classpath:llm-memory-eval-set-v2.json | audit-v2-stability |  |
| strictVerifierProfileDefinition | True | strict-report profile for v2/classpath:llm-memory-eval-set-v2.json | official-v2-strict |  |
| runDatasetLocation | True | classpath:llm-memory-eval-set-v2.json | completedRuns=5 |  |
| runDatasetVersion | True | v2 | completedRuns=5 |  |
| runBaselineProfileId | True | audit-v2-stability | completedRuns=5 |  |
| runStrictVerifierProfileId | True | official-v2-strict | completedRuns=5 |  |
| runVerifyProfile | True | official-v2-strict | completedRuns=5 |  |


## Case Failure Summary

| Case | Mode | Failures | Rounds | Recall Hit | Recall Miss | Missing Expected Fragments | Expected Answer | Question |
| --- | --- | ---: | --- | ---: | ---: | --- | --- | --- |
| v2-009 | Vortex-Memory | 1/5 | 3 | 1 | 0 |  | Thursday | On which weekday does the mobile release happen? |


## Case Failure Details

| Round | Case | Mode | Recall Hit | Returned Fragments | Missing Expected Fragments | Generated Answer |
| ---: | --- | --- | --- | --- | --- | --- |
| 3 | v2-009 | Vortex-Memory | True | old-release-window, mobile-cutoff, localization-freeze |  | The memory is insufficient to determine the weekday: it says the localization freeze **starts every Thursday at 08:00 UTC** and the mobile release happens **one hour after the loca... |


## Runs

| Round | Eval | Verify | Baseline | Memory | Recovered | RecoveredAccuracy | RecoveredL2HitRate | Report |
| --- | --- | --- | --- | --- | --- | ---: | ---: | --- |
| 1 | completed | PASS | 0/15 | 15/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-081513.json |
| 2 | completed | PASS | 0/15 | 15/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-081730.json |
| 3 | completed | DRIFT | 0/15 | 14/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-082017.json |
| 4 | completed | PASS | 0/15 | 15/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-082238.json |
| 5 | completed | PASS | 0/15 | 15/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-082500.json |


## Verification Drift

## Round 3

```text
FAIL: report 'E:\1projects\claude\Vortex\ops\eval-reports\20260601-mode-scoped-l2-wait-audit-5x-net\runs\20260601-mode-scoped-l2-wait-audit-5x-net-run03\llm-memory-eval-20260601-082017.json' drifted from LLM memory eval baseline profile 'official-v2-strict' (baseline '20260529-real-bge-v2-006', dataset v2).
- modeSummaries.Vortex-Memory.correct expected=15 actual=14
- modeSummaries.Vortex-Memory.accuracy expected=1.0 actual=0.9333333333333333
```
