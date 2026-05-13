package com.vortex.kernel.snapshot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which DAG elements have been modified since the last checkpoint.
 *
 * Maintains dirty sets per task for:
 * - node IDs (added or modified)
 * - edge IDs (added)
 * - context keys (added or modified)
 *
 * After a checkpoint, the dirty set is atomically retrieved and cleared.
 */
@Slf4j
@Component
public class DirtySetTracker {

    private final ConcurrentHashMap<String, Set<String>> dirtyNodeIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> dirtyEdgeIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> dirtyContextKeys = new ConcurrentHashMap<>();

    // ---- Mark dirty ----

    public void markNodeDirty(String taskId, String nodeId) {
        dirtyNodeIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(nodeId);
    }

    public void markEdgeDirty(String taskId, String edgeId) {
        dirtyEdgeIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(edgeId);
    }

    public void markContextDirty(String taskId, String key) {
        dirtyContextKeys.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    /**
     * Mark all nodes in a task as dirty (e.g., after recovery or on first full checkpoint).
     */
    public void markAllNodesDirty(String taskId, Set<String> nodeIds) {
        dirtyNodeIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(nodeIds);
    }

    // ---- Query ----

    /**
     * Atomically retrieve the current dirty state and reset it.
     */
    public DirtySnapshot getAndClear(String taskId) {
        Set<String> nodeIds = dirtyNodeIds.remove(taskId);
        Set<String> edgeIds = dirtyEdgeIds.remove(taskId);
        Set<String> contextKeys = dirtyContextKeys.remove(taskId);

        return new DirtySnapshot(
                nodeIds != null ? nodeIds : Collections.emptySet(),
                edgeIds != null ? edgeIds : Collections.emptySet(),
                contextKeys != null ? contextKeys : Collections.emptySet()
        );
    }

    /**
     * Check if a task has any dirty state (without clearing).
     */
    public boolean isDirty(String taskId) {
        Set<String> nodes = dirtyNodeIds.get(taskId);
        Set<String> edges = dirtyEdgeIds.get(taskId);
        Set<String> context = dirtyContextKeys.get(taskId);
        return (nodes != null && !nodes.isEmpty())
                || (edges != null && !edges.isEmpty())
                || (context != null && !context.isEmpty());
    }

    /**
     * Remove all dirty tracking for a task.
     */
    public void remove(String taskId) {
        dirtyNodeIds.remove(taskId);
        dirtyEdgeIds.remove(taskId);
        dirtyContextKeys.remove(taskId);
    }

    /**
     * Snapshot of dirty elements for a single task.
     */
    public record DirtySnapshot(
            Set<String> nodeIds,
            Set<String> edgeIds,
            Set<String> contextKeys
    ) {
        public boolean isEmpty() {
            return nodeIds.isEmpty() && edgeIds.isEmpty() && contextKeys.isEmpty();
        }

        public int totalChanges() {
            return nodeIds.size() + edgeIds.size() + contextKeys.size();
        }
    }
}
