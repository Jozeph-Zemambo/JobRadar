package io.github.jozephzemambo.jobradar.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class ExportRunnerTest {

    private final ExportService export = mock(ExportService.class);

    @TempDir
    Path dir;

    @Test
    void writesTheFileAtomicallyAndExitsZero() throws Exception {
        Path file = dir.resolve("exports/postings.json");
        when(export.export(eq(0.5), isNull(), any(), eq(ExportService.Format.JSON_ARRAY))).thenAnswer(inv -> {
            OutputStream out = inv.getArgument(2);
            out.write("[\n{\"a\":1}\n]\n".getBytes(StandardCharsets.UTF_8));
            assertThat(file).as("not visible until complete").doesNotExist();
            return 1L;
        });
        ExportRunner runner = new ExportRunner(export, file.toString(), 0.5, ExportService.Format.JSON_ARRAY);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
        assertThat(Files.readString(file)).isEqualTo("[\n{\"a\":1}\n]\n");
        assertThat(file.resolveSibling("postings.json.partial")).doesNotExist();
    }

    @Test
    void failureExitsOneAndLeavesNoFile() throws Exception {
        Path file = dir.resolve("postings.json");
        when(export.export(any(), any(), any(), any())).thenThrow(new IllegalStateException("db locked"));
        ExportRunner runner = new ExportRunner(export, file.toString(), null, ExportService.Format.NDJSON);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(file).doesNotExist();
    }

    @Test
    void rejectsOutOfRangeScore() {
        assertThatThrownBy(() -> new ExportRunner(export, "x.json", Double.NaN, ExportService.Format.NDJSON))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
