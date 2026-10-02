package io.github.jozephzemambo.jobradar.normalize;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns free-form location strings into comparable keys.
 *
 * <p>The three ATSes write the same place many ways: "New York, NY (HQ)", "New York City", "NYC", "US-Remote",
 * "Remote (US)". Each string is split into segments, aliases are folded, and segments are split into
 * <em>specific</em> (cities) and <em>generic</em> (countries, state codes, "remote", "hq"). Two postings are
 * considered co-located when their specific keys intersect, or, if neither names a city, when their generic
 * keys intersect.
 */
public final class LocationNormalizer {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("nyc", "new york"),
            Map.entry("new york city", "new york"),
            Map.entry("sf", "san francisco"),
            Map.entry("bay area", "san francisco"),
            Map.entry("san francisco bay area", "san francisco"),
            Map.entry("usa", "us"),
            Map.entry("u s", "us"),
            Map.entry("united states", "us"),
            Map.entry("united states of america", "us"),
            Map.entry("united kingdom", "uk"),
            Map.entry("england", "uk"),
            Map.entry("great britain", "uk"));

    private static final Set<String> GENERIC = Set.of(
            "remote", "hybrid", "onsite", "on site", "hq", "office", "anywhere", "global", "worldwide",
            "us", "uk", "canada", "eu", "europe", "emea", "apac", "latam", "americas", "north america",
            "germany", "france", "india", "ireland", "japan", "australia", "singapore", "netherlands", "spain",
            "n a", "na", "tbd", "multiple locations");

    private LocationNormalizer() {
    }

    /** Normalized keys for a posting's locations, preferring city-level keys when any exist. */
    public static Set<String> keys(Collection<String> locations) {
        Set<String> specific = new LinkedHashSet<>();
        Set<String> generic = new LinkedHashSet<>();
        if (locations != null) {
            for (String location : locations) {
                for (String segment : segments(location)) {
                    if (isGeneric(segment)) {
                        generic.add(segment);
                    } else {
                        specific.add(segment);
                    }
                }
            }
        }
        return specific.isEmpty() ? generic : specific;
    }

    /**
     * True when two postings share a location. A posting with no location data overlaps nothing: on the live
     * crawl, undescribed Workday postings ("2 Locations") otherwise all matched each other, and for dedup a false
     * merge (hiding a real opening) is worse than a missed one.
     */
    public static boolean overlaps(Collection<String> a, Collection<String> b) {
        Set<String> keysA = keys(a);
        Set<String> keysB = keys(b);
        Set<String> intersection = new HashSet<>(keysA);
        intersection.retainAll(keysB);
        return !intersection.isEmpty();
    }

    static Set<String> segments(String location) {
        Set<String> out = new LinkedHashSet<>();
        if (location == null) {
            return out;
        }
        String lower = location.toLowerCase(Locale.ROOT);
        Arrays.stream(lower.split("[,;|/()\\-–•]+"))
                .map(s -> s.replaceAll("[^\\p{L}\\p{N} ]+", " ").replaceAll("\\s+", " ").strip())
                .filter(s -> !s.isEmpty())
                .map(s -> ALIASES.getOrDefault(s, s))
                .forEach(out::add);
        return out;
    }

    private static boolean isGeneric(String segment) {
        // Two-letter segments are almost always state or country codes ("NY", "CA", "GB").
        return segment.length() <= 2 || GENERIC.contains(segment);
    }
}
