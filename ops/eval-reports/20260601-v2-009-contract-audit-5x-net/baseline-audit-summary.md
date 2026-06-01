# LLM Memory Baseline Audit

- Audit Stamp: 20260601-v2-009-contract-audit-5x-net
- GeneratedAt: 2026-06-01T09:39:57.7290331Z
- Baseline Id: 20260601-v2-009-contract-audit-5x-net
- Baseline Profile: contract-v2.1-candidate
- Dataset Version: v2.1
- Strict Verifier Profile: contract-v2.1-candidate
- Overall Passed: True
- Audit Gate Passed: True
- Strict Verifier Passed: True
- Requested Rounds: 5
- Eval Success Count: 5
- Verifier Pass Count: 5
- Dataset Location: classpath:llm-memory-eval-set-v2-1.json
- Base URL: https://sub2.congmingai.com
- Model: gpt-5.2
- L1 Max Tokens: 96
- Total Duration Seconds: 5.275

## Aggregate

- Baseline-NoMemory correct values: 0, 0, 0, 0, 0
- Vortex-Memory accuracy values: 1, 1, 1, 1, 1
- RecoveredAccuracy values: 1, 1, 1, 1, 1
- RecoveredL2HitRate values: 1, 1, 1, 1, 1
- Case failure count: 0
- Case failure groups: 0

## Audit Gate

| Check | Passed | Expected | Actual | Details |
| --- | --- | --- | --- | --- |
| evalSuccessCount | True | 5/5 | 5/5 |  |
| environmentStable | True | all completed runs share dataset/baseUrl/model/l1MaxTokens/promptSha/modes | completedRuns=5 |  |
| baselineNoMemoryMaxCorrect | True | max <= 0 | values=[0, 0, 0, 0, 0] |  |
| vortexMemoryMeanAccuracy | True | >= 0.8500 | 1.0000 |  |
| recoveredMeanAccuracy | True | >= 0.9500 | 1.0000 |  |
| recoveredL2MeanHitRate | True | >= 0.9500 | 1.0000 |  |


## Case Failure Summary

| Case | Mode | Failures | Rounds | Recall Hit | Recall Miss | Missing Expected Fragments | Expected Answer | Question |
| --- | --- | ---: | --- | ---: | ---: | --- | --- | --- |
|  |  | / |  |  |  |  |  |  |


## Case Failure Details

| Round | Case | Mode | Recall Hit | Returned Fragments | Missing Expected Fragments | Generated Answer |
| ---: | --- | --- | --- | --- | --- | --- |


## Runs

| Round | Eval | Verify | Baseline | Memory | Recovered | RecoveredAccuracy | RecoveredL2HitRate | Report |
| --- | --- | --- | --- | --- | --- | ---: | ---: | --- |
| 1 | completed | PASS | 0/15 | 15/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-084900.json |
| 2 | completed | PASS | 0/15 | 15/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-085126.json |
| 3 | completed | PASS | 0/15 | 15/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-085331.json |
| 4 | completed | PASS | 0/15 | 15/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-085547.json |
| 5 | completed | PASS | 0/15 | 15/15 | 15/15 | 1.0000 | 1.0000 | llm-memory-eval-20260601-085755.json |

