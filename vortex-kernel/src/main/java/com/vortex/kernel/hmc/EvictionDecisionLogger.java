package com.vortex.kernel.hmc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
public class EvictionDecisionLogger {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MemorySloTracker sloTracker;

    public EvictionDecisionLogger(MemorySloTracker sloTracker) {
        this.sloTracker = sloTracker;
    }

    public void logSemanticDecision(
            SemanticEvictionPolicy.EvictionCandidate candidate,
            String triggerNamespace,
            long targetEvictTokens) {
        sloTracker.recordEvictionDecisionAttempt();
        logDecision(EvictionDecisionLogEntry.builder()
                .fragmentId(candidate.fragment().getId())
                .fragmentNamespace(candidate.fragment().getNamespace())
                .triggerNamespace(triggerNamespace)
                .mode("semantic")
                .tokenCount(candidate.fragment().getTokenCount())
                .targetEvictTokens(targetEvictTokens)
                .recencyScore(candidate.recencyScore())
                .similarityScore(candidate.similarityScore())
                .importanceScore(candidate.importanceScore())
                .recencyContribution(candidate.recencyContribution())
                .similarityContribution(candidate.similarityContribution())
                .importanceContribution(candidate.importanceContribution())
                .redundancyPenalty(candidate.redundancyPenalty())
                .noveltyBonus(candidate.noveltyBonus())
                .totalScore(candidate.totalScore())
                .scoreDensity(candidate.density())
                .pinned(candidate.pinned())
                .reasoningChainId(candidate.reasoningChainId())
                .groupTokenCount(candidate.groupTokenCount())
                .timestamp(Instant.now())
                .build());
    }

    public void logFallbackEviction(
            SemanticEvictionPolicy.EvictionCandidate candidate,
            String triggerNamespace,
            String cause) {
        sloTracker.recordEvictionDecisionAttempt();
        logDecision(EvictionDecisionLogEntry.builder()
                .fragmentId(candidate.fragment().getId())
                .fragmentNamespace(candidate.fragment().getNamespace())
                .triggerNamespace(triggerNamespace)
                .mode("caffeine-fallback")
                .cause(cause)
                .tokenCount(candidate.fragment().getTokenCount())
                .targetEvictTokens(candidate.fragment().getTokenCount())
                .recencyScore(candidate.recencyScore())
                .similarityScore(candidate.similarityScore())
                .importanceScore(candidate.importanceScore())
                .recencyContribution(candidate.recencyContribution())
                .similarityContribution(candidate.similarityContribution())
                .importanceContribution(candidate.importanceContribution())
                .redundancyPenalty(candidate.redundancyPenalty())
                .noveltyBonus(candidate.noveltyBonus())
                .totalScore(candidate.totalScore())
                .scoreDensity(candidate.density())
                .pinned(candidate.pinned())
                .reasoningChainId(candidate.reasoningChainId())
                .groupTokenCount(candidate.groupTokenCount())
                .timestamp(Instant.now())
                .build());
    }

    protected void logDecision(EvictionDecisionLogEntry entry) {
        sloTracker.recordEvictionDecisionLogged();
        try {
            log.info("eviction-decision {}", objectMapper.writeValueAsString(entry));
        } catch (JsonProcessingException e) {
            log.info("eviction-decision fragmentId={} namespace={} mode={} cause={} totalScore={}",
                    entry.fragmentId, entry.fragmentNamespace, entry.mode, entry.cause, entry.totalScore);
        }
    }

    @Builder
    protected record EvictionDecisionLogEntry(
            String fragmentId,
            String fragmentNamespace,
            String triggerNamespace,
            String mode,
            String cause,
            int tokenCount,
            long targetEvictTokens,
            double recencyScore,
            double similarityScore,
            double importanceScore,
            double recencyContribution,
            double similarityContribution,
            double importanceContribution,
            double redundancyPenalty,
            double noveltyBonus,
            double totalScore,
            double scoreDensity,
            boolean pinned,
            String reasoningChainId,
            long groupTokenCount,
            Instant timestamp) {
    }
}
