# Memory Health Grafana Queries

本文件提供 memory health v2 升级后的最小查询集合，供 Grafana 面板迁移时直接使用。

## Checkpoint Recovery

```promql
vortex_hmc_slo_checkpoint_recovery_success_rate
```

用途：展示 checkpoint / WAL 恢复链路的成功率。

## Persistence Recovery

```promql
vortex_hmc_slo_persistence_success_rate
```

用途：展示 fragment 持久化与 DLQ 最终落盘链路的成功率。

## Durability Overview

```promql
vortex_hmc_slo_durability_success_rate
```

用途：总览 durability，但不替代链路级告警。

## Legacy Compatibility

```promql
vortex_hmc_slo_recovery_success_rate
```

用途：仅用于迁移窗口内的旧面板平滑切换。新面板不要再把它作为 checkpoint 告警的主图。
