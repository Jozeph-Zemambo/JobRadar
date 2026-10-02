package io.github.jozephzemambo.jobradar.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Data migrations, run step by step against a throwaway database so existing rows can be checked. */
class MigrationTest {

    @Test
    void v5RewritesExistingWorkdayIdsToBoardScopedOnes() throws Exception {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(url, "4");
        try (Connection db = DriverManager.getConnection(url, "sa", ""); Statement st = db.createStatement()) {
            st.executeUpdate(insert(1, "WORKDAY", "/job/Toronto/Engineer_R123", "workday/wd5/Workday"));
            st.executeUpdate(insert(2, "LEVER", "2193db3f", "spotify"));
        }

        migrate(url, "5");

        try (Connection db = DriverManager.getConnection(url, "sa", ""); Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("select id, external_id from posting order by id")) {
            rs.next();
            assertThat(rs.getString("external_id")).isEqualTo("workday/wd5/Workday/job/Toronto/Engineer_R123");
            rs.next();
            assertThat(rs.getString("external_id")).as("other ATSes untouched").isEqualTo("2193db3f");
        }
    }

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration", "classpath:db/vendor/h2")
                .target(target)
                .load()
                .migrate();
    }

    private static String insert(long id, String ats, String externalId, String board) {
        return "insert into posting (id, ats, external_id, board_token, company, title, normalized_title, "
                + "workplace_type, url, canonical_url, first_seen_at, last_seen_at) values (" + id + ", '" + ats
                + "', '" + externalId + "', '" + board + "', 'Co', 'T', 't', 'UNKNOWN', 'https://x.io/" + id
                + "', 'https://x.io/" + id + "', current_timestamp, current_timestamp)";
    }
}
