package com.vortex.kernel.snapshot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which runtime elements have been modified since the last checkpoint.
 */
@Slf4j
@Component
public class DirtySetTracker {

    private final ConcurrentHashMap<String, Set<String>> dirtyNodeIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> dirtyEdgeIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> dirtyContextKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> dirtyConversationIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> dirtyToolExecutionIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> dirtyLlmCallIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> deletedNodeIds = new ConcurrentHashMap<>();

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

    public void markConversationDirty(String taskId, String conversationId) {
        dirtyConversationIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(conversationId);
    }

    public void markToolExecutionDirty(String taskId, String executionId) {
        dirtyToolExecutionIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(executionId);
    }

    public void markLlmCallDirty(String taskId, String callId) {
        dirtyLlmCallIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(callId);
    }

    public void markNodeDeleted(String taskId, String nodeId) {
        deletedNodeIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(nodeId);
        Set<String> dirtyNodes = dirtyNodeIds.get(taskId);
        if (dirtyNodes != null) {
            dirtyNodes.remove(nodeId);
        }
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
        Set<String> conversationIds = dirtyConversationIds.remove(taskId);
        Set<String> toolExecutionIds = dirtyToolExecutionIds.remove(taskId);
        Set<String> llmCallIds = dirtyLlmCallIds.remove(taskId);
        Set<String> deletedNodes = deletedNodeIds.remove(taskId);

        return new DirtySnapshot(
                nodeIds != null ? nodeIds : Collections.emptySet(),
                edgeIds != null ? edgeIds : Collections.emptySet(),
                contextKeys != null ? contextKeys : Collections.emptySet(),
                conversationIds != null ? conversationIds : Collections.emptySet(),
                toolExecutionIds != null ? toolExecutionIds : Collections.emptySet(),
                llmCallIds != null ? llmCallIds : Collections.emptySet(),
                deletedNodes != null ? deletedNodes : Collections.emptySet()
        );
    }

    /**
     * Restore a previously captured dirty snapshot when checkpoint persistence fails.
     */
    public void restore(String taskId, DirtySnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        if (!snapshot.nodeIds().isEmpty()) {
            dirtyNodeIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(snapshot.nodeIds());
        }
        if (!snapshot.edgeIds().isEmpty()) {
            dirtyEdgeIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(snapshot.edgeIds());
        }
        if (!snapshot.contextKeys().isEmpty()) {
            dirtyContextKeys.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(snapshot.contextKeys());
        }
        if (!snapshot.conversationIds().isEmpty()) {
            dirtyConversationIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(snapshot.conversationIds());
        }
        if (!snapshot.toolExecutionIds().isEmpty()) {
            dirtyToolExecutionIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(snapshot.toolExecutionIds());
        }
        if (!snapshot.llmCallIds().isEmpty()) {
            dirtyLlmCallIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(snapshot.llmCallIds());
        }
        if (!snapshot.deletedNodeIds().isEmpty()) {
            deletedNodeIds.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).addAll(snapshot.deletedNodeIds());
        }
    }

    /**
     * Check if a task has any dirty state (without clearing).
     */
    public boolean isDirty(String taskId) {
        return hasAny(dirtyNodeIds.get(taskId))
                || hasAny(dirtyEdgeIds.get(taskId))
                || hasAny(dirtyContextKeys.get(taskId))
                || hasAny(dirtyConversationIds.get(taskId))
                || hasAny(dirtyToolExecutionIds.get(taskId))
                || hasAny(dirtyLlmCallIds.get(taskId))
                || hasAny(deletedNodeIds.get(taskId));
    }

    public boolean hasDirty(String taskId) {
        return isDirty(taskId);
    }

    /**
     * Remove all dirty tracking for a task.
     */
    public void remove(String taskId) {
        dirtyNodeIds.remove(taskId);
        dirtyEdgeIds.remove(taskId);
        dirtyContextKeys.remove(taskId);
        dirtyConversationIds.remove(taskId);
        dirtyToolExecutionIds.remove(taskId);
        dirtyLlmCallIds.remove(taskId);
        deletedNodeIds.remove(taskId);
    }

    private boolean hasAny(Set<String> values) {
        return values != null && !values.isEmpty();
    }

    /**
     * Snapshot of dirty elements for a single task.
     */
    public record DirtySnapshot(
            Set<String> nodeIds,
            Set<String> edgeIds,
            Set<String> contextKeys,
            Set<String> conversationIds,
            Set<String> toolExecutionIds,
            Set<String> llmCallIds,
            Set<String> deletedNodeIds
    ) {
        public boolean isEmpty() {
            return nodeIds.isEmpty()
                    && edgeIds.isEmpty()
                    && contextKeys.isEmpty()
                    && conversationIds.isEmpty()
                    && toolExecutionIds.isEmpty()
                    && llmCallIds.isEmpty()
                    && deletedNodeIds.isEmpty();
        }

        public int totalChanges() {
            return nodeIds.size()
                    + edgeIds.size()
                    + contextKeys.size()
                    + conversationIds.size()
                    + toolExecutionIds.size()
                    + llmCallIds.size()
                    + deletedNodeIds.size();
        }
    }
}