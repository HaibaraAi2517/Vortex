package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RedundancyAnalyzer {

    public Map<String, RedundancyStats> computeRedundancyStats(List<MemoryFragment> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, RedundancyStats> stats = new HashMap<>();
        for (MemoryFragment fragment : candidates) {
            double maxPenalty = 0.0;
            double minNovelty = Double.POSITIVE_INFINITY;
            boolean hasPeer = false;
            for (MemoryFragment other : candidates) {
                if (other == fragment) {
                    continue;
                }
                hasPeer = true;
                maxPenalty = Math.max(maxPenalty, fragment.redundancyPenaltyAgainst(other));
                minNovelty = Math.min(minNovelty, fragment.noveltyBonusAgainst(other));
            }
            stats.put(fragment.getId(), new RedundancyStats(
                    maxPenalty,
                    hasPeer ? minNovelty : 0.0));
        }
        return stats;
    }

    public record RedundancyStats(double redundancyPenalty, double noveltyBonus) {}

    public static final class IncrementalRedundancyState {
        private final List<MemoryFragment> fragments = new ArrayList<>();
        private final Map<String, RedundancyStats> stats = new HashMap<>();

        public static IncrementalRedundancyState from(List<MemoryFragment> initial) {
            IncrementalRedundancyState state = new IncrementalRedundancyState();
            for (MemoryFragment fragment : initial) {
                state.add(fragment);
            }
            return state;
        }

        public void add(MemoryFragment candidate) {
            double candidateMaxPenalty = 0.0;
            double candidateMinNovelty = Double.POSITIVE_INFINITY;
            boolean hasPeer = false;
            for (MemoryFragment existing : fragments) {
                hasPeer = true;
                double candidatePenalty = candidate.redundancyPenaltyAgainst(existing);
                double candidateNovelty = candidate.noveltyBonusAgainst(existing);
                candidateMaxPenalty = Math.max(candidateMaxPenalty, candidatePenalty);
                candidateMinNovelty = Math.min(candidateMinNovelty, candidateNovelty);

                RedundancyStats previous = stats.getOrDefault(existing.getId(), new RedundancyStats(0.0, 0.0));
                double updatedPenalty = Math.max(previous.redundancyPenalty(), existing.redundancyPenaltyAgainst(candidate));
                double updatedNovelty = fragments.size() == 1
                        ? existing.noveltyBonusAgainst(candidate)
                        : Math.min(previous.noveltyBonus(), existing.noveltyBonusAgainst(candidate));
                stats.put(existing.getId(), new RedundancyStats(updatedPenalty, updatedNovelty));
            }
            stats.put(candidate.getId(), new RedundancyStats(candidateMaxPenalty, hasPeer ? candidateMinNovelty : 0.0));
            fragments.add(candidate);
        }

        public Map<String, RedundancyStats> snapshot() {
            return Map.copyOf(stats);
        }
    }
}
