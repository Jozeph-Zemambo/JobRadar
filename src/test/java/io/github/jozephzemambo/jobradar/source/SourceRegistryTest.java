package io.github.jozephzemambo.jobradar.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jozephzemambo.jobradar.domain.Ats;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceRegistryTest {

    @Test
    void looksUpByAts() {
        JobSource lever = stub(Ats.LEVER);
        SourceRegistry registry = new SourceRegistry(List.of(lever));

        assertThat(registry.forAts(Ats.LEVER)).isSameAs(lever);
        assertThat(registry.supports(Ats.ASHBY)).isFalse();
        assertThatThrownBy(() -> registry.forAts(Ats.ASHBY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTwoSourcesForOneAts() {
        assertThatThrownBy(() -> new SourceRegistry(List.of(stub(Ats.LEVER), stub(Ats.LEVER))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEVER");
    }

    private static JobSource stub(Ats ats) {
        JobSource source = mock(JobSource.class);
        when(source.ats()).thenReturn(ats);
        return source;
    }
}
