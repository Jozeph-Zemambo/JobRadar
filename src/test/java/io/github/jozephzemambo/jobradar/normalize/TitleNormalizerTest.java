package io.github.jozephzemambo.jobradar.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TitleNormalizerTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', textBlock = """
            ' Security Engineer, Cloud'          | security engineer cloud
            Sr. Software Eng (Backend)           | senior software engineer backend
            SWE II - Payments                    | software engineer 2 payments
            C++ / C# Developer                   | c++ c# developer
            Abuse   Investigator                 | abuse investigator
            Ingeniero de Datos Señor             | ingeniero de datos señor
            """)
    void normalizes(String raw, String expected) {
        assertThat(TitleNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    void nullAndPunctuationOnlyBecomeEmpty() {
        assertThat(TitleNormalizer.normalize(null)).isEmpty();
        assertThat(TitleNormalizer.normalize(" -- ")).isEmpty();
        assertThat(TitleNormalizer.tokens(" -- ")).isEmpty();
    }

    @Test
    void tokensAreDistinctAndOrdered() {
        assertThat(TitleNormalizer.tokens("Engineer, Platform Engineer"))
                .containsExactly("engineer", "platform");
    }
}
