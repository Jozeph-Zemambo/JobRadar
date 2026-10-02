package io.github.jozephzemambo.jobradar.scoring;

import java.util.List;

/**
 * What a ranking is relative to: a skill set and the kinds of titles that are in scope.
 *
 * @param name            label shown in API responses
 * @param skills          skills the profile has (canonical names from the dictionary)
 * @param titleKeywords   phrases that make a title relevant, e.g. "software engineer", "backend"
 * @param titleExclusions phrases that make a title irrelevant regardless of skills, e.g. "director"
 */
public record Profile(String name, List<String> skills, List<String> titleKeywords, List<String> titleExclusions) {

    public Profile {
        name = name == null || name.isBlank() ? "default" : name;
        skills = skills == null ? List.of() : List.copyOf(skills);
        titleKeywords = titleKeywords == null ? List.of() : List.copyOf(titleKeywords);
        titleExclusions = titleExclusions == null ? List.of() : List.copyOf(titleExclusions);
    }
}
