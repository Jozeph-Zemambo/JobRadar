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
            Staff Engineer, Mechanical (R4571)   | staff engineer mechanical
            Technician JR0286755 2027            | technician 2027
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

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Sr. Software Engineer           | senior
            Software Engineer II            | 2
            Staff Engineer, Tech Lead       | staff,lead
            Software Engineer               | ''
            Mid/Senior SRE                  | mid,senior
            """)
    void levelTokens(String title, String expected) {
        assertThat(String.join(",", TitleNormalizer.levelTokens(title))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" = \"{1}\" -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            Engineer, Software Development Engineering (Embedded) | Software Development Engineering (Embedded) | true
            Project Manager Assistant - Stage (F/H/NB)             | Project Manager Assistant - Stage           | true
            Director of Sales and Marketing                        | Sales & Marketing Director                  | true
            Systems Engineer                                       | System Engineer                             | true
            Creator Project Manager Assistant                      | Project Manager Assistant                   | false
            Enterprise Sales Director - Majors, Healthcare         | Enterprise Sales Director - Majors          | false
            """)
    void signatureTokens(String a, String b, boolean same) {
        assertThat(TitleNormalizer.signatureTokens(a).equals(TitleNormalizer.signatureTokens(b))).isEqualTo(same);
    }

    @Test
    void stemmingIsConservative() {
        assertThat(TitleNormalizer.stem("engineering")).isEqualTo("engineer");
        assertThat(TitleNormalizer.stem("analytics")).isEqualTo("analytic");
        assertThat(TitleNormalizer.stem("business")).isEqualTo("business");
        assertThat(TitleNormalizer.stem("campus")).isEqualTo("campus");
        assertThat(TitleNormalizer.stem("king")).isEqualTo("king");
    }
}
