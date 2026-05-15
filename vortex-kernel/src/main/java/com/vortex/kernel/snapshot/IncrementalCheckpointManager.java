package com.vortex.kernel.snapshot;

import com.vortex.common.model.*;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.storage.api.L3ColdStore;
import com.vortex.storage.l3.MinioColdStore;
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

        // Count deltas since last full
        // Find the last full checkpoint (might not be the immediate predecessor)
        CheckpointMetadata lastFull = history.stream()
                .filter(m -> m.getType() == CheckpointMetadata.CheckpointType.FULL)
                .reduce((first, second) -> second) // last element
                .orElse(null);

        long deltasSinceLastFull = deltasSinceLastFull(history);

        DirtySetTracker.DirtySnapshot dirty = dirtySetTracker.getAndClear(state.getTaskId());

        boolean shouldBeFull = history.isEmpty()
                || deltasSinceLastFull >= maxDeltasBeforeFull
                || lastFull == null;

        CheckpointMetadata meta;
        if (shouldBeFull) {
            meta = createFullCheckpoint(state, walSeq);
        } else {
            meta = createDeltaCheckpoint(state, dirty, lastFull.getCheckpointId(), walSeq);
        }

        meta.setNodeCount(state.getGraph().nodeCount());
        meta.setEdgeCount(state.getGraph().edgeCount());
        meta.setBranchId(state.getCurrentBranchId());
        history.add(meta);

        log.info("Checkpoint created taskId={} checkpointId={} type={} seqNo={} nodes={} edges={}",
                state.getTaskId(), meta.getCheckpointId(), meta.getType(),
                meta.getSequenceNumber(), meta.getNodeCount(), meta.getEdgeCount());

        return meta;
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
                changedNodes, newEdges, contextDiff, Collections.emptySet());

        // Keep DELTA metadata semantics, but persist a recoverable materialized snapshot.
        byte[] compressed = kryoSerializer.serializeCompressed(delta);
        String l3Key = "checkpoints/" + state.getTaskId() + "/" + checkpointId + ".kryo";

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

        return l3.saveCheckpointWithMetadata(state, meta);
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
        for (DagNode node : delta.changedNodes()) {
            state.getGraph().addNode(node);
        }

        // Apply edge changes
        for (DagEdge edge : delta.newEdges()) {
            try {
                state.getGraph().addEdge(edge);
            } catch (Exception e) {
                log.warn("Failed to apply delta edge {}: {}", edge.getEdgeId(), e.getMessage());
            }
        }

        // Apply context diff
        for (Map.Entry<String, String> entry : delta.contextDiff().entrySet()) {
            if (entry.getValue() == null) {
                state.getContext().remove(entry.getKey());
            } else {
                state.getContext().put(entry.getKey(), entry.getValue());
            }
        }

        // Apply deletions
        for (String nodeId : delta.deletedNodeIds()) {
            state.getGraph().removeNode(nodeId);
        }

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
        List<CheckpointMetadata> history = getOrLoadHistory(taskId);
        return deltasSinceLastFull(history);
    }

    /**
     * Reload checkpoint metadata for a task from cold storage.
     */
    public List<CheckpointMetadata> reloadTask(String taskId) {
        List<CheckpointMetadata> reloaded = new ArrayList<>(l3.listCheckpointMetadata(taskId));
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
                key -> Collections.synchronizedList(new ArrayList<>(l3.listCheckpointMetadata(key))));
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
}
