package com.vortex.app.eval;

import com.vortex.kernel.snapshot.SnapshotService;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;

@Component
public class RuntimeRecoveryTaskCacheEvictor {

    private static final String EVICT_METHOD = "evictFromCacheForTest";

    public void evict(SnapshotService snapshotService, String taskId) {
        Method method = ReflectionUtils.findMethod(SnapshotService.class, EVICT_METHOD, String.class);
        if (method == null) {
            throw new IllegalStateException("SnapshotService cache eviction hook is unavailable");
        }
        ReflectionUtils.makeAccessible(method);
        ReflectionUtils.invokeMethod(method, snapshotService, taskId);
    }
}

