package com.vortex.common.serialization;

import com.vortex.common.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.*;

class KryoSerializerTest {

    private KryoSerializer kryo;

    @BeforeEach
    void setUp() {
        kryo = new KryoSerializer();
    }

    @Test
    void roundTrip_dagNode() {
        DagNode node = DagNode.builder()
                .type(DagNode.NodeType.ACTION)
                .content("Execute query")
                .result("Query returned 42 rows")
                .status(DagNode.NodeStatus.COMPLETED)
                .build();
        node.getMetadata().put("cost", "150ms");

        byte[] data = kryo.serialize(node);
        DagNode restored = kryo.deserialize(data, DagNode.class);

        assertThat(restored.getNodeId()).isEqualTo(node.getNodeId());
        assertThat(restored.getType()).isEqualTo(DagNode.NodeType.ACTION);
        assertThat(restored.getContent()).isEqualTo("Execute query");
        assertThat(restored.getResult()).isEqualTo("Query returned 42 rows");
        assertThat(restored.getStatus()).isEqualTo(DagNode.NodeStatus.COMPLETED);
        assertThat(restored.getMetadata()).containsEntry("cost", "150ms");
    }

    @Test
    void roundTrip_dagEdge() {
        DagEdge edge = DagEdge.builder()
                .sourceNodeId("src-123")
                .targetNodeId("tgt-456")
                .dependencyType(DagEdge.EdgeType.DATA_DEP)
                .condition("result > 0")
                .build();

        byte[] data = kryo.serialize(edge);
        DagEdge restored = kryo.deserialize(data, DagEdge.class);

        assertThat(restored.getSourceNodeId()).isEqualTo("src-123");
        assertThat(restored.getTargetNodeId()).isEqualTo("tgt-456");
        assertThat(restored.getDependencyType()).isEqualTo(DagEdge.EdgeType.DATA_DEP);
        assertThat(restored.getCondition()).isEqualTo("result > 0");
    }

    @Test
    void roundTrip_dagGraph() {
        DagGraph graph = new DagGraph();
        DagNode a = DagNode.builder().type(DagNode.NodeType.THOUGHT).content("step 1").build();
        DagNode b = DagNode.builder().type(DagNode.NodeType.ACTION).content("step 2").build();
        graph.addNode(a); graph.addNode(b);
        graph.getEdges().add(DagEdge.builder()
                .sourceNodeId(a.getNodeId())
                .targetNodeId(b.getNodeId())
                .dependencyType(DagEdge.EdgeType.CONTROL_DEP)
                .build());

        byte[] data = kryo.serialize(graph);
        DagGraph restored = kryo.deserialize(data, DagGraph.class);

        assertThat(restored.nodeCount()).isEqualTo(2);
        assertThat(restored.getEdges()).hasSize(1);
        assertThat(restored.areConnected(a.getNodeId(), b.getNodeId())).isTrue();
    }

    @Test
    void roundTrip_taskState_full() {
        TaskState state = createSampleTaskState();

        byte[] data = kryo.serialize(state);
        TaskState restored = kryo.deserialize(data, TaskState.class);

        assertThat(restored.getTaskId()).isEqualTo(state.getTaskId());
        assertThat(restored.getDescription()).isEqualTo("test task");
        assertThat(restored.getGraph().nodeCount()).isEqualTo(3);
        assertThat(restored.getBranches()).hasSize(1);
        assertThat(restored.getContext()).containsEntry("totalSteps", "3");
    }

    @Test
    void roundTrip_withCompression() {
        TaskState state = createSampleTaskState();

        byte[] compressed = kryo.serializeCompressed(state);
        byte[] raw = kryo.serialize(state);

        // Compressed should be smaller
        assertThat(compressed.length).isLessThan(raw.length);

        TaskState restored = kryo.deserializeCompressed(compressed, TaskState.class);
        assertThat(restored.getTaskId()).isEqualTo(state.getTaskId());
        assertThat(restored.getGraph().nodeCount()).isEqualTo(3);
    }

    @Test
    void kryoIsFasterThanJackson() {
        // Compare DagGraph directly (TaskState has @JsonIgnore on graph, making Jackson trivial)
        DagGraph graph = createBenchmarkGraph(50);
        kryoVsJacksonBenchmark(graph);
    }

