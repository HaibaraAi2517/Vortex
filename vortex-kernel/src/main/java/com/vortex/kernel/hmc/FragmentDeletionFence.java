package com.vortex.kernel.hmc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable deletion generations, kept separately from the bounded retry-dedup cache.
 * Old queued writes remain fenced even after restart or explicit ID reuse.
 */
final class FragmentDeletionFence {
    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, State> states = new ConcurrentHashMap<>();

    FragmentDeletionFence(Path directory) {
        this.directory = directory;
    }

    State state(String fragmentId) {
        return states.computeIfAbsent(fragmentId, this::read);
    }

    void delete(String fragmentId) {
        State previous = state(fragmentId);
        if (!previous.deleted()) {
            write(fragmentId, new State(Math.addExact(previous.generation(), 1L), true));
        }
    }

    void beginStore(String fragmentId) {
        State previous = state(fragmentId);
        if (previous.deleted()) {
            write(fragmentId, new State(previous.generation(), false));
        }
    }

    private State read(String fragmentId) {
        Path path = path(fragmentId);
        try {
            if (!Files.exists(path)) {
                return new State(0L, false);
            }
            return mapper.readValue(Files.readAllBytes(path), State.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fragment deletion fence " + path, e);
        }
    }

    private void write(String fragmentId, State state) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "fence-", ".tmp");
            byte[] bytes = mapper.writeValueAsBytes(state);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, path(fragmentId),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            states.put(fragmentId, state);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist fragment deletion fence " + fragmentId, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A leftover staging file is never read as a committed fence.
                }
            }
        }
    }

    private Path path(String fragmentId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(fragmentId.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record State(long generation, boolean deleted) {
    }
}
