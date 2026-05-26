package com.vortex.kernel.snapshot;

import com.vortex.common.model.ActionLogEntry;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.kernel.hmc.MemorySloTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class SnapshotRecoveryHealthLoggingTest {

    @TempDir
    Path tempDir;

    @Test
    void recoveryFailureLogsUnifiedDurabilityEnvelope(CapturedOutput output) {
        SnapshotServiceTest.FakeL3ColdStore store = new SnapshotServiceTest.FakeL3ColdStore();
        String walDir = tempDir.resolve("wal").toString();
        ActionLogWriter walWriter = new ActionLogWriter(walDir);
        ActionLogReader walReader = new ActionLogReader(walDir);
        ActionLogTruncator walTruncator = new ActionLogTruncator(walWriter, walReader, walDir);
        DirtySetTracker dirtySetTracker = new DirtySetTracker();
        IncrementalCheckpointManager checkpointManager = new IncrementalCheckpointManager(store, dirtySetTracker, 10);
        CheckpointScheduler scheduler = new CheckpointScheduler(50, 60_000, false);
        BranchManager branchManager = new BranchManager(10, new BranchMergeConflictDetector());
        DotGraphExporter dotExporter = new DotGraphExporter();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CheckpointRecoveryMetrics checkpointRecoveryMetrics = new CheckpointRecoveryMetrics(meterRegistry);
        MemorySloTracker memorySloTracker = new MemorySloTracker(meterRegistry);
        memorySloTracker.bind();

        ApplicationEventPublisher eventPublisher = event -> {};
        CheckpointLifecycleManager lifecycleManager = new CheckpointLifecycleManager(store, 20, 7, 48);
        TaskLifecycleManager taskLifecycleMgr = new TaskLifecycleManager(
                store, checkpointManager, lifecycleManager, walWriter, walReader, walTruncator,
                scheduler, dirtySetTracker, memorySloTracker, null, null);
        DagMutationService dagMutationSvc = new DagMutationService(
                walWriter, dirtySetTracker, scheduler, eventPublisher, branchManager, taskLifecycleMgr);
        RecoveryEngine recoveryEng = new RecoveryEngine(
                walReader, walWriter, checkpointManager, checkpointRecoveryMetrics, memorySloTracker,
                branchManager, scheduler, taskLifecycleMgr);
        SnapshotService service = new SnapshotService(
                taskLifecycleMgr, dagMutationSvc, recoveryEng,
                branchManager, dotExporter, walWriter, walTruncator,
                checkpointManager, lifecycleManager, scheduler, checkpointRecoveryMetrics, memorySloTracker);
        taskLifecycleMgr.setSnapshotService(service);
        taskLifecycleMgr.setRecoveryEngine(recoveryEng);

        var task = service.createTask("recovery logging", "ns");
        service.appendNode(task.getTaskId(), "THOUGHT", "before checkpoint");
        String checkpointId = service.checkpoint(task.getTaskId());
        walWriter.append(task.getTaskId(), ActionLogEntry.OperationType.UPDATE_CONTEXT, "{corrupt");

        assertThatThrownBy(() -> service.recover(task.getTaskId(), checkpointId))
                .isInstanceOf(CheckpointRecoveryException.class);

        assertThat(output.toString()).contains("memory_durability_degraded");
        assertThat(output.toString()).contains("healthCode=checkpoint_recovery_success_rate_low");
        assertThat(output.toString()).contains("chain=checkpoint-recovery");
        assertThat(output.toString()).contains("phase=wal-replay");
        assertThat(output.toString()).contains("failureReason=WAL_STATE_APPLY_FAILED");
    }

    @Test
    void checkpointRetentionDeleteFailureLogsUnifiedDurabilityEnvelope(CapturedOutput output) {
        SnapshotServiceTest.FakeL3ColdStore store = new SnapshotServiceTest.FakeL3ColdStore() {
            @Override
            public void deleteCheckpoint(CheckpointMetadata meta) {
                throw new IllegalStateException("simulated retention delete failure");
            }
        };

        CheckpointLifecycleManager lifecycleManager = new CheckpointLifecycleManager(store, 1, 7, 48);
        List<CheckpointMetadata> checkpoints = List.of(
                CheckpointMetadata.builder()
                        .taskId("task-1")
                        .checkpointId("cp-1")
                        .createdAt(Instant.now().minusSeconds(7200))
                        .build(),
                CheckpointMetadata.builder()
                        .taskId("task-1")
                        .checkpointId("cp-2")
                        .createdAt(Instant.now())
                        .build());

        lifecycleManager.applyRetention("task-1", checkpoints);

        assertThat(output.toString()).contains("memory_durability_degraded");
        assertThat(output.toString()).contains("healthCode=checkpoint_recovery_success_rate_low");
        assertThat(output.toString()).contains("chain=checkpoint-recovery");
        assertThat(output.toString()).contains("phase=checkpoint-retention-delete");
        assertThat(output.toString()).contains("checkpointId=cp-1");
    }
}
