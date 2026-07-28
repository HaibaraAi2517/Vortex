# Vortex Test And Coverage Evidence - 2026-07-28

This note records a clean-worktree verification of the test-count and line
coverage claim for Vortex. It is intended to keep resume/public wording tied to
repository evidence rather than local memory.

## Scope

- Commit under test: `41db524 build: enable jacoco coverage reports`
- Date: 2026-07-28
- Command:

```powershell
mvn -B org.jacoco:jacoco-maven-plugin:0.8.15:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.15:report
```

The command was run from a temporary clean git worktree at commit `41db524`.
`git status --short` in that worktree was empty after the run, aside from
ignored Maven `target/` outputs.

The run includes:

- Surefire unit tests in `vortex-common`, `vortex-storage`, `vortex-kernel`,
  and `vortex-app`.
- Failsafe integration tests in `vortex-app`.
- Docker Compose-backed app integration coverage through the app failsafe
  suite.

## Result

Maven finished with:

```text
BUILD SUCCESS
```

Fresh XML reports were generated under:

- `*/target/surefire-reports/TEST-*.xml`
- `*/target/failsafe-reports/TEST-*.xml`
- `*/target/site/jacoco/jacoco.xml`

## Test Count

| Module | Report Type | Tests | Failures | Errors | Skipped |
| --- | --- | ---: | ---: | ---: | ---: |
| `vortex-common` | surefire | 37 | 0 | 0 | 0 |
| `vortex-storage` | surefire | 21 | 0 | 0 | 0 |
| `vortex-kernel` | surefire | 300 | 0 | 0 | 0 |
| `vortex-app` | surefire | 141 | 0 | 0 | 0 |
| `vortex-app` | failsafe | 13 | 0 | 0 | 0 |
| **Total** |  | **512** | **0** | **0** | **0** |

## Line Coverage

Jacoco module line counters:

| Module | Covered | Missed | Total | Line Coverage |
| --- | ---: | ---: | ---: | ---: |
| `vortex-app` | 4270 | 977 | 5247 | 81.38% |
| `vortex-common` | 462 | 278 | 740 | 62.43% |
| `vortex-kernel` | 5294 | 1843 | 7137 | 74.18% |
| `vortex-storage` | 195 | 488 | 683 | 28.55% |
| **Total** | **10221** | **3586** | **13807** | **74.03%** |

## Safe Wording

Allowed:

> At commit `41db524`, the full Maven `verify` path passed `512` tests with
> `0` failures/errors/skips, and Jacoco reported `74.03%` aggregate line
> coverage across `vortex-app`, `vortex-common`, `vortex-kernel`, and
> `vortex-storage`.

Resume-short version:

> 500+ automated tests and 74% aggregate Jacoco line coverage, verified by the
> repository `verify` path.

## Boundaries

- Do not quote the dirty-worktree `514` test count as public evidence. That
  count included an untracked local `HybridRecallRerankerTest`.
- Do not quote a stronger coverage number than `74.03%` unless a newer clean
  worktree report is generated and recorded.
- The generated XML/HTML reports live under Maven `target/` directories and are
  ignored. This runbook is the tracked summary evidence.
- This is a test/coverage evidence note only; it is not a benchmark latency,
  recall, LongMemEval, or production-quality claim.
