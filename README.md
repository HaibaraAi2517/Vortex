# Vortex

Vortex 是一个面向 AI Agent 的记忆、分页、checkpoint/recover 与反馈学习内核。

## Stable Workflow

当前仓库已经收口到一条默认可执行的稳定回归路径：

```bash
mvn verify -pl vortex-app -am
```

这条命令会自动完成：

- 编译并运行模块级单元测试
- 启动 `docker-compose.yml` 中的 `Redis / etcd / MinIO / Milvus`
- 执行 `vortex-app` 的默认集成测试闭环
- 在测试结束后自动关闭 compose 依赖

前提：

- 本机 Docker Desktop / Docker daemon 已启动
- `docker compose` 命令可用

## What The Default Verify Covers

默认集成回归重点覆盖：

- memory store -> evict -> L2 recall -> L1 re-admission
- task checkpoint -> recover
- checkpoint retention / delta chain recoverability
- recall feedback -> adaptive weight evolution
- compose 环境下的完整 demo 故事线

## Manual Demo

如果你想手动看一遍 API 演示：

1. 启动应用

```bash
mvn spring-boot:run -pl vortex-app
```

2. 在另一个终端执行

```bash
bash ops/demo.sh
```

## Optional Testcontainers Path

仓库仍保留 `FullLifecycleIT` 作为附加验证路径，但它不属于默认稳定回归集合。

显式执行方式：

```bash
mvn --% verify -pl vortex-app -am -Dit.test=FullLifecycleIT -Drun.full.lifecycle.it=true
```

适用场景：

- 你明确想验证 Testcontainers 路径
- 当前机器上的 Docker / Testcontainers 兼容性已经确认稳定

## Ops

- [compose-verify.md](ops/compose-verify.md)
- [compose-up.sh](ops/compose-up.sh)
- [demo.sh](ops/demo.sh)
