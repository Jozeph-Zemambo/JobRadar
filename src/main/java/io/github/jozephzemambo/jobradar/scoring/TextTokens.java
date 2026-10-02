package io.github.jozephzemambo.jobradar.scoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A posting's text as a token sequence, in original case and lowercase, for skill matching.
 *
 * <p>Tokens keep {@code + # . -} inside them, so "C++", "C#", "Node.js" and "go-to-market" survive as single
 * tokens. That last one matters: it stops "Go-to-market" (common in sales postings) from counting as the Go
 * language. Leading and trailing dots and dashes are stripped ("Java." becomes "Java").
 *
 * <p>{@link SkillDictionary} indexes aliases by their first token and checks the following tokens in place, so
 * matching is one pass over the text with no per-phrase allocation. An earlier version materialized every 1-3 word
 * phrase into hash sets, which was 35% of ingest CPU on a real crawl.
 */
public final class TextTokens {

    private final String[] exact;
    private final String[] lower;

    private TextTokens(String[] exact) {
        this.exact = exact;
        this.lower = new String[exact.length];
        for (int i = 0; i < exact.length; i++) {
            lower[i] = exact[i].toLowerCase(Locale.ROOT);
        }
    }

    public static TextTokens of(String text) {
        return new TextTokens(tokenize(text).toArray(String[]::new));
    }

    public int size() {
        return exact.length;
    }

    /** Lowercased token at {@code i}. */
    public String lower(int i) {
        return lower[i];
    }

    /** Whether {@code phrase} (tokens from {@link #phraseTokens}) occurs starting at token {@code i}. */
    public boolean matchesAt(int i, String[] phrase, boolean caseSensitive) {
        if (i + phrase.length > exact.length) {
            return false;
        }
        String[] source = caseSensitive ? exact : lower;
        for (int k = 0; k < phrase.length; k++) {
            if (!source[i + k].equals(phrase[k])) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code phrase} occurs anywhere. Linear scan; for one-off checks, not hot loops. */
    public boolean contains(String phrase, boolean caseSensitive) {
        String[] tokens = phraseTokens(phrase, caseSensitive);
        if (tokens.length == 0) {
            return false;
        }
        for (int i = 0; i < exact.length; i++) {
            if (matchesAt(i, tokens, caseSensitive)) {
                return true;
            }
        }
        return false;
    }

    /** Tokenizes a dictionary alias the same way as text, so "CI/CD" in the dictionary matches "ci/cd" in a posting. */
    public static String[] phraseTokens(String phrase, boolean caseSensitive) {
        return tokenize(phrase).stream()
                .map(t -> caseSensitive ? t : t.toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean tokenChar = i < text.length() && isTokenChar(text.charAt(i));
            if (tokenChar && start < 0) {
                start = i;
            } else if (!tokenChar && start >= 0) {
                String token = strip(text, start, i);
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
                start = -1;
            }
        }
        return tokens;
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '+' || c == '#' || c == '.' || c == '-';
    }

    private static String strip(String text, int start, int end) {
        while (start < end && (text.charAt(start) == '.' || text.charAt(start) == '-')) {
            start++;
        }
        while (end > start && (text.charAt(end - 1) == '.' || text.charAt(end - 1) == '-')) {
            end--;
        }
        return text.substring(start, end);
    }
}
