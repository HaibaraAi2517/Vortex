# Roadmap

Vortex is in alpha. The roadmap is organized around problems to solve rather
than a promise of fixed dates.

## Make The Runtime Easier To Try

Current problem: a new user can run the no-key demo, but the project still needs
more short, inspectable examples for common integration paths.

Done:

- Add a Spring AI ChatClient advisor example under `examples/spring-ai-integration/`.
- Add LangChain4j adapters and a runnable integration example under
  `examples/langchain4j-integration/`.
- Record a short quickstart demo GIF and transcript under `docs/assets/`.

Planned work:

- Add one MCP integration example.
- Keep quickstart commands tested on Windows PowerShell and Bash.

## Make Retrieval Quality Easier To Evaluate

Current problem: case-isolated retrieval evidence on the official LongMemEval
oracle is committed and safe to cite within its documented scope, but it
measures oracle-fragment retrieval rather than end-to-end answer quality or
production traffic.

Done:

- Add a reviewed, case-isolated LongMemEval retrieval evaluation path.
- Commit dataset conversion notes, hashes, configuration, statistical analysis,
  and boundary notes with the final evidence report.

Planned work:

- Add an end-to-end real-LLM evaluation that measures answer quality separately
  from oracle-fragment retrieval.
- Add domain-specific fixtures that explain where hybrid recall helps and where
  it does not.
- Validate retrieval quality on traffic-shaped workloads before making online
  production claims.

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
identity, distributed policy enforcement, and capacity validation are not
complete.

Done:

- Define a trusted-environment boundary with one shared Bearer token, namespace
  allowlists, loopback-only Quickstart publishing, request-size limits,
  in-process rate limiting, and structured audit events.
- Document deployment, backup, restore, migration, upgrade, and rollback
  procedures, and exercise the release drills against Milvus, MinIO, Redis, and
  the Vortex application volume.

Planned work:

- Add production OIDC and/or mTLS identities instead of a shared token.
- Add per-tenant identities, fine-grained RBAC, and stronger namespace isolation.
- Add distributed rate limiting and audit aggregation.
- Improve operational dashboards and SLO documentation.
- Validate capacity and failure behavior on long-running, traffic-shaped workloads.

## Improve Contributor Experience

Current problem: the codebase has multiple modules, benchmark harnesses, and
governance scripts that need clearer entry points.

Planned work:

- Keep module ownership and architecture notes current.
- Add focused contributor docs for memory, retrieval, storage, and recovery.
- Make CI failures easier to map back to local commands.