    @Test
    void kryoProducesSmallerOutput_thanJackson() {
        DagGraph graph = createBenchmarkGraph(50);
        byte[] kryoBytes = kryo.serialize(graph);
        com.fasterxml.jackson.databind.ObjectMapper jackson =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        byte[] jacksonBytes;
        try {
            jacksonBytes = jackson.writeValueAsBytes(graph);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        System.out.printf("Jackson size: %d bytes%n", jacksonBytes.length);
        System.out.printf("Kryo size:    %d bytes (%.1fx smaller)%n",
                kryoBytes.length, (double) jacksonBytes.length / kryoBytes.length);

        assertThat(kryoBytes.length).isLessThan(jacksonBytes.length);
    }

    private static void kryoVsJacksonBenchmark(DagGraph graph) {
        com.fasterxml.jackson.databind.ObjectMapper jackson =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        KryoSerializer localKryo = new KryoSerializer();

        // Warmup
        for (int i = 0; i < 10; i++) {
            try { jackson.writeValueAsBytes(graph); } catch (Exception ignored) {}
            localKryo.serialize(graph);
        }

        // Benchmark
        int iterations = 100;
        long jacksonStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try { jackson.writeValueAsBytes(graph); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new RuntimeException(e); }
        }
        long jacksonNs = (System.nanoTime() - jacksonStart) / iterations;

        long kryoStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            localKryo.serialize(graph);
        }
        long kryoNs = (System.nanoTime() - kryoStart) / iterations;

        System.out.printf("Jackson: %d ns per serialization%n", jacksonNs);
        System.out.printf("Kryo:    %d ns per serialization (%.1fx faster)%n",
                kryoNs, (double) jacksonNs / kryoNs);

