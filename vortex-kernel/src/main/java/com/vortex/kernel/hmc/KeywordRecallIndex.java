package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight lexical retriever used as the keyword branch of hybrid recall.
 *
 * The implementation intentionally stays local and dependency-free: it scores
 * query/content token overlap with an IDF-style rarity boost, then lets the
 * recall reranker combine lexical and semantic evidence.
 */
@Component
public class KeywordRecallIndex {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}_:-]+");

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
        if (text == null || text.isBlank()) {
            return new HashSet<>();
        }
        Set<String> terms = new HashSet<>();
        for (String raw : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            String token = raw.trim();
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    public record KeywordCandidate(MemoryFragment fragment, double score, List<String> matchedTerms) {
    }
}
