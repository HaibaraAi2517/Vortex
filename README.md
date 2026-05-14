# Vortex

当前仓库的集成测试有两条路径，但在 Windows 上推荐使用 `docker compose` 绕行方案。

## Integration Tests

### 推荐：`DockerComposeIT`

适用场景：
- Windows + Docker Desktop
- 当前 Docker Engine 版本下，Java `docker-java` / Testcontainers 对 Windows named pipe 兼容性不稳定

启动依赖容器：

```powershell
docker --host tcp://localhost:2375 compose up -d
```

执行集成测试：

```powershell
mvn verify -pl vortex-app -am -Pintegration "-Dit.test=DockerComposeIT"
```

当前这条路径已验证通过，覆盖：
- memory store -> evict -> L2 recall -> L1 re-admission
- task checkpoint -> recover
- recall feedback -> adaptive weight evolution

如果直接使用 `docker compose` 命令遇到 Windows named pipe 权限问题，可继续显式指定：

```powershell
docker --host tcp://localhost:2375 compose ps
```

前提是 Docker Desktop 已开启：

- `Settings -> General -> Expose daemon on tcp://localhost:2375 without TLS`

### 备用：`FullLifecycleIT`

`FullLifecycleIT` 仍然保留为 Testcontainers 路径：

```powershell
mvn verify -pl vortex-app -am -Pintegration "-Dit.test=FullLifecycleIT"
```

但在当前 Windows + Docker Desktop + Docker Engine 29 环境下，这条路径可能因为上游 `docker-java` / Testcontainers 对 named pipe 的兼容性问题失败。更稳的做法是：

- 在 Linux / WSL2 里运行
- 或暂时使用上面的 `DockerComposeIT`

## Notes

- `DockerComposeIT` 连接的是预启动容器，不负责容器生命周期管理。
- 语义分页元数据现在使用可稳定序列化的并发集合，重启加载不再依赖 `Collections$SetFromMap` 这种 Kryo 不兼容类型。
