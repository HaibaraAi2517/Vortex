package com.vortex.common.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;
import com.vortex.common.model.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * High-performance Kryo serialization for DAG structures and task snapshots.
 *
 * Uses a thread-safe Kryo instance pool since Kryo is not thread-safe.
 * All known types are pre-registered with numeric IDs for maximum performance.
 *
 * Benchmarks show 5-10x faster than Jackson for complex object graphs.
 */
public class KryoSerializer {

    private static final int INITIAL_BUFFER_SIZE = 8192;
    private static final int MAX_BUFFER_SIZE = 16 * 1024 * 1024; // 16 MB

    // Numeric class IDs for maximum serialization performance
    private static final int ID_DAG_NODE = 1;
    private static final int ID_DAG_EDGE = 2;
    private static final int ID_DAG_GRAPH = 3;
    private static final int ID_TASK_STATE = 4;
    private static final int ID_TASK_BRANCH = 5;
    private static final int ID_CHECKPOINT_METADATA = 6;
    private static final int ID_ACTION_LOG_ENTRY = 7;

    private final Pool<Kryo> kryoPool = new Pool<Kryo>(true, false, 16) {
        @Override
        protected Kryo create() {
            Kryo kryo = new Kryo();
            kryo.setRegistrationRequired(false);
            registerClasses(kryo);
            return kryo;
        }
    };

    private static void registerClasses(Kryo kryo) {
        // Core model classes (custom serializer for DagGraph)
        kryo.register(DagNode.class, ID_DAG_NODE);
        kryo.register(DagEdge.class, ID_DAG_EDGE);
        kryo.register(DagGraph.class, new DagGraphKryoSerializer(), ID_DAG_GRAPH);
        kryo.register(TaskState.class, ID_TASK_STATE);
        kryo.register(TaskBranch.class, ID_TASK_BRANCH);
        kryo.register(CheckpointMetadata.class, ID_CHECKPOINT_METADATA);
        kryo.register(ActionLogEntry.class, ID_ACTION_LOG_ENTRY);
        // Java collections
        kryo.register(ArrayList.class, 10);
        kryo.register(HashMap.class, 11);
        kryo.register(java.util.concurrent.ConcurrentHashMap.class, 12);
        kryo.register(java.util.HashSet.class, 13);
        kryo.register(java.util.Collections.emptyList().getClass(), 14);
        // Java core types
        kryo.register(UUID.class, 15);
        kryo.register(Instant.class, 16);
        kryo.register(String.class, 17);
        kryo.register(float[].class, 18);
        kryo.register(double[].class, 19);
        kryo.register(int[].class, 20);
        kryo.register(long[].class, 21);
        // Enums
        kryo.register(DagNode.NodeType.class, 30);
        kryo.register(DagNode.NodeStatus.class, 31);
        kryo.register(DagEdge.EdgeType.class, 32);
        kryo.register(TaskState.TaskStatus.class, 33);
        kryo.register(TaskBranch.BranchStatus.class, 34);
        kryo.register(CheckpointMetadata.CheckpointType.class, 35);
        kryo.register(ActionLogEntry.OperationType.class, 36);
    }

    /**
     * Serialize an object to a byte array.
     */
    public byte[] serialize(Object obj) {
        Kryo kryo = kryoPool.obtain();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
             Output output = new Output(bos, MAX_BUFFER_SIZE)) {
            kryo.writeObject(output, obj);
            output.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Kryo serialization failed for " + obj.getClass().getSimpleName(), e);
        } finally {
            kryoPool.free(kryo);
        }
    }

    /**
     * Deserialize a byte array back to an object.
     */
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] data, Class<T> type) {
        Kryo kryo = kryoPool.obtain();
        try (Input input = new Input(new ByteArrayInputStream(data))) {
            return (T) kryo.readObject(input, type);
        } finally {
            kryoPool.free(kryo);
        }
    }

    /**
     * Serialize and compress with gzip.
     */
    public byte[] serializeCompressed(Object obj) {
        byte[] raw = serialize(obj);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2);
             GZIPOutputStream gzos = new GZIPOutputStream(bos)) {
            gzos.write(raw);
            gzos.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Kryo serialization + gzip failed", e);
        }
    }

    /**
     * Decompress and deserialize.
     */
    public <T> T deserializeCompressed(byte[] data, Class<T> type) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             GZIPInputStream gzis = new GZIPInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = gzis.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return deserialize(bos.toByteArray(), type);
        } catch (IOException e) {
            throw new IllegalStateException("Kryo decompress + deserialize failed", e);
        }
    }

    /**
     * Detect whether the given bytes are Kryo binary or JSON text.
     * Kryo typically starts with non-zero control bytes; JSON starts with '{' or '['.
     */
    public static boolean isKryoFormat(byte[] data) {
        if (data == null || data.length == 0) return false;
        byte first = data[0];
        return first != '{' && first != '[';
    }
}
