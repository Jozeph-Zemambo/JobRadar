package io.github.jozephzemambo.jobradar.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class UrlCanonicalizerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            https://jobs.lever.co/spotify/2193db3f-77c5-43b8-b030-8f92c9882bf1                  | https://jobs.lever.co/spotify/2193db3f-77c5-43b8-b030-8f92c9882bf1
            HTTPS://Jobs.Lever.co/spotify/2193db3f/                                             | https://jobs.lever.co/spotify/2193db3f
            http://www.stripe.com/jobs/search?gh_jid=8172510                                    | https://stripe.com/jobs/search?gh_jid=8172510
            https://stripe.com/jobs/search?utm_source=linkedin&gh_jid=8172510&utm_medium=social | https://stripe.com/jobs/search?gh_jid=8172510
            https://jobs.lever.co/spotify/abc?lever-source=LinkedIn                             | https://jobs.lever.co/spotify/abc
            https://jobs.ashbyhq.com/ramp/34413f8d#apply                                        | https://jobs.ashbyhq.com/ramp/34413f8d
            https://boards.greenhouse.io/discord/jobs/123?gh_src=abc&b=2&a=1                    | https://boards.greenhouse.io/discord/jobs/123?a=1&b=2
            https://example.com:8443/jobs/1                                                     | https://example.com:8443/jobs/1
            https://example.com:443/                                                            | https://example.com
            """)
    void canonicalizes(String input, String expected) {
        assertThat(UrlCanonicalizer.canonicalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsBlank(String input) {
        assertThatThrownBy(() -> UrlCanonicalizer.canonicalize(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"not a url with spaces", "relative/path"})
    void fallsBackForUnparseableOrHostlessUrls(String input) {
        assertThat(UrlCanonicalizer.canonicalize(input)).isEqualTo(input.toLowerCase());
    }
}
