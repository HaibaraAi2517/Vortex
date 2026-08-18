package com.vortex.app.eval;

import com.vortex.kernel.hmc.MemorySloTracker;
import com.vortex.kernel.hmc.NamespaceQuotaManager;
import com.vortex.kernel.hmc.TieredEvictionCoordinator;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L1HotStoreAdmin;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdmissionContentionBenchmarkRunnerTest {

    @Test
    void runsConfiguredParallelismLevelsAndReportsAdmissionMetrics() {
        TieredEvictionCoordinator coordinator = mock(TieredEvictionCoordinator.class);
        L1HotStore l1 = mock(L1HotStore.class);
        L1HotStoreAdmin l1Admin = mock(L1HotStoreAdmin.class);
        MemorySloTracker tracker = new MemorySloTracker(new SimpleMeterRegistry());
        LlmMemoryEvalProperties properties = new LlmMemoryEvalProperties();
        properties.setAdmissionBenchmarkParallelismLevels(List.of(2, 1, 2));
        properties.setAdmissionBenchmarkOperationsPerThread(3);
        properties.setAdmissionBenchmarkWarmupOperationsPerThread(1);
        properties.setAdmissionBenchmarkTokenCount(1);
        properties.setAdmissionBenchmarkTimeout(Duration.ofSeconds(5));

        when(l1.maxTokenCapacity()).thenReturn(8_192L);
        when(l1.currentTokenCount()).thenReturn(0L);
        when(l1Admin.allFragments()).thenReturn(List.of());
        when(coordinator.admitToL1(any(), anyString())).thenAnswer(invocation -> {
            tracker.recordAdmissionRequest();
            tracker.recordAdmissionDirectAttempt();
            tracker.recordAdmissionDirectCommit();
            tracker.recordAdmissionLockWait(100_000L);
            tracker.recordAdmissionLockHold(200_000L);
            return true;
        });
        when(coordinator.removeFromL1(anyString())).thenReturn(true);

        AdmissionContentionBenchmarkRunner runner = new AdmissionContentionBenchmarkRunner(
                coordinator,
                l1,
                l1Admin,
                new NamespaceQuotaManager(0.25, 0.15, 256),
                tracker,
                properties);

        AdmissionContentionBenchmarkReport report = runner.runConfiguredBenchmark();

        assertThat(report.getParallelismLevels()).containsExactly(1, 2);
        assertThat(report.getResults()).hasSize(2);
        assertThat(report.getResults().get(0).getAttempted()).isEqualTo(3);
        assertThat(report.getResults().get(0).getAdmitted()).isEqualTo(3);
        assertThat(report.getResults().get(0).getAdmissionRequests()).isEqualTo(3);
        assertThat(report.getResults().get(0).getDirectAttempts()).isEqualTo(3);
        assertThat(report.getResults().get(0).getDirectCommits()).isEqualTo(3);
        assertThat(report.getResults().get(0).getOptimisticAttempts()).isZero();
        assertThat(report.getResults().get(0).getLockAcquisitionsPerRequest()).isEqualTo(1.0);
        assertThat(report.getResults().get(0).getLockWaitAverageMs()).isCloseTo(0.1, within(0.0001));
        assertThat(report.getResults().get(0).getLockHoldAverageMs()).isCloseTo(0.2, within(0.0001));
        assertThat(report.getResults().get(0).getPlanningAverageMs()).isZero();
        assertThat(report.getResults().get(1).getAttempted()).isEqualTo(6);
        assertThat(report.getResults()).allSatisfy(result -> {
            assertThat(result.getErrors()).isZero();
            assertThat(result.getSuccessRate()).isEqualTo(1.0);
            assertThat(result.getThroughputPerSecond()).isPositive();
        });
        verify(coordinator, org.mockito.Mockito.times(12)).removeFromL1(anyString());
    }
}
