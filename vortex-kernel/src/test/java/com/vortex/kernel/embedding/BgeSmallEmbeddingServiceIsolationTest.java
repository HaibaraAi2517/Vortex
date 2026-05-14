package com.vortex.kernel.embedding;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BgeSmallEmbeddingServiceIsolationTest {

    @Test
    void embedAsyncDoesNotPinVirtualThread() throws Exception {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int requestCount = Math.max(100, availableProcessors * 3);
        BlockingEmbeddingService service = new BlockingEmbeddingService(availableProcessors);
        Queue<float[]> results = new ConcurrentLinkedQueue<>();
        List<Thread> callers = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                int index = i;
                callers.add(Thread.ofVirtual().unstarted(() ->
                        results.add(service.embedAsync("request-" + index).join())));
            }

            callers.forEach(Thread::start);
            assertThat(service.awaitWorkers()).isTrue();
            assertThat(service.jniPoolActiveCountForTest()).isLessThanOrEqualTo(availableProcessors);
            assertThat(service.sawVirtualWorker()).isFalse();
            assertThat(service.workerThreadNames()).allMatch(name -> name.startsWith("onnx-jni-"));

            service.releaseWorkers();

            for (Thread caller : callers) {
                caller.join(TimeUnit.SECONDS.toMillis(5));
            }

            assertThat(results).hasSize(requestCount);
        } finally {
            service.close();
        }
    }

    private static final class BlockingEmbeddingService extends BgeSmallEmbeddingService {
        private final CountDownLatch workersEntered;
        private final CountDownLatch releaseWorkers = new CountDownLatch(1);
        private final Queue<String> workerThreadNames = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean sawVirtualWorker = new AtomicBoolean(false);

        private BlockingEmbeddingService(int expectedConcurrentWorkers) {
            super("unused");
            this.workersEntered = new CountDownLatch(expectedConcurrentWorkers);
        }

        @Override
        public void init() {
            // This test only verifies executor isolation for embedAsync.
            // Model loading must stay disabled even if parent initialization changes.
        }

        @Override
        public float[] embed(String text) {
            workerThreadNames.add(Thread.currentThread().getName());
            if (Thread.currentThread().isVirtual()) {
                sawVirtualWorker.set(true);
            }
            workersEntered.countDown();
            try {
                if (!releaseWorkers.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release JNI workers");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting in test worker", e);
            }
            return new float[]{1.0f, 0.0f, 0.0f, 0.0f};
        }

        boolean awaitWorkers() throws InterruptedException {
            return workersEntered.await(5, TimeUnit.SECONDS);
        }

        void releaseWorkers() {
            releaseWorkers.countDown();
        }

        Queue<String> workerThreadNames() {
            return workerThreadNames;
        }

        boolean sawVirtualWorker() {
            return sawVirtualWorker.get();
        }
    }
}
