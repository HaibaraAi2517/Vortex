"""Run after starting Vortex and installing ./sdk/python. Safe to run repeatedly."""
from vortex_memory import VortexClient, VortexConflictError

memory = VortexClient.from_env()
key = "demo.user.home.city"
current = memory.get_fact(key)
beijing = memory.remember(key, "我住北京", expected_revision=current["revision"] if current else 0)
shanghai = memory.remember(key, "我已经搬到上海了", expected_revision=beijing["revision"])
print("Current memory:", memory.context("用户现在住在哪里？"))
try:
    memory.remember(key, "旧请求想写回北京", expected_revision=beijing["revision"])
except VortexConflictError:
    print("Stale update correctly rejected.")
assert memory.get_fact(key)["revision"] == shanghai["revision"]
print("Durable revision:", shanghai["revision"])
print("Restart Vortex and call get_fact again to inspect the same revision.")
