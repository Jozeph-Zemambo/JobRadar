package io.github.jozephzemambo.jobradar.scoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Known skills and the ways postings spell them ("k8s" is Kubernetes, "Postgres" is PostgreSQL).
 * Built from {@code skills.yml}; immutable after construction, so it is safe to share across threads.
 *
 * <p>Aliases are indexed by their first token (lowercase). Matching walks the posting's tokens once and, at each
 * token, only checks the few aliases that start with it.
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

    /** One spelling of one skill, as tokens. */
    private record Alias(int skill, String[] tokens, boolean caseSensitive) {
    }

    private final List<Skill> skills;
    private final Map<String, Skill> byName;
    private final Map<String, List<Alias>> byFirstToken;

    public SkillDictionary(List<Skill> skills) {
        this.skills = List.copyOf(skills);
        Map<String, Skill> names = new LinkedHashMap<>();
        Map<String, List<Alias>> index = new HashMap<>();
        for (int s = 0; s < this.skills.size(); s++) {
            Skill skill = this.skills.get(s);
            if (names.putIfAbsent(skill.name().toLowerCase(Locale.ROOT), skill) != null) {
                throw new IllegalArgumentException("Duplicate skill: " + skill.name());
            }
            Set<String> insensitive = new LinkedHashSet<>();
            if (skill.caseSensitiveAliases().isEmpty()) {
                insensitive.add(skill.name());
            }
            insensitive.addAll(skill.aliases());
            for (String alias : insensitive) {
                add(index, new Alias(s, TextTokens.phraseTokens(alias, false), false));
            }
            for (String alias : skill.caseSensitiveAliases()) {
                add(index, new Alias(s, TextTokens.phraseTokens(alias, true), true));
            }
        }
        this.byName = Collections.unmodifiableMap(names);
        this.byFirstToken = Collections.unmodifiableMap(index);
    }

    private static void add(Map<String, List<Alias>> index, Alias alias) {
        if (alias.tokens().length == 0) {
            return;
        }
        index.computeIfAbsent(alias.tokens()[0].toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(alias);
    }

    /** Canonical names of every skill mentioned in {@code text}, in dictionary order. */
    public List<String> skillsIn(TextTokens text) {
        boolean[] found = new boolean[skills.size()];
        for (int i = 0; i < text.size(); i++) {
            List<Alias> candidates = byFirstToken.get(text.lower(i));
            if (candidates == null) {
                continue;
            }
            for (Alias alias : candidates) {
                if (!found[alias.skill()] && text.matchesAt(i, alias.tokens(), alias.caseSensitive())) {
                    found[alias.skill()] = true;
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (int s = 0; s < found.length; s++) {
            if (found[s]) {
                names.add(skills.get(s).name());
            }
        }
        return names;
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
