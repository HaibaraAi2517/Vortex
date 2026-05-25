package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Directed Acyclic Graph representing an Agent's thought-action chain.
 *
 * Thread-safe for concurrent read/write via ConcurrentHashMap for nodes
 * and synchronized blocks for structural mutations.
 *
 * Core guarantees:
 * - Cycle detection on edge insertion (rejects edges that would create cycles)
 * - Topological sort (Kahn's algorithm)
 * - Source/sink node identification
 * - Lazy adjacency list rebuilding with dirty flag
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DagGraph {

    /** All nodes keyed by nodeId. */
    @Builder.Default
    private Map<String, DagNode> nodes = new ConcurrentHashMap<>();

    /** All edges in the graph. Access via synchronized blocks for thread safety. */
    @Builder.Default
    private List<DagEdge> edges = new ArrayList<>();

    /** Cached adjacency list: sourceNodeId → [targetNodeId, ...]. Rebuilt on structural change. */
    @Builder.Default
    private transient volatile Map<String, List<String>> adjacencyList = new ConcurrentHashMap<>();

    /** Cached reverse adjacency: targetNodeId → [sourceNodeId, ...]. */
    @Builder.Default
    private transient volatile Map<String, List<String>> reverseAdjacencyList = new ConcurrentHashMap<>();

    /** Cached in-degree map for topological sort. */
    @Builder.Default
    private transient volatile Map<String, Integer> inDegree = new ConcurrentHashMap<>();

    /** Whether the adjacency structures need rebuilding. */
    @Builder.Default
    private transient volatile boolean dirty = false;

    // ---- Node operations ----

    public void addNode(DagNode node) {
        nodes.put(node.getNodeId(), node);
    }

    public Optional<DagNode> getNode(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public DagNode removeNode(String nodeId) {
        DagNode removed = nodes.remove(nodeId);
        if (removed != null) {
            synchronized (edges) {
                edges.removeIf(e -> e.getSourceNodeId().equals(nodeId) || e.getTargetNodeId().equals(nodeId));
            }
            markDirty();
        }
        return removed;
    }

    public int nodeCount() {
        return nodes.size();
    }

    // ---- Edge operations ----

    /**
     * Add an edge with cycle detection. Rejects edges that would create a cycle.
     *
     * @throws IllegalArgumentException if either endpoint doesn't exist
     * @throws IllegalStateException if the edge would create a cycle
     */
    public void validateEdge(DagEdge edge) {
        synchronized (edges) {
            validateEdgeUnderLock(edge);
        }
    }

    public void addEdge(DagEdge edge) {
        synchronized (edges) {
            if (validateEdgeUnderLock(edge)) {
                edges.add(edge);
                markDirty();
            }
        }
    }

    public List<DagEdge> getOutgoingEdges(String nodeId) {
        rebuildAdjacency();
        List<String> targets = adjacencyList.getOrDefault(nodeId, Collections.emptyList());
        synchronized (edges) {
            return edges.stream()
                    .filter(e -> e.getSourceNodeId().equals(nodeId) && targets.contains(e.getTargetNodeId()))
                    .collect(Collectors.toList());
        }
    }

    public List<DagEdge> getIncomingEdges(String nodeId) {
        rebuildAdjacency();
        List<String> sources = reverseAdjacencyList.getOrDefault(nodeId, Collections.emptyList());
        synchronized (edges) {
            return edges.stream()
                    .filter(e -> e.getTargetNodeId().equals(nodeId) && sources.contains(e.getSourceNodeId()))
                    .collect(Collectors.toList());
        }
    }

    public int edgeCount() {
        return edges.size();
    }

    // ---- Graph analysis ----

    /**
     * Returns nodes with no incoming edges (entry points of the DAG).
     */
    public List<DagNode> getSourceNodes() {
        rebuildAdjacency();
        return nodes.keySet().stream()
                .filter(id -> reverseAdjacencyList.getOrDefault(id, Collections.emptyList()).isEmpty())
                .map(nodes::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Returns nodes with no outgoing edges (exit points of the DAG).
     */
    public List<DagNode> getSinkNodes() {
        rebuildAdjacency();
        return nodes.keySet().stream()
                .filter(id -> adjacencyList.getOrDefault(id, Collections.emptyList()).isEmpty())
                .map(nodes::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Check if the graph contains a cycle using DFS with three-color marking.
     */
    public boolean hasCycle() {
        rebuildAdjacency(); // always rebuild for safety
        Set<String> white = new HashSet<>(nodes.keySet()); // unvisited
        Set<String> gray = new HashSet<>();                  // in current DFS path
        Set<String> black = new HashSet<>();                  // fully processed

        while (!white.isEmpty()) {
            String start = white.iterator().next();
            if (hasCycleDfs(start, white, gray, black)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleDfs(String nodeId, Set<String> white, Set<String> gray, Set<String> black) {
        white.remove(nodeId);
        gray.add(nodeId);

        for (String neighbor : adjacencyList.getOrDefault(nodeId, Collections.emptyList())) {
            if (black.contains(neighbor)) continue;
            if (gray.contains(neighbor)) return true; // back edge → cycle
            if (hasCycleDfs(neighbor, white, gray, black)) return true;
        }

        gray.remove(nodeId);
        black.add(nodeId);
        return false;
    }

    /**
     * Kahn's algorithm for topological sort.
     *
     * @return ordered list of node IDs in topological order
     * @throws IllegalStateException if the graph contains a cycle
     */
    public List<String> topologicalSort() {
        rebuildAdjacency(); // always rebuild for safety

        // Compute in-degree for each node
        Map<String, Integer> indeg = new HashMap<>();
        for (String nodeId : nodes.keySet()) {
            indeg.put(nodeId, 0);
        }
        for (Map.Entry<String, List<String>> entry : adjacencyList.entrySet()) {
            for (String target : entry.getValue()) {
                indeg.merge(target, 1, Integer::sum);
            }
        }

        // Queue nodes with zero in-degree
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indeg.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);
            for (String neighbor : adjacencyList.getOrDefault(current, Collections.emptyList())) {
                int newDegree = indeg.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        if (result.size() != nodes.size()) {
            throw new IllegalStateException("Graph contains a cycle — topological sort is impossible");
        }

        return result;
    }

    /**
     * Check if targetNodeId is reachable from sourceNodeId via BFS.
     */
    public boolean areConnected(String sourceNodeId, String targetNodeId) {
        rebuildAdjacency(); // always rebuild — dirty is transient, lost after deserialization
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.offer(sourceNodeId);
        visited.add(sourceNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(targetNodeId)) return true;
            for (String neighbor : adjacencyList.getOrDefault(current, Collections.emptyList())) {
                if (visited.add(neighbor)) {
                    queue.offer(neighbor);
                }
            }
        }
        return false;
    }

    // ---- Legacy migration ----

    /**
     * Convert a flat list of old ThoughtNodes into a linear DAG.
     */
    public static DagGraph fromLegacy(List<TaskState.ThoughtNode> legacyNodes) {
        DagGraph graph = new DagGraph();
        if (legacyNodes == null || legacyNodes.isEmpty()) return graph;

        String previousId = null;
        for (TaskState.ThoughtNode legacy : legacyNodes) {
            DagNode node = DagNode.builder()
                    .nodeId(legacy.getNodeId())
                    .type(mapLegacyType(legacy.getType()))
                    .content(legacy.getContent())
                    .result(legacy.getResult())
                    .status(legacy.isCompleted() ? DagNode.NodeStatus.COMPLETED : DagNode.NodeStatus.PENDING)
                    .executedAt(legacy.getExecutedAt())
                    .build();
            graph.addNode(node);

            if (previousId != null) {
                DagEdge edge = DagEdge.builder()
                        .sourceNodeId(previousId)
                        .targetNodeId(node.getNodeId())
                        .dependencyType(DagEdge.EdgeType.CONTROL_DEP)
                        .build();
                // Bypass cycle check for linear chains from legacy
                synchronized (graph.edges) {
                    graph.edges.add(edge);
                }
                graph.markDirty();
            }
            previousId = node.getNodeId();
        }
        return graph;
    }

    private static DagNode.NodeType mapLegacyType(String legacyType) {
        if (legacyType == null) return DagNode.NodeType.THOUGHT;
        return switch (legacyType.toUpperCase()) {
            case "ACTION" -> DagNode.NodeType.ACTION;
            case "OBSERVATION" -> DagNode.NodeType.OBSERVATION;
            default -> DagNode.NodeType.THOUGHT;
        };
    }

    // ---- Internal helpers ----

    private void markDirty() {
        this.dirty = true;
    }

    private boolean validateEdgeUnderLock(DagEdge edge) {
        if (!nodes.containsKey(edge.getSourceNodeId())) {
            throw new IllegalArgumentException("Source node not found: " + edge.getSourceNodeId());
        }
        if (!nodes.containsKey(edge.getTargetNodeId())) {
            throw new IllegalArgumentException("Target node not found: " + edge.getTargetNodeId());
        }

        boolean duplicateExists = edges.stream().anyMatch(e ->
                e.getSourceNodeId().equals(edge.getSourceNodeId())
                        && e.getTargetNodeId().equals(edge.getTargetNodeId())
                        && e.getDependencyType() == edge.getDependencyType());
        if (duplicateExists) {
            return false;
        }

        rebuildAdjacency();
        Map<String, List<String>> testAdj = deepCopyAdjacency(adjacencyList);
        testAdj.computeIfAbsent(edge.getSourceNodeId(), k -> new ArrayList<>()).add(edge.getTargetNodeId());

        if (wouldCreateCycle(testAdj, edge.getSourceNodeId(), edge.getTargetNodeId())) {
            throw new IllegalStateException(
                    "Adding edge " + edge.getSourceNodeId() + " -> " + edge.getTargetNodeId()
                            + " would create a cycle in the DAG");
        }
        return true;
    }

    private void rebuildAdjacency() {
        Map<String, List<String>> adj = new ConcurrentHashMap<>();
        Map<String, List<String>> rev = new ConcurrentHashMap<>();

        synchronized (edges) {
            for (DagEdge edge : edges) {
                adj.computeIfAbsent(edge.getSourceNodeId(), k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(edge.getTargetNodeId());
                rev.computeIfAbsent(edge.getTargetNodeId(), k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(edge.getSourceNodeId());
            }
        }

        this.adjacencyList = adj;
        this.reverseAdjacencyList = rev;
        this.dirty = false;
    }

    private void rebuildIfDirty() {
        if (!dirty) return;
        synchronized (this) {
            if (!dirty) return; // double-check
            rebuildAdjacency();
        }
    }

    private Map<String, List<String>> deepCopyAdjacency(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * Check if adding sourceId → targetId would create a cycle.
     * Uses DFS from targetId to see if we can reach sourceId.
     */
    private boolean wouldCreateCycle(Map<String, List<String>> adj, String sourceId, String targetId) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.offer(targetId);
        visited.add(targetId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(sourceId)) return true;
            for (String neighbor : adj.getOrDefault(current, Collections.emptyList())) {
                if (visited.add(neighbor)) {
                    queue.offer(neighbor);
                }
            }
        }
        return false;
    }
}
