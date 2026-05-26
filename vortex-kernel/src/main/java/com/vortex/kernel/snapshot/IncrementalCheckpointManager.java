package com.vortex.kernel.snapshot;

import com.vortex.common.model.*;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.storage.api.CheckpointStoreException;
import com.vortex.storage.api.L3ColdStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages incremental (differential) checkpointing for task states.
 *
 * Strategy:
 * - First checkpoint for a task is always FULL
 * - Subsequent checkpoints are DELTA if changes are small enough
 * - After maxDeltasBeforeFull, a new FULL checkpoint is created (compaction)
 * - Recovery assembles: FULL → DELTA₁ → DELTA₂ → ... → current
 */
@Slf4j
@Component
public class IncrementalCheckpointManager {

    private final L3ColdStore l3;
    private final DirtySetTracker dirtySetTracker;
    private final KryoSerializer kryoSerializer;
    private final int maxDeltasBeforeFull;

    /** Per-task index: taskId → ordered list of checkpoint metadata. */
    private final ConcurrentHashMap<String, List<CheckpointMetadata>> checkpointsByTask = new ConcurrentHashMap<>();

    public IncrementalCheckpointManager(
            L3ColdStore l3,
            DirtySetTracker dirtySetTracker,
            @Value("${vortex.kernel.snapshot.checkpoint.max-deltas-before-full:10}") int maxDeltasBeforeFull) {
        this.l3 = l3;
        this.dirtySetTracker = dirtySetTracker;
        this.kryoSerializer = new KryoSerializer();
        this.maxDeltasBeforeFull = maxDeltasBeforeFull;
    }

    /**
     * Decide whether to create a FULL or DELTA checkpoint, then create it.
     *
     * @param state  the current task state to checkpoint
     * @param walSeq the current WAL sequence number
     * @return the metadata for the created checkpoint
     */
    public CheckpointMetadata createCheckpoint(TaskState state, long walSeq) {
        List<CheckpointMetadata> history = getOrLoadHistory(state.getTaskId());

        CheckpointMetadata previousCheckpoint = history.isEmpty() ? null : history.getLast();

        // Find the last full checkpoint (might not be the immediate predecessor)
        CheckpointMetadata lastFull = history.stream()
                .filter(m -> m.getType() == CheckpointMetadata.CheckpointType.FULL)
                .reduce((first, second) -> second) // last element
                .orElse(null);

        long deltasSinceLastFull = deltasSinceLastFull(history);

        DirtySetTracker.DirtySnapshot dirty = dirtySetTracker.getAndClear(state.getTaskId());
        try {
            boolean shouldBeFull = history.isEmpty()
                    || deltasSinceLastFull >= maxDeltasBeforeFull
                    || lastFull == null;

            CheckpointMetadata meta;
            if (shouldBeFull) {
                meta = createFullCheckpoint(state, walSeq);
            } else {
                meta = createDeltaCheckpoint(state, dirty, previousCheckpoint.getCheckpointId(), walSeq);
            }

            meta.setNodeCount(state.getGraph().nodeCount());
            meta.setEdgeCount(state.getGraph().edgeCount());
            meta.setBranchId(state.getCurrentBranchId());
            history.add(meta);

            log.info("Checkpoint created taskId={} checkpointId={} type={} seqNo={} nodes={} edges={}",
                    state.getTaskId(), meta.getCheckpointId(), meta.getType(),
                    meta.getSequenceNumber(), meta.getNodeCount(), meta.getEdgeCount());

            return meta;
        } catch (RuntimeException e) {
            dirtySetTracker.restore(state.getTaskId(), dirty);
            throw e;
        }
    }

    /**
     * Create a full checkpoint of the entire task state.
     */
    public CheckpointMetadata createFullCheckpoint(TaskState state, long walSeq) {
        String checkpointId = UUID.randomUUID().toString();
        // Set checkpoint ID on state so saveCheckpoint uses the same ID
        state.setLatestCheckpointId(checkpointId);
        state.setLastCheckpointAt(java.time.Instant.now());

        CheckpointMetadata meta = CheckpointMetadata.builder()
                .checkpointId(checkpointId)
                .taskId(state.getTaskId())
                .sequenceNumber(walSeq)
                .type(CheckpointMetadata.CheckpointType.FULL)
                .build();

        return l3.saveCheckpointWithMetadata(state, meta);
    }

