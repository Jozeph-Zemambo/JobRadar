package io.github.jozephzemambo.jobradar.scoring;

import java.util.List;

/**
 * A score plus the reasons for it, so a ranking can always be explained.
 *
 * @param score          final score in [0, 1]
 * @param skillCoverage  matched / (skills the posting mentions + 1)
 * @param titleMatch     the title contains one of the profile's title keywords
 * @param titleExcluded  the title contains one of the profile's exclusions (score forced to 0)
 * @param postingSkills  every dictionary skill the posting mentions
 * @param matchedSkills  posting skills the profile has
 * @param missingSkills  posting skills the profile lacks
 */
public record ScoreBreakdown(
        double score,
        double skillCoverage,
        boolean titleMatch,
        boolean titleExcluded,
        List<String> postingSkills,
        List<String> matchedSkills,
        List<String> missingSkills) {

    public ScoreBreakdown {
        postingSkills = List.copyOf(postingSkills);
        matchedSkills = List.copyOf(matchedSkills);
        missingSkills = List.copyOf(missingSkills);
    }
}
