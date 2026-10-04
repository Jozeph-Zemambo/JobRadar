package io.github.jozephzemambo.jobradar.stats;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot export for scripts: with the {@code export} profile the app starts without a web server, writes every
 * open, non-duplicate posting (with its score and matched skills) to a JSON file, and exits.
 *
 * <p>The file is written to a temporary name and moved into place at the end, so a reader never sees a half-written
 * export. Exit code 0 on success, 1 on failure.
 */
@Component
@Profile("export")
public class ExportRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExportRunner.class);

    private final ExportService export;
    private final Path file;
    private final Double minScore;
    private final ExportService.Format format;
    private int exitCode = 1;

    public ExportRunner(ExportService export,
            @Value("${jobradar.export.file:exports/postings.json}") String file,
            @Value("${jobradar.export.min-score:#{null}}") Double minScore,
            @Value("${jobradar.export.format:JSON_ARRAY}") ExportService.Format format) {
        if (minScore != null && (!Double.isFinite(minScore) || minScore < 0 || minScore > 1)) {
            throw new IllegalArgumentException("jobradar.export.min-score must be between 0 and 1");
        }
        this.export = export;
        this.file = Path.of(file).toAbsolutePath();
        this.minScore = minScore;
        this.format = format;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path partial = file.resolveSibling(file.getFileName() + ".partial");
            long written;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial))) {
                written = export.export(minScore, null, out, format);
            }
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Exported {} postings to {}", written, file);
            exitCode = 0;
        } catch (IOException | RuntimeException e) {
            log.error("Export failed", e);
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
