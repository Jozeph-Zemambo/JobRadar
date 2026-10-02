package io.github.jozephzemambo.jobradar.scoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Known skills and the ways postings spell them ("k8s" is Kubernetes, "Postgres" is PostgreSQL).
 * Built from {@code skills.yml}; immutable after construction, so it is safe to share across threads.
 */
public final class SkillDictionary {

    /**
     * One skill as configured.
     *
     * @param name                 canonical display name, e.g. "Kubernetes"
     * @param category             grouping for stats, e.g. "infrastructure"
     * @param aliases              case-insensitive spellings (the name itself is always included)
     * @param caseSensitiveAliases spellings that only count with exact case, for words that are also English
     *                             ("Go"): "Go" in a stack list counts, "go" in a sentence doesn't
     */
    public record Skill(String name, String category, List<String> aliases, List<String> caseSensitiveAliases) {
        public Skill {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            caseSensitiveAliases = caseSensitiveAliases == null ? List.of() : List.copyOf(caseSensitiveAliases);
        }
    }

    private record Alias(String phrase, boolean caseSensitive) {
    }

    private final Map<String, Skill> byName;
    private final Map<String, List<Alias>> aliasesByName;

    public SkillDictionary(List<Skill> skills) {
        Map<String, Skill> names = new LinkedHashMap<>();
        Map<String, List<Alias>> aliases = new LinkedHashMap<>();
        for (Skill skill : skills) {
            if (names.putIfAbsent(skill.name().toLowerCase(Locale.ROOT), skill) != null) {
                throw new IllegalArgumentException("Duplicate skill: " + skill.name());
            }
            List<Alias> forSkill = new ArrayList<>();
            Set<String> insensitive = new LinkedHashSet<>();
            if (skill.caseSensitiveAliases().isEmpty()) {
                insensitive.add(skill.name());
            }
            insensitive.addAll(skill.aliases());
            for (String alias : insensitive) {
                forSkill.add(new Alias(TextTokens.normalizePhrase(alias, false), false));
            }
            for (String alias : skill.caseSensitiveAliases()) {
                forSkill.add(new Alias(TextTokens.normalizePhrase(alias, true), true));
            }
            aliases.put(skill.name(), List.copyOf(forSkill));
        }
        this.byName = Collections.unmodifiableMap(names);
        this.aliasesByName = Collections.unmodifiableMap(aliases);
    }

    /** Canonical names of every skill mentioned in {@code text}, in dictionary order. */
    public List<String> skillsIn(TextTokens text) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<Alias>> entry : aliasesByName.entrySet()) {
            for (Alias alias : entry.getValue()) {
                if (text.contains(alias.phrase(), alias.caseSensitive())) {
                    found.add(entry.getKey());
                    break;
                }
            }
        }
        return found;
    }

    /** Canonical name for a configured skill name in any case, or null if unknown. */
    public String canonicalName(String name) {
        Skill skill = byName.get(name.toLowerCase(Locale.ROOT));
        return skill == null ? null : skill.name();
    }

    public String categoryOf(String canonicalName) {
        Skill skill = byName.get(canonicalName.toLowerCase(Locale.ROOT));
        return skill == null ? null : skill.category();
    }

    public int size() {
        return byName.size();
    }
}
