package io.github.jozephzemambo.jobradar.normalize;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Normalizes job titles for fuzzy comparison: case, punctuation, whitespace and common abbreviations.
 * "Sr. Software Eng (Backend)" and "senior software engineer, backend" normalize to the same string.
 */
public final class TitleNormalizer {

    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("sr", "senior"),
            Map.entry("snr", "senior"),
            Map.entry("jr", "junior"),
            Map.entry("eng", "engineer"),
            Map.entry("engr", "engineer"),
            Map.entry("swe", "software engineer"),
            Map.entry("sde", "software engineer"),
            Map.entry("mgr", "manager"),
            Map.entry("mgmt", "management"),
            Map.entry("dev", "developer"),
            Map.entry("ops", "operations"),
            Map.entry("ii", "2"),
            Map.entry("iii", "3"),
            Map.entry("iv", "4"));

    /**
     * Words that set a role's level rather than its function. Two titles that differ only in these
     * ("Senior Software Engineer" vs "Software Engineer") are different openings, even though token overlap is high.
     */
    private static final Set<String> LEVEL_WORDS = Set.of(
            "intern", "internship", "junior", "associate", "mid", "senior", "staff", "principal", "lead",
            "distinguished", "head", "director", "vp", "chief", "1", "2", "3", "4", "5");

    /**
     * Requisition ids that some boards put in titles ("(R4571)", "JR0286755"). They identify the req, not the role,
     * so two reqs for the same role would otherwise look different. Years ("2027") have no letters and are kept.
     */
    private static final Pattern REQ_ID = Pattern.compile("[a-z]{1,3}\\d{4,}");

    /**
     * Words that never distinguish one role from another: English and French function words seen in real titles,
     * and "nb" from gender markers like "(F/H/NB)" (the single letters are dropped by length).
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "and", "or", "of", "the", "for", "a", "an", "in", "at", "to", "with", "on",
            "de", "la", "le", "les", "des", "du", "en", "et", "nb");

    private TitleNormalizer() {
    }

    /** Lowercase, punctuation to spaces (keeping {@code + #} for C++ / C#), abbreviations expanded. */
    public static String normalize(String title) {
        if (title == null) {
            return "";
        }
        String cleaned = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}+#]+", " ")
                .strip();
        if (cleaned.isEmpty()) {
            return "";
        }
        return Arrays.stream(cleaned.split(" "))
                .filter(token -> !REQ_ID.matcher(token).matches())
                .map(token -> ABBREVIATIONS.getOrDefault(token, token))
                .collect(Collectors.joining(" "));
    }

    /** Distinct tokens of the normalized title, in first-seen order. */
    public static Set<String> tokens(String title) {
        String normalized = normalize(title);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(normalized.split(" ")));
    }

    /** The level words in a title, e.g. {"senior"} for "Sr. Software Engineer", {"2"} for "Engineer II". */
    public static Set<String> levelTokens(String title) {
        Set<String> levels = new LinkedHashSet<>(tokens(title));
        levels.retainAll(LEVEL_WORDS);
        return levels;
    }

    /**
     * The tokens that identify a role, for deciding whether two titles name the same job: stop words and stray
     * single letters ("F/H/NB") dropped, and a light suffix stem so "Engineering" equals "Engineer" and "Systems"
     * equals "System". Requisition ids are already gone (see {@link #normalize}).
     *
     * <p>Duplicate detection requires these sets to be <em>equal</em>: on 200 labeled pairs, titles differing by
     * even one remaining word ("..., Healthcare", "Creator ...") were almost always different openings.
     */
    public static Set<String> signatureTokens(String title) {
        Set<String> signature = new LinkedHashSet<>();
        for (String token : tokens(title)) {
            if (STOP_WORDS.contains(token) || (token.length() == 1 && Character.isLetter(token.charAt(0)))) {
                continue;
            }
            signature.add(stem(token));
        }
        return signature;
    }

    static String stem(String token) {
        if (token.length() > 5 && token.endsWith("ing")) {
            return token.substring(0, token.length() - 3);
        }
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }
}
