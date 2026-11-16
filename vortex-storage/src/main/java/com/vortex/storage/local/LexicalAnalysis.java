package com.vortex.storage.local;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** CJK bigrams plus normalized Latin words; query text is never parsed as Lucene syntax. */
public final class LexicalAnalysis {
    private LexicalAnalysis() {}

    public static Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return result;
        try (var analyzer = new CJKAnalyzer(); TokenStream stream = analyzer.tokenStream("text", text)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) result.add(term.toString());
            stream.end();
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
