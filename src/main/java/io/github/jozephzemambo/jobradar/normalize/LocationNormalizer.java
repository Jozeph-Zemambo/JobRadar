package io.github.jozephzemambo.jobradar.normalize;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns free-form location strings into comparable keys.
 *
 * <p>The ATSes write the same place many ways: "New York, NY (HQ)", "New York City", "NYC", "US-Remote",
 * "Remote (US)", and Workday's reversed "US, Oregon, Hillsboro". Segments are split into regions (countries,
 * US states, Canadian provinces, two-letter codes) and specific places. Then:
 * <ul>
 *   <li>a string that <em>ends</em> in a region is "City, State, Country": only its first specific segment is a
 *   key, so "Petaling Jaya, Selangor, Malaysia" gives "petaling jaya" and an unlisted state like Selangor can't
 *   make two different cities overlap;</li>
 *   <li>otherwise the string is a list of cities (Greenhouse writes "Seattle, San Francisco, New York City") and
 *   every specific segment is a key.</li>
 * </ul>
 * Region-level segments become keys only when a location names no specific place, and then only its most
 * specific region (state over country over "remote"/"EMEA"), so "US-Remote" still matches "Remote (US)" but
 * "Florida, USA, Remote" doesn't match "Texas, USA, Remote". Workplace words ("Hybrid", "On-site") are dropped
 * entirely because they aren't places.
 *
 * <p>Both rules came from a labeled evaluation: "Mountain View, California" and "San Francisco, California" used
 * to overlap on "california", and two postings located only at "Hybrid" used to match.
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

    /** Not places at all. */
    private static final Set<String> WORKPLACE_WORDS = Set.of(
            "hybrid", "onsite", "inoffice", "office", "hq", "headquarters", "flexible", "n a", "na",
            "tbd", "multiple locations", "various");

    /*
     * Places too coarse to say two postings are in the same location, unless nothing finer is given. Three levels,
     * because when a posting names no city its most specific region is what identifies it: "Florida, USA, Remote"
     * and "Texas, USA, Remote" share "us" and "remote" but are different openings.
     */

    /** Cross-country areas and "anywhere" words. */
    private static final Set<String> MACRO_REGIONS = Set.of(
            "remote", "anywhere", "global", "worldwide", "eu", "europe", "emea", "apac", "latam", "americas",
            "north america", "asia");

    private static final Set<String> COUNTRIES = Set.of(
            "us", "uk", "canada", "germany", "france", "india", "ireland", "japan", "australia", "singapore",
            "netherlands", "spain", "italy", "brazil", "mexico", "china", "malaysia", "israel", "poland", "portugal",
            "sweden", "switzerland", "south korea", "korea", "philippines", "costa rica", "argentina", "colombia",
            "united arab emirates", "uae");

    /** US states and Canadian provinces (two-letter codes are handled by length). */
    private static final Set<String> STATES = Set.of(
            "alabama", "alaska", "arizona", "arkansas", "california", "colorado", "connecticut", "delaware",
            "florida", "georgia", "hawaii", "idaho", "illinois", "indiana", "iowa", "kansas", "kentucky",
            "louisiana", "maine", "maryland", "massachusetts", "michigan", "minnesota", "mississippi", "missouri",
            "montana", "nebraska", "nevada", "new hampshire", "new jersey", "new mexico", "north carolina",
            "north dakota", "ohio", "oklahoma", "oregon", "pennsylvania", "rhode island", "south carolina",
            "south dakota", "tennessee", "texas", "utah", "vermont", "virginia", "west virginia", "wisconsin",
            "wyoming", "district of columbia",
            "alberta", "british columbia", "manitoba", "new brunswick", "newfoundland and labrador", "nova scotia",
            "ontario", "prince edward island", "quebec", "saskatchewan");

    private LocationNormalizer() {
    }

    /** Normalized keys for a posting's locations: one specific key per location string where possible. */
    public static Set<String> keys(Collection<String> locations) {
        Set<String> specific = new LinkedHashSet<>();
        Set<String> regional = new LinkedHashSet<>();
        if (locations != null) {
            for (String location : locations) {
                List<String> places = segments(location).stream()
                        .filter(s -> !WORKPLACE_WORDS.contains(s))
                        .toList();
                if (places.isEmpty()) {
                    continue;
                }
                boolean endsInRegion = regionLevel(places.getLast()) > 0;
                boolean sawCity = false;
                for (String segment : places) {
                    if (regionLevel(segment) == 0) {
                        specific.add(segment);
                        sawCity = true;
                        if (endsInRegion) {
                            break; // "City, State, Country": everything after the city is region-level
                        }
                    }
                }
                if (!sawCity) {
                    regional.addAll(regionalKeys(places));
                }
            }
        }
        return specific.isEmpty() ? regional : specific;
    }

    /**
     * Keys for a location string that names no city: only its most specific region level ("Florida, USA, Remote"
     * gives "florida", not "us" or "remote"). A remote posting is keyed apart from an office posting in the same
     * region ("Remote - India" is not "India"), unless "remote" is all it says.
     */
    private static Set<String> regionalKeys(List<String> places) {
        int finest = places.stream().mapToInt(LocationNormalizer::regionLevel).max().orElse(0);
        boolean remote = places.contains("remote");
        Set<String> keys = new LinkedHashSet<>();
        for (String segment : places) {
            if (regionLevel(segment) == finest) {
                keys.add(remote && !segment.equals("remote") ? "remote " + segment : segment);
            }
        }
        return keys;
    }

    /**
     * True when two postings share a location. A posting with no location data overlaps nothing: on the live
     * crawl, undescribed Workday postings ("2 Locations") otherwise all matched each other, and for dedup a false
     * merge (hiding a real opening) is worse than a missed one.
     */
    public static boolean overlaps(Collection<String> a, Collection<String> b) {
        Set<String> intersection = new HashSet<>(keys(a));
        intersection.retainAll(keys(b));
        return !intersection.isEmpty();
    }

    static Set<String> segments(String location) {
        Set<String> out = new LinkedHashSet<>();
        if (location == null) {
            return out;
        }
        // Join hyphenated workplace words before splitting on hyphens, or "On-site" would become a place "site".
        String lower = location.toLowerCase(Locale.ROOT)
                .replaceAll("\\bon[\\s-]+site\\b", "onsite")
                .replaceAll("\\bin[\\s-]+office\\b", "inoffice");
        Arrays.stream(lower.split("[,;|/()\\-–•]+"))
                .map(s -> s.replaceAll("[^\\p{L}\\p{N} ]+", " ").replaceAll("\\s+", " ").strip())
                .filter(s -> !s.isEmpty())
                .map(s -> ALIASES.getOrDefault(s, s))
                .map(s -> s.endsWith(" province") ? s.substring(0, s.length() - " province".length()) : s)
                .forEach(out::add);
        return out;
    }

    /**
     * 0 for a specific place (a city), otherwise how specific the region is: 3 state or province, 2 country,
     * 1 macro region. Two-letter segments are almost always state or country codes ("NY", "CA", "GB"); they
     * count as states, except the aliased "us" and "uk".
     */
    private static int regionLevel(String segment) {
        if (MACRO_REGIONS.contains(segment)) {
            return 1;
        }
        if (COUNTRIES.contains(segment)) {
            return 2;
        }
        if (STATES.contains(segment) || segment.length() <= 2) {
            return 3;
        }
        return 0;
    }
}
