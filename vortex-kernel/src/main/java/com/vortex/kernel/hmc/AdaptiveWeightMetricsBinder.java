package com.vortex.kernel.hmc;

import com.vortex.common.dto.MemoryScenario;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveWeightMetricsBinder {

    private final MeterRegistry meterRegistry;
    private final AdaptiveWeightLearner learner;

    public AdaptiveWeightMetricsBinder(MeterRegistry meterRegistry, AdaptiveWeightLearner learner) {
        this.meterRegistry = meterRegistry;
        this.learner = learner;
    }

    @PostConstruct
    public void bind() {
        for (MemoryScenario scenario : MemoryScenario.values()) {
            String scenarioTag = scenario.name().toLowerCase();
            registerProfileWeightGauge(scenario, scenarioTag, "active", "alpha");
            registerProfileWeightGauge(scenario, scenarioTag, "active", "beta");
            registerProfileWeightGauge(scenario, scenarioTag, "active", "gamma");
            registerProfileWeightGauge(scenario, scenarioTag, "shadow", "alpha");
            registerProfileWeightGauge(scenario, scenarioTag, "shadow", "beta");
            registerProfileWeightGauge(scenario, scenarioTag, "shadow", "gamma");
            registerProfileMetricGauge(scenario, scenarioTag, "grounding", "active");
            registerProfileMetricGauge(scenario, scenarioTag, "grounding", "shadow");
            registerProfileMetricGauge(scenario, scenarioTag, "grounding", "baseline");
            registerProfileMetricGauge(scenario, scenarioTag, "selection.precision", "active");
            registerProfileMetricGauge(scenario, scenarioTag, "selection.precision", "shadow");
            registerProfileMetricGauge(scenario, scenarioTag, "selection.precision", "baseline");
            registerProfileMetricGauge(scenario, scenarioTag, "selection.coverage", "active");
            registerProfileMetricGauge(scenario, scenarioTag, "selection.coverage", "shadow");
            registerProfileMetricGauge(scenario, scenarioTag, "selection.coverage", "baseline");
            registerProfileMetricGauge(scenario, scenarioTag, "reward", "active");
            registerProfileMetricGauge(scenario, scenarioTag, "reward", "shadow");
            registerProfileMetricGauge(scenario, scenarioTag, "reward", "baseline");
            registerScenarioGauge("vortex.hmc.learning.exploration", scenario, scenarioTag,
                    metrics -> metrics.exploration());
            registerScenarioGauge("vortex.hmc.learning.total.recalls", scenario, scenarioTag,
                    metrics -> metrics.totalRecalls());
            registerScenarioGauge("vortex.hmc.learning.pending.sessions", scenario, scenarioTag,
                    metrics -> metrics.pendingRecallSessions());
            registerScenarioGauge("vortex.hmc.learning.feedback.answer.reward", scenario, scenarioTag,
                    metrics -> metrics.answerReward());
            registerScenarioGauge("vortex.hmc.learning.feedback.regret.penalty", scenario, scenarioTag,
                    metrics -> metrics.regretPenalty());
            registerScenarioGauge("vortex.hmc.learning.shadow.relative.lift", scenario, scenarioTag,
                    metrics -> metrics.shadowEvaluation().relativeLift());
            registerScenarioGauge("vortex.hmc.learning.baseline.relative.lift", scenario, scenarioTag,
                    metrics -> metrics.shadowEvaluation().baselineRelativeLift());
            registerScenarioGauge("vortex.hmc.learning.shadow.sample.count", scenario, scenarioTag,
                    metrics -> metrics.shadowEvaluation().sampleCount());
        }
    }

    private void registerProfileWeightGauge(MemoryScenario scenario, String scenarioTag, String profile, String component) {
        Gauge.builder("vortex.hmc.learning.weight", learner,
                        currentLearner -> weightValue(currentLearner.metricsSnapshot(scenario), profile, component))
                .tags(Tags.of("scenario", scenarioTag, "profile", profile, "component", component))
                .register(meterRegistry);
    }

    private void registerProfileMetricGauge(MemoryScenario scenario, String scenarioTag, String metric, String profile) {
        Gauge.builder("vortex.hmc.learning.feedback." + metric, learner,
                        currentLearner -> profileMetricValue(currentLearner.metricsSnapshot(scenario), metric, profile))
                .tags(Tags.of("scenario", scenarioTag, "profile", profile))
                .register(meterRegistry);
    }

    private void registerScenarioGauge(
            String meterName,
            MemoryScenario scenario,
            String scenarioTag,
            java.util.function.ToDoubleFunction<AdaptiveWeightLearner.LearningMetricsSnapshot> extractor) {
        Gauge.builder(meterName, learner, currentLearner -> extractor.applyAsDouble(currentLearner.metricsSnapshot(scenario)))
                .tags(Tags.of("scenario", scenarioTag))
                .register(meterRegistry);
    }

    private double weightValue(AdaptiveWeightLearner.LearningMetricsSnapshot metrics, String profile, String component) {
        AdaptiveWeightProfile target = "shadow".equals(profile) ? metrics.shadow() : metrics.active();
        if (target == null) {
            return 0.0;
        }
        return switch (component) {
            case "alpha" -> target.getAlpha();
            case "beta" -> target.getBeta();
            default -> target.getGamma();
        };
    }

    private double profileMetricValue(AdaptiveWeightLearner.LearningMetricsSnapshot metrics, String metric, String profile) {
        return switch (metric) {
            case "grounding" -> switch (profile) {
                case "active" -> metrics.activeGrounding();
                case "shadow" -> metrics.shadowGrounding();
                default -> metrics.baselineGrounding();
            };
            case "selection.precision" -> switch (profile) {
                case "active" -> metrics.activeSelectionPrecision();
                case "shadow" -> metrics.shadowSelectionPrecision();
                default -> metrics.baselineSelectionPrecision();
            };
            case "selection.coverage" -> switch (profile) {
                case "active" -> metrics.activeSelectionCoverage();
                case "shadow" -> metrics.shadowSelectionCoverage();
                default -> metrics.baselineSelectionCoverage();
            };
            default -> switch (profile) {
                case "active" -> metrics.activeReward();
                case "shadow" -> metrics.shadowReward();
                default -> metrics.baselineReward();
            };
        };
    }
}
