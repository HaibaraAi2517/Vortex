package com.vortex.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DagGraphTest {

    @Test
    void adjacencyRebuildDoesNotInvertGraphAndEdgeLocks() throws Exception {
        DagGraph graph = new DagGraph();
        DagNode source = node("source");
        DagNode target = node("target");
        graph.addNode(source);
        graph.addNode(target);
        var field = DagGraph.class.getDeclaredField("edges");
        field.setAccessible(true);
        Object edges = field.get(graph);
        var graphHeld = new java.util.concurrent.CountDownLatch(1);
        var edgesHeld = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2,
                Thread.ofPlatform().daemon().factory());
        try {
            var reader = executor.submit(() -> {
                synchronized (graph) {
                    graphHeld.countDown();
                    assertThat(edgesHeld.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    return graph.getSourceNodes();
                }
            });
            var writer = executor.submit(() -> {
                synchronized (edges) {
                    edgesHeld.countDown();
                    assertThat(graphHeld.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    graph.addEdge(edge(source, target));
                    return true;
                }
            });
            assertThat(writer.get(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(reader.get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .extracting(DagNode::getNodeId).containsExactly("source");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void addNode_and_retrieve() {
        DagGraph g = new DagGraph();
        DagNode n = DagNode.builder().type(DagNode.NodeType.THOUGHT).content("hello").build();
        g.addNode(n);
        assertThat(g.nodeCount()).isEqualTo(1);
        assertThat(g.getNode(n.getNodeId())).isPresent().get().extracting(DagNode::getContent).isEqualTo("hello");
    }

    @Test
    void addEdge_validatesEndpoints() {
        DagGraph g = new DagGraph();
        DagNode a = DagNode.builder().build();
        g.addNode(a);

        DagEdge e = DagEdge.builder().sourceNodeId(a.getNodeId()).targetNodeId("nonexistent").build();
        assertThatThrownBy(() -> g.addEdge(e))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target node not found");

        DagEdge e2 = DagEdge.builder().sourceNodeId("nonexistent").targetNodeId(a.getNodeId()).build();
        assertThatThrownBy(() -> g.addEdge(e2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Source node not found");
    }

    @Test
    void cycleDetection_linearChain_noCycle() {
        DagGraph g = linearChain(5);
        assertThat(g.hasCycle()).isFalse();
    }

    @Test
    void cycleDetection_triangle_hasCycle() {
        DagGraph g = new DagGraph();
        DagNode a = DagNode.builder().build();
        DagNode b = DagNode.builder().build();
        DagNode c = DagNode.builder().build();
        g.addNode(a); g.addNode(b); g.addNode(c);

        g.addEdge(edge(a, b));
        g.addEdge(edge(b, c));
        // The edge c→a would create a cycle, so addEdge should reject it
        assertThatThrownBy(() -> g.addEdge(edge(c, a)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void cycleDetection_selfLoop_hasCycle() {
        DagGraph g = new DagGraph();
        DagNode a = DagNode.builder().build();
        g.addNode(a);
        assertThatThrownBy(() -> g.addEdge(edge(a, a)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void cycleDetection_diamond_noCycle() {
        DagGraph g = diamond();
        assertThat(g.hasCycle()).isFalse();
    }

    @Test
    void topologicalSort_linearChain() {
        DagGraph g = linearChain(5);
        List<String> order = g.topologicalSort();
        assertThat(order).hasSize(5);
        assertThat(order.get(0)).isEqualTo("node0");
        assertThat(order.get(4)).isEqualTo("node4");
    }

    @Test
    void topologicalSort_withCycle_throws() {
        DagGraph g = new DagGraph();
        DagNode a = DagNode.builder().build();
        DagNode b = DagNode.builder().build();
        g.addNode(a); g.addNode(b);
        // Bypass addEdge to create a cycle that would otherwise be rejected
        g.addEdgeUncheckedForImport(edge(a, b));
        g.addEdgeUncheckedForImport(edge(b, a));
        assertThatThrownBy(g::topologicalSort)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void topologicalSort_diamond() {
        DagGraph g = diamond();
        List<String> order = g.topologicalSort();
        assertThat(order).hasSize(4);
        // Entry must be first (zero in-degree)
        assertThat(order.get(0)).isEqualTo("entry");
        // Exit must be last (zero out-degree)
        assertThat(order.get(3)).isEqualTo("exit");
    }

    @Test
    void sourceNodes_and_sinkNodes() {
        DagGraph g = diamond();
        assertThat(g.getSourceNodes()).hasSize(1);
        assertThat(g.getSourceNodes().get(0).getNodeId()).isEqualTo("entry");
        assertThat(g.getSinkNodes()).hasSize(1);
        assertThat(g.getSinkNodes().get(0).getNodeId()).isEqualTo("exit");
    }

    @Test
    void areConnected_reachable() {
        DagGraph g = linearChain(5);
        assertThat(g.areConnected("node0", "node4")).isTrue();
        assertThat(g.areConnected("node4", "node0")).isFalse();
    }

    @Test
    void fromLegacy_convertsFlatList() {
        List<TaskState.ThoughtNode> legacy = List.of(
                TaskState.ThoughtNode.builder().nodeId("n1").type("THOUGHT").content("step1").completed(true).build(),
                TaskState.ThoughtNode.builder().nodeId("n2").type("ACTION").content("step2").completed(false).build()
        );
        DagGraph g = DagGraph.fromLegacy(legacy);
        assertThat(g.nodeCount()).isEqualTo(2);
        assertThat(g.getEdges()).hasSize(1); // linear chain creates 1 edge
        assertThat(g.getSourceNodes()).hasSize(1);
        assertThat(g.getSinkNodes()).hasSize(1);
    }

    @Test
    void fromLegacy_emptyList() {
        DagGraph g = DagGraph.fromLegacy(List.of());
        assertThat(g.nodeCount()).isEqualTo(0);
        assertThat(g.getEdges()).isEmpty();
    }

    @Test
    void fromLegacy_null() {
        DagGraph g = DagGraph.fromLegacy(null);
        assertThat(g.nodeCount()).isEqualTo(0);
    }

    @Test
    void removeNode_cleansEdges() {
        DagGraph g = linearChain(3);
        g.removeNode("node1");
        assertThat(g.nodeCount()).isEqualTo(2);
        // Edges to/from node1 should be removed
        assertThat(g.getEdges()).hasSize(0); // edges 0→1 and 1→2 both removed
    }

    @Test
    void getOutgoingEdges_and_incomingEdges() {
        DagGraph g = diamond();
        List<DagEdge> outgoing = g.getOutgoingEdges("entry");
        assertThat(outgoing).hasSize(2); // entry→left, entry→right

        List<DagEdge> incoming = g.getIncomingEdges("exit");
        assertThat(incoming).hasSize(2); // left→exit, right→exit
    }

    @Test
    void addEdge_duplicate_isIdempotent() {
        DagGraph g = linearChain(2);
        int before = g.getEdges().size();
        DagNode a = g.getNode("node0").get();
        DagNode b = g.getNode("node1").get();
        g.addEdge(edge(a, b)); // duplicate — should be silently ignored
        assertThat(g.getEdges()).hasSize(before);
    }

    // ---- helpers ----

    private DagNode node(String id) {
        return DagNode.builder().nodeId(id).type(DagNode.NodeType.THOUGHT).content(id).build();
    }

    private DagEdge edge(DagNode src, DagNode tgt) {
        return DagEdge.builder()
                .sourceNodeId(src.getNodeId())
                .targetNodeId(tgt.getNodeId())
                .dependencyType(DagEdge.EdgeType.CONTROL_DEP)
                .build();
    }

    /** Create a 0→1→2→...→(n-1) chain using addEdge. */
    private DagGraph linearChain(int n) {
        DagGraph g = new DagGraph();
        DagNode prev = null;
        for (int i = 0; i < n; i++) {
            DagNode node = node("node" + i);
            g.addNode(node);
            if (prev != null) {
                g.addEdge(edge(prev, node));
            }
            prev = node;
        }
        return g;
    }

    /** Create a diamond graph: entry → left/right → exit, using addEdge. */
    private DagGraph diamond() {
        DagGraph g = new DagGraph();
        DagNode entry = node("entry");
        DagNode left = node("left");
        DagNode right = node("right");
        DagNode exit = node("exit");
        g.addNode(entry); g.addNode(left); g.addNode(right); g.addNode(exit);
        g.addEdge(edge(entry, left));
        g.addEdge(edge(entry, right));
        g.addEdge(edge(left, exit));
        g.addEdge(edge(right, exit));
        return g;
    }
}
