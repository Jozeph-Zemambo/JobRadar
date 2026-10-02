package io.github.jozephzemambo.jobradar.source;

import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import io.github.jozephzemambo.jobradar.http.RateLimiter;
import io.github.jozephzemambo.jobradar.http.RetryPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Shared helpers for contract tests that replay recorded ATS responses through WireMock. */
public final class SourceTestSupport {

    private SourceTestSupport() {
    }

    public static ObjectMapper mapper() {
        return JsonMapper.builder().build();
    }

    public static HttpFetcher plainFetcher() {
        return new HttpFetcher(HttpClient.newHttpClient(), "JobRadar-test", Duration.ofSeconds(5),
                RateLimiter.UNLIMITED, RetryPolicy.noRetry());
    }

    public static String fixture(String name) {
        try (InputStream in = SourceTestSupport.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
