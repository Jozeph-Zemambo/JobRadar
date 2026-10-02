package io.github.jozephzemambo.jobradar.normalize;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reduces a posting URL to a stable form so the same posting reached through different links compares equal.
 *
 * <p>Query strings are <em>not</em> dropped wholesale: some boards carry the job identity in the query
 * (Stripe's Greenhouse URLs look like {@code stripe.com/jobs/search?gh_jid=8172510}). Only known tracking
 * parameters are removed; the rest are kept and sorted so parameter order doesn't matter.
 */
public final class UrlCanonicalizer {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "gh_src", "source", "src", "ref", "referrer", "lever-source", "lever-origin", "lever-via",
            "ashby_jid", "fbclid", "gclid", "trk");

    private UrlCanonicalizer() {
    }

    public static String canonicalize(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            // Not worth failing a whole board over one odd URL; fall back to a trimmed lowercase form.
            return url.strip().toLowerCase(Locale.ROOT);
        }
        if (uri.getHost() == null) {
            return url.strip().toLowerCase(Locale.ROOT);
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || port == 80 || port == 443;

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.equals("/")) {
            path = "";
        }

        String query = canonicalQuery(uri.getRawQuery());

        // Scheme is normalized to https: the same board is often linked over both.
        StringBuilder out = new StringBuilder("https://").append(host);
        if (!defaultPort) {
            out.append(':').append(port);
        }
        out.append(path);
        if (!query.isEmpty()) {
            out.append('?').append(query);
        }
        return out.toString();
    }

    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        return Arrays.stream(rawQuery.split("&"))
                .filter(param -> !param.isBlank())
                .filter(param -> !isTracking(paramName(param)))
                .sorted()
                .collect(Collectors.joining("&"));
    }

    private static String paramName(String param) {
        int eq = param.indexOf('=');
        return (eq < 0 ? param : param.substring(0, eq)).toLowerCase(Locale.ROOT);
    }

    private static boolean isTracking(String name) {
        return name.startsWith("utm_") || TRACKING_PARAMS.contains(name);
    }
}