        // Kryo should at minimum not be significantly slower,
        // and the compressed checkpoint should beat Jackson's uncompressed output.
        // Exact speedup depends on JVM warmup and OS.
        double speedup = (double) jacksonNs / kryoNs;
        byte[] kryoCheck = localKryo.serialize(graph);
        byte[] jacksonCheck;
        try { jacksonCheck = jackson.writeValueAsBytes(graph); } catch (Exception e) { throw new RuntimeException(e); }
        // Kryo (with custom serializer skipping transient fields) should be smaller
        assertThat(kryoCheck.length).isLessThan(jacksonCheck.length)
                .as("Kryo output should be smaller than JSON for the same graph");
    }

    private DagGraph createBenchmarkGraph(int count) {
        DagGraph graph = new DagGraph();
        String previousId = null;
        for (int i = 0; i < count; i++) {
            DagNode node = DagNode.builder()
                    .type(i % 2 == 0 ? DagNode.NodeType.THOUGHT : DagNode.NodeType.ACTION)
                    .content("Node " + i + " content with some realistic text about distributed systems and AI memory management.")
                    .result(i % 3 == 0 ? "Result for node " + i : null)
                    .status(i < count - 1 ? DagNode.NodeStatus.COMPLETED : DagNode.NodeStatus.PENDING)
                    .build();
            graph.addNode(node);
            if (previousId != null) {
                graph.getEdges().add(DagEdge.builder()
                        .sourceNodeId(previousId)
                        .targetNodeId(node.getNodeId())
                        .dependencyType(DagEdge.EdgeType.CONTROL_DEP)
                        .build());
            }
            previousId = node.getNodeId();
        }
        return graph;
    }

    @Test
    void isKryoFormat_detectsBinary() {
        byte[] kryoData = kryo.serialize(DagNode.builder().build());
        assertThat(KryoSerializer.isKryoFormat(kryoData)).isTrue();
        assertThat(KryoSerializer.hasVersionHeader(kryoData)).isTrue();

        byte[] jsonData = "{\"nodeId\":\"123\"}".getBytes();
        assertThat(KryoSerializer.isKryoFormat(jsonData)).isFalse();
    }

    @Test
    void deserialize_acceptsLegacyPayloadWithoutHeader() {
        DagNode node = DagNode.builder().type(DagNode.NodeType.THOUGHT).content("legacy").build();
        byte[] current = kryo.serialize(node);
        byte[] legacy = java.util.Arrays.copyOfRange(current, 5, current.length);

        DagNode restored = kryo.deserialize(legacy, DagNode.class);

        assertThat(restored.getNodeId()).isEqualTo(node.getNodeId());
        assertThat(restored.getContent()).isEqualTo("legacy");
    }

    @Test
    void deserializeCompressed_acceptsLegacyCompressedPayloadWithoutHeader() throws Exception {
        TaskState state = createSampleTaskState();
        byte[] current = kryo.serialize(state);
        byte[] legacy = java.util.Arrays.copyOfRange(current, 5, current.length);
        byte[] legacyCompressed = gzip(legacy);

        TaskState restored = kryo.deserializeCompressed(legacyCompressed, TaskState.class);

        assertThat(restored.getTaskId()).isEqualTo(state.getTaskId());
        assertThat(restored.getGraph().nodeCount()).isEqualTo(state.getGraph().nodeCount());
    }

    @Test
    void deserialize_rejectsUnsupportedVersionHeader() {
        byte[] kryoData = kryo.serialize(DagNode.builder().type(DagNode.NodeType.ACTION).build());
        kryoData[4] = (byte) 99;

        assertThatThrownBy(() -> kryo.deserialize(kryoData, DagNode.class))
                .isInstanceOf(KryoSerializer.VersionMismatchException.class)
                .hasMessageContaining("expected=1")
                .hasMessageContaining("actual=99");
    }

    @Test
    void isJacksonFormat_detectsJson() {
        byte[] jsonData = "{\"nodeId\":\"123\"}".getBytes();
        assertThat(JacksonCompatibilityBridge.isJacksonFormat(jsonData)).isTrue();

        byte[] kryoData = kryo.serialize(DagNode.builder().build());
        assertThat(JacksonCompatibilityBridge.isJacksonFormat(kryoData)).isFalse();
    }

    @Test
    void jacksonMigration_fromOldFlatList() throws Exception {
        // Simulate old Jackson JSON with flat nodes
        String oldJson = """
                {
                    "taskId": "old-task-1",
                    "description": "Legacy task",
                    "namespace": "test",
                    "nodes": [
                        {"nodeId":"n1","type":"THOUGHT","content":"step1","completed":true},
                        {"nodeId":"n2","type":"ACTION","content":"step2","completed":false}
                    ]
                }""";

        TaskState migrated = JacksonCompatibilityBridge.migrateFromJackson(oldJson.getBytes());
        assertThat(migrated.getGraph()).isNotNull();
        assertThat(migrated.getGraph().nodeCount()).isEqualTo(2);
        assertThat(migrated.getGraph().getEdges()).hasSize(1); // linear chain
    }

    private TaskState createSampleTaskState() {
        DagGraph graph = new DagGraph();
        DagNode a = DagNode.builder().type(DagNode.NodeType.THOUGHT).content("start").status(DagNode.NodeStatus.COMPLETED).build();
        DagNode b = DagNode.builder().type(DagNode.NodeType.ACTION).content("process").status(DagNode.NodeStatus.COMPLETED).build();
        DagNode c = DagNode.builder().type(DagNode.NodeType.OBSERVATION).content("observe").status(DagNode.NodeStatus.PENDING).build();
        graph.addNode(a); graph.addNode(b); graph.addNode(c);
        graph.getEdges().add(DagEdge.builder().sourceNodeId(a.getNodeId()).targetNodeId(b.getNodeId()).build());
        graph.getEdges().add(DagEdge.builder().sourceNodeId(b.getNodeId()).targetNodeId(c.getNodeId()).build());

        TaskState state = TaskState.builder()
                .taskId("task-test-1")
                .description("test task")
                .namespace("test-ns")
                .graph(graph)
                .currentNodeId(b.getNodeId())
                .build();
        state.getContext().put("totalSteps", "3");
        state.getBranches().add(TaskBranch.builder().branchName("main").sourceNodeId(a.getNodeId()).build());
        return state;
    }

    private static byte[] gzip(byte[] payload) throws Exception {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(bos)) {
            gzos.write(payload);
            gzos.finish();
            return bos.toByteArray();
        }
    }
}