    /**
     * Create a delta checkpoint containing only the changes since the base checkpoint.
     */
    public CheckpointMetadata createDeltaCheckpoint(
            TaskState state,
            DirtySetTracker.DirtySnapshot dirty,
            String baseCheckpointId,
            long walSeq) {

        String checkpointId = UUID.randomUUID().toString();

        // Extract changed nodes, new edges, context diff from the current state
        Set<DagNode> changedNodes = new HashSet<>();
        for (String nodeId : dirty.nodeIds()) {
            state.getGraph().getNode(nodeId).ifPresent(changedNodes::add);
        }

        Set<DagEdge> newEdges = new HashSet<>();
        synchronized (state.getGraph().getEdges()) {
            for (DagEdge edge : state.getGraph().getEdges()) {
                if (dirty.edgeIds().contains(edge.getEdgeId())) {
                    newEdges.add(edge);
                }
            }
        }

        Map<String, String> contextDiff = new HashMap<>();
        for (String key : dirty.contextKeys()) {
            String value = state.getContext().get(key);
            contextDiff.put(key, value); // null value means deleted
        }

        CheckpointDelta delta = new CheckpointDelta(
                baseCheckpointId, walSeq,
                changedNodes,
                newEdges,
                contextDiff,
                Set.copyOf(dirty.deletedNodeIds()),
                state.getCurrentNodeId(),
                state.getCurrentBranchId(),
                new ArrayList<>(state.getBranches()),
                state.getStatus(),
                state.getFinalizationStatus());

        byte[] compressed = kryoSerializer.serializeCompressed(delta);
        String l3Key = checkpointDataKey(state.getTaskId(), checkpointId);

        CheckpointMetadata meta = CheckpointMetadata.builder()
                .checkpointId(checkpointId)
                .taskId(state.getTaskId())
                .sequenceNumber(walSeq)
                .type(CheckpointMetadata.CheckpointType.DELTA)
                .baseCheckpointId(baseCheckpointId)
                .sizeBytes(compressed.length)
                .compressed(true)
                .compressionAlgorithm("gzip")
                .l3Key(l3Key)
                .build();

        log.debug("Delta checkpoint created: base={}, {} changed nodes, {} new edges, {} context changes",
                baseCheckpointId, changedNodes.size(), newEdges.size(), contextDiff.size());

        return l3.saveCheckpointBytesWithMetadata(compressed, meta);
    }

    /**
     * Assemble a task state by applying a chain of deltas onto a full checkpoint.
     */
    public TaskState assemble(TaskState fullBase, List<CheckpointDelta> deltas) {
        TaskState current = fullBase;
        for (CheckpointDelta delta : deltas) {
            current = applyDelta(current, delta);
        }
        return current;
    }

    private TaskState applyDelta(TaskState state, CheckpointDelta delta) {
        // Apply node changes
        for (DagNode node : delta.getChangedNodes()) {
            state.getGraph().addNode(node);
        }

        // Apply edge changes
        for (DagEdge edge : delta.getNewEdges()) {
            try {
                state.getGraph().addEdge(edge);
            } catch (Exception e) {
                if (isDuplicateEdge(state, edge)) {
                    log.debug("Skipped duplicate delta edge {}", edge.getEdgeId());
                    continue;
                }
                throw new CheckpointRecoveryException(
                        CheckpointRecoveryFailureReason.DELTA_STATE_APPLY_FAILED,
                        state.getTaskId(),
                        state.getLatestCheckpointId(),
                        "Failed to apply delta edge taskId=" + state.getTaskId() + " edgeId=" + edge.getEdgeId(),
                        e);
            }
        }

        // Apply context diff
        for (Map.Entry<String, String> entry : delta.getContextDiff().entrySet()) {
            if (entry.getValue() == null) {
                state.getContext().remove(entry.getKey());
            } else {
                state.getContext().put(entry.getKey(), entry.getValue());
            }
        }

        // Apply deletions
        for (String nodeId : delta.getDeletedNodeIds()) {
            state.getGraph().removeNode(nodeId);
        }

        state.setCurrentNodeId(delta.getCurrentNodeId());
        state.setCurrentBranchId(delta.getCurrentBranchId());
        state.setBranches(new ArrayList<>(delta.getBranches()));
        state.setStatus(delta.getStatus());
        state.setFinalizationStatus(delta.getFinalizationStatus() != null
                ? delta.getFinalizationStatus()
                : TaskState.TaskFinalizationStatus.NONE);
        state.setWalSequenceNumber(delta.getSequenceNumber());

        return state;
    }

    /**
     * List all checkpoint metadata for a task.
     */
    public List<CheckpointMetadata> listCheckpoints(String taskId) {
        return List.copyOf(getOrLoadHistory(taskId));
    }

    /**
     * Remove all tracking for a completed task.
     */
    public void removeTask(String taskId) {
        checkpointsByTask.remove(taskId);
        dirtySetTracker.remove(taskId);
    }

