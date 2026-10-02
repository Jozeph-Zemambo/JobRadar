package io.github.jozephzemambo.jobradar.scoring;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A posting's text reduced to sets of 1- to 3-word phrases, so checking whether it mentions a skill is a hash
 * lookup instead of a regex scan. Matching ~70 skills against a 5 KB description becomes one pass over the text.
 *
 * <p>Tokens keep {@code + # . -} inside them, so "C++", "C#", "Node.js" and "go-to-market" survive as single
 * tokens. That last one matters: it stops "Go-to-market" (common in sales postings) from counting as the Go
 * language. Trailing sentence punctuation is stripped ("Java." becomes "java").
 */
public final class TextTokens {

    static final int MAX_NGRAM = 3;

    private final Set<String> lowerPhrases;
    private final Set<String> exactCasePhrases;

    private TextTokens(Set<String> lowerPhrases, Set<String> exactCasePhrases) {
        this.lowerPhrases = lowerPhrases;
        this.exactCasePhrases = exactCasePhrases;
    }

    public static TextTokens of(String text) {
        List<String> tokens = tokenize(text);
        Set<String> exact = phrases(tokens);
        Set<String> lower = new HashSet<>(exact.size());
        for (String phrase : exact) {
            lower.add(phrase.toLowerCase(Locale.ROOT));
        }
        return new TextTokens(lower, exact);
    }

    /** Whether the text contains {@code phrase} (already normalized with {@link #normalizePhrase}). */
    public boolean contains(String phrase, boolean caseSensitive) {
        return caseSensitive ? exactCasePhrases.contains(phrase) : lowerPhrases.contains(phrase);
    }

    /** Applies the same tokenization to a dictionary alias, so "CI/CD" in the dictionary matches "ci/cd" in text. */
    public static String normalizePhrase(String phrase, boolean caseSensitive) {
        String joined = String.join(" ", tokenize(phrase));
        return caseSensitive ? joined : joined.toLowerCase(Locale.ROOT);
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        for (String raw : text.split("[^\\p{L}\\p{N}+#.\\-]+")) {
            String token = strip(raw);
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String strip(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && (raw.charAt(start) == '.' || raw.charAt(start) == '-')) {
            start++;
        }
        while (end > start && (raw.charAt(end - 1) == '.' || raw.charAt(end - 1) == '-')) {
            end--;
        }
        return raw.substring(start, end);
    }

    private static Set<String> phrases(List<String> tokens) {
        Set<String> phrases = new HashSet<>(tokens.size() * MAX_NGRAM);
        for (int i = 0; i < tokens.size(); i++) {
            StringBuilder phrase = new StringBuilder();
            for (int n = 0; n < MAX_NGRAM && i + n < tokens.size(); n++) {
                if (n > 0) {
                    phrase.append(' ');
                }
                phrase.append(tokens.get(i + n));
                phrases.add(phrase.toString());
            }
        }
        return phrases;
    }
}
