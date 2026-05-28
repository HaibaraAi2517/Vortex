package com.vortex.common.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vortex.common.model.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

/**
 * Compares serialization performance between Jackson and Kryo for representative task data.
 * Not a Spring component — intended for manual execution or integration into benchmark tests.
 */
@Slf4j
public class SerializationBenchmark {

    private static final ObjectMapper JACKSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .findAndRegisterModules();
    private static final KryoSerializer KRYO = new KryoSerializer();

    /**
     * Create a representative TaskState with N nodes in a linear chain.
     */
    public static TaskState createSampleTask(int nodeCount) {
        TaskState state = TaskState.builder()
                .taskId(UUID.randomUUID().toString())
                .description("Benchmark task with " + nodeCount + " nodes")
                .namespace("benchmark")
                .build();

        DagGraph graph = new DagGraph();
        String previousId = null;
        for (int i = 0; i < nodeCount; i++) {
            DagNode node = DagNode.builder()
                    .type(i % 2 == 0 ? DagNode.NodeType.THOUGHT : DagNode.NodeType.ACTION)
                    .content("This is benchmark node number " + i + " with some additional text to make it realistic. "
                            + "The agent is reasoning about complex topics involving distributed systems and AI memory management.")
                    .result(i % 2 == 1 ? "Action result for node " + i : null)
                    .status(i < nodeCount - 1 ? DagNode.NodeStatus.COMPLETED : DagNode.NodeStatus.PENDING)
                    .build();
            graph.addNode(node);

            if (previousId != null) {
                graph.addEdgeUncheckedForImport(DagEdge.builder()
                        .sourceNodeId(previousId)
                        .targetNodeId(node.getNodeId())
                        .dependencyType(DagEdge.EdgeType.CONTROL_DEP)
                        .build());
            }
            previousId = node.getNodeId();
        }
        state.setGraph(graph);
        state.getContext().put("key1", "value1");
        state.getContext().put("totalSteps", String.valueOf(nodeCount));

        return state;
    }

    /**
     * Run benchmark and return results as a formatted string.
     *
     * @param nodeCount  number of nodes in the test graph
     * @param iterations number of warm-up + measurement iterations
     */
    public static BenchmarkResult run(int nodeCount, int iterations) {
        TaskState sample = createSampleTask(nodeCount);

        // Warm up (3 iterations, not counted)
        for (int i = 0; i < 3; i++) {
            try { JACKSON.writeValueAsBytes(sample); } catch (Exception ignored) {}
            KRYO.serialize(sample);
            KRYO.serializeCompressed(sample);
        }

        // Jackson benchmark
        long jacksonStart = System.nanoTime();
        byte[] jacksonBytes = null;
        for (int i = 0; i < iterations; i++) {
            try {
                jacksonBytes = JACKSON.writeValueAsBytes(sample);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        long jacksonNanos = (System.nanoTime() - jacksonStart) / iterations;

        // Kryo benchmark
        long kryoStart = System.nanoTime();
        byte[] kryoBytes = null;
        for (int i = 0; i < iterations; i++) {
            kryoBytes = KRYO.serialize(sample);
        }
        long kryoNanos = (System.nanoTime() - kryoStart) / iterations;

        // Kryo + gzip benchmark
        long kryoGzipStart = System.nanoTime();
        byte[] kryoGzipBytes = null;
        for (int i = 0; i < iterations; i++) {
            kryoGzipBytes = KRYO.serializeCompressed(sample);
        }
        long kryoGzipNanos = (System.nanoTime() - kryoGzipStart) / iterations;

        return new BenchmarkResult(
                nodeCount, iterations,
                jacksonBytes != null ? jacksonBytes.length : 0, jacksonNanos,
                kryoBytes != null ? kryoBytes.length : 0, kryoNanos,
                kryoGzipBytes != null ? kryoGzipBytes.length : 0, kryoGzipNanos
        );
    }

    public static void printReport(int nodeCount, int iterations) {
        BenchmarkResult r = run(nodeCount, iterations);
        log.info("=== Serialization Benchmark ({} nodes, {} iterations) ===", nodeCount, iterations);
        log.info("Jackson:       {} bytes, {} μs", r.jacksonSize, r.jacksonTimeUs());
        log.info("Kryo:          {} bytes ({}x), {} μs ({}x)", r.kryoSize, String.format("%.2f", r.kryoSizeRatio()),
                r.kryoTimeUs(), String.format("%.2f", r.kryoSpeedup()));
        log.info("Kryo+gzip:     {} bytes ({}x), {} μs ({}x)", r.kryoGzipSize, String.format("%.2f", r.kryoGzipSizeRatio()),
                r.kryoGzipTimeUs(), String.format("%.2f", r.kryoGzipSpeedup()));
    }

    public record BenchmarkResult(
            int nodeCount,
            int iterations,
            int jacksonSize,
            long jacksonNanos,
            int kryoSize,
            long kryoNanos,
            int kryoGzipSize,
            long kryoGzipNanos
    ) {
        public double kryoSpeedup() { return (double) jacksonNanos / kryoNanos; }
        public double kryoGzipSpeedup() { return (double) jacksonNanos / kryoGzipNanos; }
        public double kryoSizeRatio() { return (double) jacksonSize / kryoSize; }
        public double kryoGzipSizeRatio() { return (double) jacksonSize / kryoGzipSize; }
        public long jacksonTimeUs() { return jacksonNanos / 1000; }
        public long kryoTimeUs() { return kryoNanos / 1000; }
        public long kryoGzipTimeUs() { return kryoGzipNanos / 1000; }
    }

    public static void main(String[] args) {
        printReport(100, 100);
    }
}
