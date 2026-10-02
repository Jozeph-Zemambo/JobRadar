package io.github.jozephzemambo.jobradar.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TitleTokenJaccardTest {

    @ParameterizedTest(name = "\"{0}\" vs \"{1}\" = {2}")
    @CsvSource(delimiter = '|', textBlock = """
            Backend Engineer                   | Engineer, Backend                 | 1.0
            Sr. Data Scientist                 | Senior Data Scientist             | 1.0
            Senior Software Engineer, Backend  | Staff Software Engineer, Backend  | 0.6
            Software Engineer                  | Software Engineer, Payments       | 0.6667
            Recruiter                          | Lawyer                            | 0.0
            '--'                               | '  '                              | 1.0
            """)
    void similarity(String a, String b, double expected) {
        assertThat(TitleTokenJaccard.similarity(a, b)).isCloseTo(expected, within(1e-4));
        assertThat(TitleTokenJaccard.similarity(b, a)).as("symmetric").isCloseTo(expected, within(1e-4));
    }
}
