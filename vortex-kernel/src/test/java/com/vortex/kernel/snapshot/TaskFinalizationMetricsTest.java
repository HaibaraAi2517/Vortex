package com.vortex.kernel.snapshot;

import com.vortex.common.model.TaskState;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TaskFinalizationMetricsTest {

    @TempDir
    Path tempDir;

    @Test
    void finalCheckpointFailure_updatesPendingFinalizationMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SnapshotServiceTest.FailingCheckpointStore store =
                new SnapshotServiceTest.FailingCheckpointStore(new SnapshotServiceTest.FakeL3ColdStore());
        SnapshotService service = newService(store, meterRegistry, null);

        TaskState task = service.createTask("metric finalization pending", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "finalize");
        store.failOnCheckpointWriteNumber(2);

        try {
            service.completeTask(task.getTaskId());
        } catch (IllegalStateException ignored) {
            // expected
        }

        assertThat(gaugeValue(meterRegistry, "vortex.task.finalization.pending")).isEqualTo(1.0);
        assertThat(gaugeValue(meterRegistry, "vortex.task.finalization.cleanup.pending")).isEqualTo(0.0);
        assertThat(counterValue(meterRegistry, "vortex.task.finalization.transitions.total", "phase", "pending_finalization"))
                .isEqualTo(1.0);
    }

    @Test
    void cleanupFailure_updatesPendingCleanupMetrics_and_retryClearsGauge() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SnapshotServiceTest.FakeL3ColdStore store = new SnapshotServiceTest.FakeL3ColdStore();
        SnapshotServiceTest.FailingActionLogWriter walWriter =
                new SnapshotServiceTest.FailingActionLogWriter(tempDir.resolve("wal").toString());
        SnapshotService service = newService(store, meterRegistry, walWriter);

        TaskState task = service.createTask("metric cleanup pending", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "cleanup");
        walWriter.failNextClose();

        try {
            service.completeTask(task.getTaskId());
        } catch (IllegalStateException ignored) {
            // expected
        }

        assertThat(gaugeValue(meterRegistry, "vortex.task.finalization.pending")).isEqualTo(0.0);
        assertThat(gaugeValue(meterRegistry, "vortex.task.finalization.cleanup.pending")).isEqualTo(1.0);
        assertThat(counterValue(meterRegistry, "vortex.task.finalization.transitions.total", "phase", "pending_cleanup"))
                .isEqualTo(1.0);

        service.completeTask(task.getTaskId());

        assertThat(gaugeValue(meterRegistry, "vortex.task.finalization.cleanup.pending")).isEqualTo(0.0);
    }

    private SnapshotService newService(
            SnapshotServiceTest.FakeL3ColdStore store,
            SimpleMeterRegistry meterRegistry,
            ActionLogWriter customWalWriter) {
        String walDir = tempDir.resolve("wal").toString();
        ActionLogWriter walWriter = customWalWriter != null ? customWalWriter : new ActionLogWriter(walDir);
        ActionLogReader walReader = new ActionLogReader(walDir);
        ActionLogTruncator walTruncator = new ActionLogTruncator(walWriter, walReader, walDir);
        DirtySetTracker dirtySetTracker = new DirtySetTracker();
        IncrementalCheckpointManager checkpointManager = new IncrementalCheckpointManager(store, dirtySetTracker, 10);
        CheckpointLifecycleManager lifecycleManager = new CheckpointLifecycleManager(store, 20, 7, 48);
        CheckpointScheduler scheduler = new CheckpointScheduler(50, 60_000, false);
        var conflictDetector = new BranchMergeConflictDetector();
        var branchManager = new BranchManager(10, conflictDetector);
        var dotExporter = new DotGraphExporter();
        var checkpointRecoveryMetrics = new CheckpointRecoveryMetrics(meterRegistry);
        var memorySloTracker = new com.vortex.kernel.hmc.MemorySloTracker(meterRegistry);
        memorySloTracker.bind();
        var taskFinalizationMetrics = new TaskFinalizationMetrics(meterRegistry);

        ApplicationEventPublisher eventPublisher = event -> {};
        TaskLifecycleManager taskLifecycleMgr = new TaskLifecycleManager(
                store, checkpointManager, lifecycleManager, walWriter, walReader, walTruncator,
                scheduler, dirtySetTracker, memorySloTracker, taskFinalizationMetrics, null, null);
        DagMutationService dagMutationSvc = new DagMutationService(
                walWriter, dirtySetTracker, scheduler, eventPublisher, branchManager, taskLifecycleMgr);
        RecoveryEngine recoveryEng = new RecoveryEngine(
                walReader, walWriter, checkpointManager, checkpointRecoveryMetrics, memorySloTracker,
                branchManager, scheduler, taskLifecycleMgr);

        SnapshotService snapshotService = new SnapshotService(
                taskLifecycleMgr, dagMutationSvc, recoveryEng,
                branchManager, dotExporter, walWriter, walTruncator,
                checkpointManager, lifecycleManager, scheduler, checkpointRecoveryMetrics, memorySloTracker);
        taskLifecycleMgr.setSnapshotService(snapshotService);
        taskLifecycleMgr.setRecoveryEngine(recoveryEng);
        return snapshotService;
    }

    private double gaugeValue(SimpleMeterRegistry meterRegistry, String meterName) {
        var gauge = meterRegistry.find(meterName).gauge();
        return gauge == null ? 0.0 : gauge.value();
    }

    private double counterValue(SimpleMeterRegistry meterRegistry, String meterName, String... tags) {
        Search search = meterRegistry.find(meterName);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        var counter = search.counter();
        return counter == null ? 0.0 : counter.count();
    }
}
