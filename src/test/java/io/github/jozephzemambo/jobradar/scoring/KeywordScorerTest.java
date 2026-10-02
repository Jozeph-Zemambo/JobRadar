package io.github.jozephzemambo.jobradar.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Posting;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KeywordScorerTest {

    private final SkillDictionary dictionary = new SkillDictionary(List.of(
            new SkillDictionary.Skill("Java", "language", List.of("java"), null),
            new SkillDictionary.Skill("JavaScript", "language", List.of("js"), null),
            new SkillDictionary.Skill("Go", "language", List.of("golang"), List.of("Go")),
            new SkillDictionary.Skill("C++", "language", List.of("cpp"), null),
            new SkillDictionary.Skill("C#", "language", null, null),
            new SkillDictionary.Skill("Node.js", "framework", List.of("nodejs"), null),
            new SkillDictionary.Skill("Spring", "framework", List.of("spring boot"), null),
            new SkillDictionary.Skill("Kubernetes", "infrastructure", List.of("k8s"), null),
            new SkillDictionary.Skill("PostgreSQL", "data", List.of("postgres"), null),
            new SkillDictionary.Skill("CI/CD", "infrastructure", null, null),
            new SkillDictionary.Skill("Machine Learning", "ml", List.of("ml"), null)));

    private final KeywordScorer scorer = new KeywordScorer(dictionary);
    private final Profile profile = new Profile("test", List.of("java", "Spring", "PostgreSQL"),
            List.of("software engineer", "backend"), List.of("director", "intern"));

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            We use JavaScript and TypeScript                     | JavaScript
            Experience with Java.                                | Java
            Java/Kotlin services                                 | Java
            Modern C++ (C++17) and some C#                       | C++,C#
            Go, Rust or Python                                   | Go
            You will go to customer sites                        | ''
            Own our go-to-market motion                          | ''
            Golang microservices                                 | Go
            k8s and Postgres                                     | Kubernetes,PostgreSQL
            Node.js backend; NodeJS experience                   | Node.js
            Strong CI/CD culture                                 | CI/CD
            Spring Boot 3 and machine learning                   | Spring,Machine Learning
            HTML email templates                                 | ''
            """)
    void detectsSkillsOnWordBoundaries(String text, String expected) {
        List<String> found = dictionary.skillsIn(TextTokens.of(text));
        List<String> expectedList = expected.isEmpty() ? List.of() : List.of(expected.split(","));
        assertThat(found).containsExactlyInAnyOrderElementsOf(expectedList);
    }

    @Test
    void scoresCoverageAndTitle() {
        Posting posting = posting("Senior Software Engineer, Backend",
                "Java, Spring Boot and PostgreSQL on Kubernetes.");

        ScoreBreakdown breakdown = scorer.score(posting, profile);

        assertThat(breakdown.postingSkills()).containsExactly("Java", "Spring", "Kubernetes", "PostgreSQL");
        assertThat(breakdown.matchedSkills()).containsExactly("Java", "Spring", "PostgreSQL");
        assertThat(breakdown.missingSkills()).containsExactly("Kubernetes");
        assertThat(breakdown.skillCoverage()).isEqualTo(0.6); // 3 / (4 + 1)
        assertThat(breakdown.titleMatch()).isTrue();
        assertThat(breakdown.score()).isEqualTo(0.72); // 0.7 * 0.6 + 0.3
    }

    @Test
    void oneMatchingSkillIsNotAPerfectScore() {
        ScoreBreakdown breakdown = scorer.score(posting("Data Analyst", "Some Java."), profile);

        assertThat(breakdown.skillCoverage()).isEqualTo(0.5);
        assertThat(breakdown.titleMatch()).isFalse();
        assertThat(breakdown.score()).isEqualTo(0.35);
    }

    @Test
    void excludedTitleScoresZeroButKeepsExplanation() {
        ScoreBreakdown breakdown = scorer.score(posting("Director of Engineering", "Java and Spring"), profile);

        assertThat(breakdown.titleExcluded()).isTrue();
        assertThat(breakdown.score()).isZero();
        assertThat(breakdown.matchedSkills()).containsExactly("Java", "Spring");
    }

    @Test
    void titleKeywordsMatchWholeWordsOnly() {
        Profile leads = new Profile("p", List.of(), List.of("lead"), List.of("intern"));

        assertThat(scorer.score(posting("Team Lead", ""), leads).titleMatch()).isTrue();
        assertThat(scorer.score(posting("Thought Leadership Manager", ""), leads).titleMatch()).isFalse();
        assertThat(scorer.score(posting("International Sales", ""), leads).titleExcluded()).isFalse();
    }

    @Test
    void postingWithNoSkillsScoresOnTitleOnly() {
        ScoreBreakdown breakdown = scorer.score(posting("Backend Software Engineer", "Join us."), profile);

        assertThat(breakdown.postingSkills()).isEmpty();
        assertThat(breakdown.score()).isEqualTo(0.3);
    }

    @Test
    void dictionaryRejectsDuplicatesAndResolvesNames() {
        assertThat(dictionary.canonicalName("POSTGRESQL")).isEqualTo("PostgreSQL");
        assertThat(dictionary.canonicalName("cobol")).isNull();
        assertThat(dictionary.categoryOf("Go")).isEqualTo("language");
        assertThat(dictionary.size()).isEqualTo(11);
        assertThatThrownBy(() -> new SkillDictionary(List.of(
                new SkillDictionary.Skill("Java", "a", null, null),
                new SkillDictionary.Skill("java", "b", null, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textTokensPhraseLookup() {
        TextTokens text = TextTokens.of("We run CI/CD on k8s. Go-to-market? No: Go services.");
        assertThat(text.contains("ci/cd", false)).isTrue();
        assertThat(text.contains("Go", true)).isTrue();
        assertThat(text.contains("go-to-market", false)).isTrue();
        assertThat(text.contains("kubernetes", false)).isFalse();
        assertThat(text.contains("--", false)).as("empty phrase").isFalse();
        assertThat(text.matchesAt(text.size() - 1, new String[] {"services", "extra"}, false)).isFalse();
        assertThat(TextTokens.of(null).size()).isZero();
    }

    @Test
    void profileDefaults() {
        Profile empty = new Profile(" ", null, null, null);
        assertThat(empty.name()).isEqualTo("default");
        assertThat(empty.skills()).isEmpty();
    }

    private static Posting posting(String title, String description) {
        return new Posting(Ats.GREENHOUSE, "1", "Co", title, List.of(), null, null, "https://x.io/1", null,
                description, null, null);
    }
}
