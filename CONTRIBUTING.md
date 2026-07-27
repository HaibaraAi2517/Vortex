# Contributing To Vortex

Thanks for taking time to improve Vortex. This project values reproducible
engineering evidence over broad claims, so contributions should keep docs,
tests, and benchmark wording aligned.

## Development Setup

Prerequisites:

- JDK 21
- Maven 3.9+
- Docker Desktop or Docker Compose

Start local dependencies:

```powershell
docker compose up -d --wait
```

Build the app:

```powershell
mvn -pl vortex-app -am -DskipTests package
```

Run the focused quickstart stack:

```powershell
docker compose -f docker-compose.quickstart.yml up --build -d
```

## Before Opening A Pull Request

Run the narrowest checks that cover your change, then document what you ran in
the PR body.

Common checks:

```powershell
mvn -B test -pl vortex-common,vortex-kernel,vortex-storage -am
mvn -B verify -pl vortex-app -am
```

For docs-only changes, run a link/format pass and make sure any command added
to README or docs has been executed locally.

## Benchmark And Claim Policy

- Do not add benchmark numbers unless the evidence artifact or runbook is also
  committed.
- Do not rewrite benchmark scope into production guarantees.
- Do not cite test counts or coverage percentages unless the latest report is
  reproducible and committed or clearly linked.
- Keep public wording consistent with [docs/benchmark.md](docs/benchmark.md).

## Commit Style

Use Conventional Commits where practical:

```text
docs: add quickstart comparison guide
feat: add memory recall endpoint option
test: cover recovery idempotency path
fix: handle empty recall candidate list
```

Keep commits scoped. Avoid mixing unrelated code, generated output, and docs in
one commit.

## Pull Request Checklist

- The change has a clear problem statement.
- Commands added to public docs have been run locally.
- Benchmark or performance claims link to evidence.
- New behavior has focused tests or a clear reason why tests were not added.
- README, docs, and release notes do not overstate alpha-stage capabilities.
