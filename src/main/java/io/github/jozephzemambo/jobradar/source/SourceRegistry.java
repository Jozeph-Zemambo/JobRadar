package io.github.jozephzemambo.jobradar.source;

import io.github.jozephzemambo.jobradar.domain.Ats;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Looks up the {@link JobSource} for an ATS. Spring injects every {@code JobSource} bean as a list, so registering a
 * new provider needs no change here.
 */
@Component
public class SourceRegistry {

    private final Map<Ats, JobSource> byAts;

    public SourceRegistry(List<JobSource> sources) {
        Map<Ats, JobSource> map = new EnumMap<>(Ats.class);
        for (JobSource source : sources) {
            JobSource previous = map.putIfAbsent(source.ats(), source);
            if (previous != null) {
                throw new IllegalStateException("Two JobSources registered for " + source.ats() + ": "
                        + previous.getClass().getSimpleName() + " and " + source.getClass().getSimpleName());
            }
        }
        this.byAts = Collections.unmodifiableMap(map);
    }

    public JobSource forAts(Ats ats) {
        JobSource source = byAts.get(ats);
        if (source == null) {
            throw new IllegalArgumentException("No JobSource registered for " + ats);
        }
        return source;
    }

    public boolean supports(Ats ats) {
        return byAts.containsKey(ats);
    }
}
