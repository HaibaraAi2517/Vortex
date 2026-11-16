package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import com.vortex.storage.local.LexicalAnalysis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight lexical retriever used as the keyword branch of hybrid recall.
 *
 * Scores transient L1 writes and the external-store compatibility scan.
 * Durable local memory uses Lucene BM25 directly. Both paths share CJK analysis.
 */
@Component
public class KeywordRecallIndex {

    public List<KeywordCandidate> search(String query, List<MemoryFragment> candidates, int limit) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }

        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        List<MemoryFragment> nonNullCandidates = candidates.stream()
                .filter(Objects::nonNull)
                .toList();
        Map<String, Integer> documentFrequency = documentFrequency(nonNullCandidates);
        int documentCount = Math.max(1, nonNullCandidates.size());

        return nonNullCandidates.stream()
                .map(fragment -> score(fragment, queryTerms, documentFrequency, documentCount))
                .filter(candidate -> candidate.score() > 0.0)
                .sorted(Comparator.comparingDouble(KeywordCandidate::score).reversed())
                .limit(limit)
                .toList();
    }

    private KeywordCandidate score(
            MemoryFragment fragment,
            Set<String> queryTerms,
            Map<String, Integer> documentFrequency,
            int documentCount) {
        Set<String> fragmentTerms = tokenize(fragment.getContent());
        if (fragment.getTags() != null) {
            fragment.getTags().forEach(tag -> fragmentTerms.addAll(tokenize(tag)));
        }

        double score = 0.0;
        List<String> matchedTerms = new ArrayList<>();
        for (String term : queryTerms) {
            if (!fragmentTerms.contains(term)) {
                continue;
            }
            int df = Math.max(1, documentFrequency.getOrDefault(term, 1));
            double idf = Math.log(1.0 + ((double) documentCount / df));
            score += idf;
            matchedTerms.add(term);
        }
        double coverage = matchedTerms.isEmpty() ? 0.0 : (double) matchedTerms.size() / queryTerms.size();
        score += coverage;
        return new KeywordCandidate(fragment, score, List.copyOf(matchedTerms));
    }

    private Map<String, Integer> documentFrequency(List<MemoryFragment> candidates) {
        Map<String, Integer> frequency = new HashMap<>();
        for (MemoryFragment candidate : candidates) {
            Set<String> terms = tokenize(candidate.getContent());
            if (candidate.getTags() != null) {
                candidate.getTags().forEach(tag -> terms.addAll(tokenize(tag)));
            }
            terms.forEach(term -> frequency.merge(term, 1, Integer::sum));
        }
        return frequency;
    }

    static Set<String> tokenize(String text) {
        return LexicalAnalysis.terms(text);
    }

    public record KeywordCandidate(MemoryFragment fragment, double score, List<String> matchedTerms) {
    }
}
