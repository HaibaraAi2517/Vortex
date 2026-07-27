# Roadmap

Vortex is in alpha. The roadmap is organized around problems to solve rather
than a promise of fixed dates.

## Make The Runtime Easier To Try

Current problem: a new user can run the no-key demo, but the project still needs
more short, inspectable examples for common integration paths.

Done:

- Add a Spring AI ChatClient advisor example under `examples/spring-ai-integration/`.
- Record a short quickstart demo GIF and transcript under `docs/assets/`.

Planned work:

- Add one MCP or LangChain4j integration example.
- Keep quickstart commands tested on Windows PowerShell and Bash.

## Make Retrieval Quality Easier To Evaluate

Current problem: deterministic internal workload evidence exists, but public
dataset promotion is not ready to cite.

Planned work:

- Add a reviewed public-dataset evaluation path.
- Commit dataset conversion notes, model/base URL disclosure, reports, and
  boundary notes before promoting any public metric.
- Add small fixtures that explain where hybrid recall helps and where it does
  not.

## Harden Runtime Recovery

Current problem: checkpoint/WAL recovery has deterministic coverage, but
production process-manager and distributed deployment assumptions remain out of
scope.

Planned work:

- Expand recovery examples around worker restart and duplicate execution IDs.
- Document deployment patterns for external process managers.
- Add more concurrency and interruption cases as the task-state API stabilizes.

## Prepare For Real Deployments

Current problem: Vortex exposes useful runtime primitives, but production
platform features are not complete.

Planned work:

- Define authentication and authorization boundaries.
- Add rate-limit and tenant-isolation design notes before implementation.
- Improve operational dashboards and SLO documentation.
- Clarify backup, restore, and migration procedures for Milvus and MinIO.

## Improve Contributor Experience

Current problem: the codebase has multiple modules, benchmark harnesses, and
governance scripts that need clearer entry points.

Planned work:

- Keep module ownership and architecture notes current.
- Add focused contributor docs for memory, retrieval, storage, and recovery.
- Make CI failures easier to map back to local commands.