    /**
     * Get the delta count since the last full checkpoint.
     */
    public long deltasSinceLastFull(String taskId) {
        List<CheckpointMetadata> history = reloadTask(taskId);
        return deltasSinceLastFull(history);
    }

    /**
     * Reload checkpoint metadata for a task from cold storage.
     */
    public List<CheckpointMetadata> reloadTask(String taskId) {
        List<CheckpointMetadata> reloaded = new ArrayList<>(loadCheckpointMetadata(taskId));
        reloaded.sort(Comparator.comparing(CheckpointMetadata::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<CheckpointMetadata> history = Collections.synchronizedList(reloaded);
        checkpointsByTask.put(taskId, history);
        return List.copyOf(history);
    }

    public Optional<CheckpointMetadata> latestCheckpoint(String taskId) {
        List<CheckpointMetadata> history = getOrLoadHistory(taskId);
        if (history.isEmpty()) {
            return Optional.empty();
        }
        return history.stream().reduce((first, second) -> second);
    }

    private List<CheckpointMetadata> getOrLoadHistory(String taskId) {
        return checkpointsByTask.computeIfAbsent(taskId,
                key -> Collections.synchronizedList(new ArrayList<>(loadCheckpointMetadata(key))));
    }

    private long deltasSinceLastFull(List<CheckpointMetadata> history) {
        long count = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            CheckpointMetadata meta = history.get(i);
            if (meta.getType() == CheckpointMetadata.CheckpointType.FULL) {
                break;
            }
            if (meta.getType() == CheckpointMetadata.CheckpointType.DELTA) {
                count++;
            }
        }
        return count;
    }

    public Optional<TaskState> loadFullCheckpoint(String taskId, String checkpointId) {
        try {
            return l3.loadCheckpoint(taskId + "/" + checkpointId);
        } catch (CheckpointStoreException e) {
            throw mapCheckpointStoreException(
                    e,
                    taskId,
                    checkpointId,
                    CheckpointRecoveryFailureReason.CHECKPOINT_STORAGE_READ_FAILED,
                    CheckpointRecoveryFailureReason.FULL_CHECKPOINT_PAYLOAD_INVALID,
                    "Failed to load full checkpoint taskId=" + taskId + " checkpointId=" + checkpointId);
        } catch (IllegalStateException e) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.CHECKPOINT_STORAGE_READ_FAILED,
                    taskId,
                    checkpointId,
                    "Failed to load full checkpoint taskId=" + taskId + " checkpointId=" + checkpointId,
                    e);
        }
    }

    public Optional<CheckpointDelta> loadDeltaCheckpoint(String taskId, String checkpointId) {
        byte[] data;
        try {
            data = l3.getBytes(checkpointDataKey(taskId, checkpointId));
        } catch (CheckpointStoreException e) {
            throw mapCheckpointStoreException(
                    e,
                    taskId,
                    checkpointId,
                    CheckpointRecoveryFailureReason.CHECKPOINT_STORAGE_READ_FAILED,
                    CheckpointRecoveryFailureReason.DELTA_PAYLOAD_INVALID,
                    "Failed to load delta checkpoint taskId=" + taskId + " checkpointId=" + checkpointId);
        } catch (IllegalStateException e) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.CHECKPOINT_STORAGE_READ_FAILED,
                    taskId,
                    checkpointId,
                    "Failed to load delta checkpoint taskId=" + taskId + " checkpointId=" + checkpointId,
                    e);
        }
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(kryoSerializer.deserializeCompressed(data, CheckpointDelta.class));
        } catch (Exception ex) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.DELTA_PAYLOAD_INVALID,
                    taskId,
                    checkpointId,
                    "Failed to deserialize delta checkpoint taskId=" + taskId + " checkpointId=" + checkpointId,
                    ex);
        }
    }

    public CheckpointRecoveryResult recoverCheckpoint(String taskId, String checkpointId) {
        List<CheckpointMetadata> history = reloadTask(taskId);
        Map<String, CheckpointMetadata> byId = new HashMap<>();
        for (CheckpointMetadata meta : history) {
            byId.put(meta.getCheckpointId(), meta);
        }

        CheckpointMetadata target = byId.get(checkpointId);
        if (target == null) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.CHECKPOINT_METADATA_MISSING,
                    taskId,
                    checkpointId,
                    "Checkpoint metadata not found for taskId=" + taskId + " checkpointId=" + checkpointId);
        }

        if (target.getType() == CheckpointMetadata.CheckpointType.FULL) {
            TaskState state = loadFullCheckpoint(taskId, checkpointId)
                    .orElseThrow(() -> new CheckpointRecoveryException(
                            CheckpointRecoveryFailureReason.FULL_CHECKPOINT_MISSING,
                            taskId,
                            checkpointId,
                            "Full checkpoint not found in L3: taskId=" + taskId + " checkpointId=" + checkpointId));
            return new CheckpointRecoveryResult(state, CheckpointRecoveryMode.FULL, 0, target);
        }

        LinkedList<CheckpointMetadata> deltaChain = new LinkedList<>();
        CheckpointMetadata current = target;
        while (current.getType() == CheckpointMetadata.CheckpointType.DELTA) {
            deltaChain.addFirst(current);
            String baseCheckpointId = current.getBaseCheckpointId();
            if (baseCheckpointId == null) {
                throw new CheckpointRecoveryException(
                        CheckpointRecoveryFailureReason.DELTA_CHECKPOINT_MISSING_BASE,
                        taskId,
                        current.getCheckpointId(),
                        "Delta checkpoint missing base checkpoint: taskId=" + taskId + " checkpointId=" + current.getCheckpointId());
            }
            current = byId.get(baseCheckpointId);
            if (current == null) {
                throw new CheckpointRecoveryException(
                        CheckpointRecoveryFailureReason.DELTA_CHAIN_BROKEN,
                        taskId,
                        checkpointId,
                        "Checkpoint chain broken: missing base checkpoint taskId=" + taskId + " checkpointId=" + baseCheckpointId);
            }
        }

        CheckpointMetadata baseCheckpoint = current;
        TaskState baseState = loadFullCheckpoint(taskId, baseCheckpoint.getCheckpointId())
                .orElseThrow(() -> new CheckpointRecoveryException(
                        CheckpointRecoveryFailureReason.BASE_FULL_CHECKPOINT_MISSING,
                        taskId,
                        checkpointId,
                        "Base full checkpoint not found in L3: taskId=" + taskId + " checkpointId=" + baseCheckpoint.getCheckpointId()));

        List<CheckpointDelta> deltas = new ArrayList<>();
        for (CheckpointMetadata deltaMeta : deltaChain) {
            deltas.add(loadDeltaCheckpoint(taskId, deltaMeta.getCheckpointId())
                    .orElseThrow(() -> new CheckpointRecoveryException(
                            CheckpointRecoveryFailureReason.DELTA_PAYLOAD_MISSING,
                            taskId,
                            deltaMeta.getCheckpointId(),
                            "Delta checkpoint not found in L3: taskId=" + taskId + " checkpointId=" + deltaMeta.getCheckpointId())));
        }
        return new CheckpointRecoveryResult(
                assemble(baseState, deltas),
                CheckpointRecoveryMode.DELTA_CHAIN,
                deltaChain.size(),
                target);
    }

    private String checkpointDataKey(String taskId, String checkpointId) {
        return "checkpoints/" + taskId + "/" + checkpointId + ".kryo";
    }

    private List<CheckpointMetadata> loadCheckpointMetadata(String taskId) {
        try {
            return l3.listCheckpointMetadata(taskId);
        } catch (CheckpointStoreException e) {
            throw mapCheckpointStoreException(
                    e,
                    taskId,
                    null,
                    CheckpointRecoveryFailureReason.CHECKPOINT_METADATA_LOAD_FAILED,
                    CheckpointRecoveryFailureReason.CHECKPOINT_METADATA_LOAD_FAILED,
                    "Failed to load checkpoint metadata for taskId=" + taskId);
        } catch (IllegalStateException e) {
            throw new CheckpointRecoveryException(
                    CheckpointRecoveryFailureReason.CHECKPOINT_METADATA_LOAD_FAILED,
                    taskId,
                    null,
                    "Failed to load checkpoint metadata for taskId=" + taskId,
                    e);
        }
    }

    private CheckpointRecoveryException mapCheckpointStoreException(
            CheckpointStoreException e,
            String taskId,
            String checkpointId,
            CheckpointRecoveryFailureReason readFailureReason,
            CheckpointRecoveryFailureReason defaultReason,
            String defaultMessage) {
        CheckpointRecoveryFailureReason reason = switch (e.getFailureType()) {
            case METADATA_READ_FAILED -> CheckpointRecoveryFailureReason.CHECKPOINT_METADATA_LOAD_FAILED;
            case PAYLOAD_INVALID -> defaultReason;
            case READ_FAILED -> readFailureReason;
            case VERSION_MISMATCH -> CheckpointRecoveryFailureReason.CHECKPOINT_VERSION_MISMATCH;
        };
        return new CheckpointRecoveryException(reason, taskId, checkpointId, defaultMessage, e);
    }

    private boolean isDuplicateEdge(TaskState state, DagEdge edge) {
        return state.getGraph().containsEquivalentEdge(edge);
    }
}
