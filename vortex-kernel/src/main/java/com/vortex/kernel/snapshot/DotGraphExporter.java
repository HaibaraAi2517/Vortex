package com.vortex.kernel.snapshot;

import com.vortex.common.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

/**
 * Exports a DAG as Graphviz DOT format for visualization.
 *
 * Node styling:
 *   THOUGHT     → ellipse, blue
 *   ACTION      → box, green
 *   OBSERVATION → hexagon, yellow
 *   FORK        → diamond, orange
 *   JOIN        → invtriangle, purple
 *   MERGE       → doubleoctagon, red
 *
 * Edge styling:
 *   CONTROL_DEP → solid line
 *   DATA_DEP    → dashed line
 *   BRANCH      → dotted line
 *
 * Completed nodes get bold borders; failed nodes get red fill.
 */
@Slf4j
@Component
public class DotGraphExporter {

    /**
     * Export a task's DAG to DOT format.
     */
    public String export(DagGraph graph, String taskId) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph Task_").append(sanitize(taskId)).append(" {\n");
        sb.append("  rankdir=TB;\n");
        sb.append("  label=\"Task: ").append(taskId).append("\";\n");
        sb.append("  fontsize=14;\n");
        sb.append("  node [fontsize=11];\n");
        sb.append("  edge [fontsize=9];\n");
        sb.append("\n");

        // Export nodes
        for (DagNode node : graph.getNodes().values()) {
            sb.append("  ").append(quote(node.getNodeId()));
            sb.append(" [").append(nodeAttributes(node)).append("];\n");
        }

        sb.append("\n");

        // Export edges
        List<DagEdge> edges;
        synchronized (graph.getEdges()) {
            edges = List.copyOf(graph.getEdges());
        }
        for (DagEdge edge : edges) {
            sb.append("  ").append(quote(edge.getSourceNodeId()))
                    .append(" -> ").append(quote(edge.getTargetNodeId()));
            sb.append(" [").append(edgeAttributes(edge)).append("]");
            if (edge.getCondition() != null && !edge.getCondition().isEmpty()) {
                sb.append(" [label=\"").append(escapeDot(edge.getCondition())).append("\"]");
            }
            sb.append(";\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Export a checkpoint timeline as DOT format.
     */
    public String exportCheckpointTimeline(String taskId, List<CheckpointMetadata> checkpoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph CheckpointTimeline_").append(sanitize(taskId)).append(" {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  label=\"Checkpoint Timeline: ").append(taskId).append("\";\n");
        sb.append("  node [shape=box, fontsize=10];\n");
        sb.append("  edge [style=dotted];\n");
        sb.append("\n");

        String previousId = null;
        for (CheckpointMetadata meta : checkpoints) {
            String nodeId = "cp_" + meta.getCheckpointId().substring(0, 8);
            String color = meta.getType() == CheckpointMetadata.CheckpointType.FULL ? "green" : "orange";
            String label = String.format("%s\\nseq=%d\\nnodes=%d\\n%s",
                    meta.getType(), meta.getSequenceNumber(), meta.getNodeCount(),
                    meta.getCreatedAt() != null ? meta.getCreatedAt().toString().substring(11, 19) : "?");
            sb.append("  ").append(nodeId).append(" [color=").append(color)
                    .append(", label=\"").append(label).append("\"];\n");

            if (previousId != null) {
                sb.append("  ").append(previousId).append(" -> ").append(nodeId).append(";\n");
            }
            previousId = nodeId;
        }

        sb.append("}\n");
        return sb.toString();
    }

    // ---- Internal ----

    private String nodeAttributes(DagNode node) {
        StringJoiner attrs = new StringJoiner(", ");

        // Shape
        String shape = switch (node.getType()) {
            case ACTION -> "box";
            case OBSERVATION -> "hexagon";
            case FORK -> "diamond";
            case JOIN -> "invtriangle";
            case MERGE -> "doubleoctagon";
            default -> "ellipse";
        };
        attrs.add("shape=" + shape);

        // Color
        String color = switch (node.getType()) {
            case ACTION -> "green";
            case OBSERVATION -> "yellow";
            case FORK -> "orange";
            case JOIN -> "purple";
            case MERGE -> "red";
            default -> "lightblue";
        };
        attrs.add("style=filled");
        attrs.add("fillcolor=" + color);

        // Status overrides
        if (node.getStatus() == DagNode.NodeStatus.COMPLETED) {
            attrs.add("penwidth=3");
        } else if (node.getStatus() == DagNode.NodeStatus.FAILED) {
            attrs.add("fillcolor=tomato");
            attrs.add("penwidth=2");
        } else if (node.getStatus() == DagNode.NodeStatus.EXECUTING) {
            attrs.add("penwidth=2");
            attrs.add("style=\"filled,dashed\"");
        }

        // Label
        String label = truncate(node.getContent(), 50);
        if (node.getResult() != null && !node.getResult().isEmpty()) {
            label += "\\n→ " + truncate(node.getResult(), 30);
        }
        attrs.add("label=\"" + escapeDot(label) + "\"");

        // Tooltip
        String tooltip = node.getNodeId() + ": " + node.getType() + " [" + node.getStatus() + "]";
        attrs.add("tooltip=\"" + escapeDot(tooltip) + "\"");

        return attrs.toString();
    }

    private String edgeAttributes(DagEdge edge) {
        String style = switch (edge.getDependencyType()) {
            case DATA_DEP -> "dashed";
            case BRANCH -> "dotted";
            default -> "solid";
        };
        return "style=" + style;
    }

    private String quote(String s) {
        return "\"" + sanitize(s) + "\"";
    }

    private String sanitize(String s) {
        if (s == null) return "null";
        return s.replace("-", "_").replace(".", "_");
    }

    private String escapeDot(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return escapeDot(s);
        return escapeDot(s.substring(0, maxLen)) + "...";
    }
}
