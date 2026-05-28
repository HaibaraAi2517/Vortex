package com.vortex.common.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.vortex.common.model.DagEdge;
import com.vortex.common.model.DagGraph;
import com.vortex.common.model.DagNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom Kryo serializer for DagGraph that only serializes core data
 * (nodes map and edges list), skipping the transient cached adjacency structures.
 */
public class DagGraphKryoSerializer extends Serializer<DagGraph> {

    @Override
    public void write(Kryo kryo, Output output, DagGraph graph) {
        // Write nodes: size, then each node
        Map<String, DagNode> nodes = graph.getNodes();
        output.writeInt(nodes.size());
        for (Map.Entry<String, DagNode> entry : nodes.entrySet()) {
            output.writeString(entry.getKey());
            kryo.writeObject(output, entry.getValue());
        }

        // Write edges: size, then each edge (must synchronize on edges list)
        List<DagEdge> edges = new ArrayList<>(graph.edgeSnapshot());
        output.writeInt(edges.size());
        for (DagEdge edge : edges) {
            kryo.writeObject(output, edge);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public DagGraph read(Kryo kryo, Input input, Class type) {
        DagGraph graph = new DagGraph();

        // Read nodes
        int nodeCount = input.readInt();
        Map<String, DagNode> nodes = new HashMap<>();
        for (int i = 0; i < nodeCount; i++) {
            String key = input.readString();
            DagNode node = kryo.readObject(input, DagNode.class);
            nodes.put(key, node);
        }
        graph.setNodes(nodes);

        // Read edges
        int edgeCount = input.readInt();
        List<DagEdge> edges = new ArrayList<>();
        for (int i = 0; i < edgeCount; i++) {
            edges.add(kryo.readObject(input, DagEdge.class));
        }
        graph.addEdgesUnchecked(edges);

        // Mark dirty so adjacency is rebuilt on next query
        graph.setDirty(true);

        return graph;
    }
}
