package io.github.jozephzemambo.jobradar.scoring;

import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.normalize.TitleNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Transparent keyword scoring:
 *
 * <pre>
 *   coverage = matched / (postingSkills + 1)
 *   score    = 0.7 * coverage + 0.3 * (title matches a keyword ? 1 : 0)
 *   score    = 0 if the title matches an exclusion
 * </pre>
 *
 * The +1 keeps a posting that mentions a single skill you have from scoring a perfect coverage of 1.0; postings
 * that ask for more of what you know rank higher. Every weight is visible in the {@link ScoreBreakdown}.
 */
public class KeywordScorer implements Scorer {

    static final double SKILL_WEIGHT = 0.7;
    static final double TITLE_WEIGHT = 0.3;

    private final SkillDictionary dictionary;

    public KeywordScorer(SkillDictionary dictionary) {
        this.dictionary = dictionary;
    }

    @Override
    public ScoreBreakdown score(Posting posting, Profile profile) {
        TextTokens text = TextTokens.of(posting.title() + "\n" + posting.descriptionText());
        List<String> postingSkills = dictionary.skillsIn(text);

        Set<String> profileSkills = new HashSet<>();
        for (String skill : profile.skills()) {
            String canonical = dictionary.canonicalName(skill);
            profileSkills.add(canonical != null ? canonical : skill);
        }
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String skill : postingSkills) {
            (profileSkills.contains(skill) ? matched : missing).add(skill);
        }

        String title = " " + TitleNormalizer.normalize(posting.title()) + " ";
        boolean excluded = containsAny(title, profile.titleExclusions());
        boolean titleMatch = containsAny(title, profile.titleKeywords());

        double coverage = (double) matched.size() / (postingSkills.size() + 1);
        double score = excluded ? 0.0 : SKILL_WEIGHT * coverage + (titleMatch ? TITLE_WEIGHT : 0.0);
        return new ScoreBreakdown(round(score), round(coverage), titleMatch, excluded, postingSkills, matched,
                missing);
    }

    /** Whole-word phrase match on the normalized title (padded with spaces so "lead" doesn't match "leader"). */
    private static boolean containsAny(String paddedTitle, List<String> phrases) {
        for (String phrase : phrases) {
            String normalized = TitleNormalizer.normalize(phrase);
            if (!normalized.isEmpty() && paddedTitle.contains(" " + normalized + " ")) {
                return true;
            }
        }
        return false;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
