package com.vortex.kernel.snapshot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses and decompresses checkpoint data.
 *
 * Currently supports gzip. Designed to be extensible to snappy, lz4, zstd in the future.
 */
@Slf4j
@Component
public class CheckpointCompressor {

    private static final int BUFFER_SIZE = 8192;

    /**
     * Compress data using gzip.
     */
    public byte[] compress(byte[] data) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2);
             GZIPOutputStream gzos = new GZIPOutputStream(bos)) {
            gzos.write(data);
            gzos.finish();
            byte[] result = bos.toByteArray();
            log.debug("Compressed {} bytes → {} bytes ({:.1f}x)",
                    data.length, result.length, (double) data.length / result.length);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Gzip compression failed", e);
        }
    }

    /**
     * Decompress gzip data.
     */
    public byte[] decompress(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             GZIPInputStream gzis = new GZIPInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = gzis.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            byte[] result = bos.toByteArray();
            log.debug("Decompressed {} bytes → {} bytes", data.length, result.length);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Gzip decompression failed", e);
        }
    }
}
