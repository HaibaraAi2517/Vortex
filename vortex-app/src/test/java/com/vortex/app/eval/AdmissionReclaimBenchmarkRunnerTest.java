package com.vortex.app.eval;

import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.hmc.AdaptiveWeightLearner;
import com.vortex.kernel.hmc.EvictionDecisionLogger;
import com.vortex.kernel.hmc.EvictionRegretTracker;
import com.vortex.kernel.hmc.FragmentPersistenceManager;
import com.vortex.kernel.hmc.FragmentPinManager;
import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.kernel.hmc.NamespaceQuotaManager;
import com.vortex.kernel.hmc.SemanticEvictionPolicy;
import com.vortex.kernel.hmc.TieredEvictionCoordinator;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import com.vortex.storage.l1.CaffeineHotStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdmissionReclaimBenchmarkRunnerTest {

    @Test
    void measuresSingletonAndReasoningChainReclaimPhases() {
        CaffeineHotStore l1 = new CaffeineHotStore(64);
        MemorySloTracker tracker = new MemorySloTracker(new SimpleMeterRegistry());
        tracker.bind();
        NamespaceQuotaManager quotaManager = new NamespaceQuotaManager(0.25, 0.15, 16);
        FragmentPersistenceManager persistenceManager = mock(FragmentPersistenceManager.class);
        when(persistenceManager.awaitQuiescence(any())).thenReturn(true);
        L2WarmStore l2 = mock(L2WarmStore.class);
        L3ColdStore l3 = mock(L3ColdStore.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingService> cloudProvider = mock(ObjectProvider.class);
        when(cloudProvider.getIfAvailable()).thenReturn(null);
        FragmentPinManager pinManager = new FragmentPinManager(
                l1,
                l2,
                l3,
                persistenceManager,
                embeddingService,
                cloudProvider,
                null);
        TieredEvictionCoordinator coordinator = new TieredEvictionCoordinator(
                l1,
                new SemanticEvictionPolicy(0.3, 0.5, 0.2),
                quotaManager,
                new EvictionDecisionLogger(tracker),
                new EvictionRegretTracker(3_600_000L),
                tracker,
                persistenceManager,
                mock(AdaptiveWeightLearner.class),
                pinManager,
                2.0,
                300_000,
                64,
                2);

        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReclaimBenchmarkSingletonParallelismLevels(List.of(1, 2));
        properties.setReclaimBenchmarkSingletonOperationsPerThread(2);
        properties.setReclaimBenchmarkWarmupOperationsPerThread(1);
        properties.setReclaimBenchmarkReasoningChainSizes(List.of(2, 3));
        properties.setReclaimBenchmarkChainOperationsPerThread(1);
        properties.setReclaimBenchmarkResidentFragments(32);
        properties.setReclaimBenchmarkEmbeddingDimensions(4);
        properties.setReclaimBenchmarkNamespaceCount(4);
        properties.setReclaimBenchmarkTimeout(Duration.ofSeconds(5));

        AdmissionReclaimBenchmarkRunner runner = new AdmissionReclaimBenchmarkRunner(
                coordinator,
                l1,
                l1,
                quotaManager,
                tracker,
                persistenceManager,
                properties);

        AdmissionReclaimBenchmarkReport report = runner.runConfiguredBenchmark();

        assertThat(report.getResults()).hasSize(4);
        assertThat(report.getResults()).allSatisfy(result -> {
            assertThat(result.getResidentFragmentsBefore()).isEqualTo(32);
            assertThat(result.getAdmitted()).isEqualTo(result.getAttempted());
            assertThat(result.getActualEvictedFragments())
                    .isEqualTo(result.getExpectedEvictedFragments());
            assertThat(result.isCapacityInvariantSatisfied()).isTrue();
            assertThat(result.getDetailedSnapshotCount()).isGreaterThanOrEqualTo(result.getAttempted());
            assertThat(result.getDetailedSnapshotLockHoldAverageMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(result.getDetailedSnapshotFreezeAverageMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(result.getPlanningGateWaitCount()).isEqualTo(result.getAttempted());
            assertThat(result.getPlanningGateWaitTotalMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(result.getPlanningGateWaitAverageMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(result.getPlanningAverageMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(result.getCommitLockHoldAverageMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(result.getErrors()).isZero();
        });
        assertThat(report.getResults())
                .extracting(AdmissionReclaimBenchmarkReport.ScenarioResult::getVictimGroupSize)
                .containsExactly(1, 1, 2, 3);
        assertThat(l1.allFragments()).isEmpty();
        assertThat(l1.currentTokenCount()).isZero();
    }

    @Test
    void setupFailureCleansPartiallyLoadedNamespaces() {
        FailingCaffeineHotStore l1 = new FailingCaffeineHotStore(16, 3);
        NamespaceQuotaManager quotaManager = new NamespaceQuotaManager(1.0, 1.0, 1);
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setReclaimBenchmarkSingletonParallelismLevels(List.of(1));
        properties.setReclaimBenchmarkSingletonOperationsPerThread(1);
        properties.setReclaimBenchmarkWarmupOperationsPerThread(0);
        properties.setReclaimBenchmarkReasoningChainSizes(List.of());
        properties.setReclaimBenchmarkResidentFragments(8);
        properties.setReclaimBenchmarkEmbeddingDimensions(4);
        properties.setReclaimBenchmarkNamespaceCount(1);

        AdmissionReclaimBenchmarkRunner runner = new AdmissionReclaimBenchmarkRunner(
                mock(TieredEvictionCoordinator.class),
                l1,
                l1,
                quotaManager,
                new MemorySloTracker(new SimpleMeterRegistry()),
                mock(FragmentPersistenceManager.class),
                properties);

        assertThatThrownBy(runner::runConfiguredBenchmark)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected benchmark setup failure");
        assertThat(l1.allFragments()).isEmpty();
        assertThat(l1.currentTokenCount()).isZero();
    }

    private static final class FailingCaffeineHotStore extends CaffeineHotStore {
        private final AtomicInteger puts = new AtomicInteger();
        private final int failingPut;

        private FailingCaffeineHotStore(long maxTokens, int failingPut) {
            super(maxTokens);
            this.failingPut = failingPut;
        }

        @Override
        public void put(MemoryFragment fragment, boolean recordAccess) {
            if (puts.incrementAndGet() == failingPut) {
                throw new IllegalStateException("injected benchmark setup failure");
            }
            super.put(fragment, recordAccess);
        }
    }
}
