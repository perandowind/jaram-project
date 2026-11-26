package com.jaram.be.migration;

import com.jaram.be.support.PostgresTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayMigrationTest extends PostgresTest {
    private String schema;
    private Connection connection;
    private Flyway flyway;

    @BeforeEach
    void setUp() throws SQLException {
        schema = "flyway_test_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.createStatement().execute("CREATE SCHEMA " + schema);
        connection.setSchema(schema);
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .baselineVersion("1")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (var testConnection = connection; var statement = testConnection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void emptyDatabaseMigratesOnceAndPreservesDataOnRestart() throws SQLException {
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        insertMember();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertMemberPreserved();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void existingDatabaseRequiresExplicitBaseline() throws SQLException {
        createExistingDatabase();
        assertThatThrownBy(() -> flyway.migrate()).isInstanceOf(FlywayException.class);
        assertMemberPreserved();
    }

    @Test
    void explicitBaselineSkipsInitialSchemaAndPreservesExistingMember() throws SQLException {
        createExistingDatabase();
        // Legacy objects may remain after Hibernate update. Baseline must retain them too.
        connection.createStatement().execute("CREATE TABLE legacy_marker (id integer)");
        flyway.baseline();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertMemberPreserved();
        try (var result = connection.createStatement().executeQuery(
                "SELECT type, version FROM flyway_schema_history WHERE success")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("type")).isEqualTo("BASELINE");
            assertThat(result.getString("version")).isEqualTo("1");
            assertThat(result.next()).isFalse();
        }
        connection.createStatement().executeQuery("SELECT * FROM legacy_marker").close();
    }

    private void createExistingDatabase() throws SQLException {
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V1__initial_schema.sql"));
        insertMember();
    }

    private void insertMember() throws SQLException {
        connection.createStatement().execute("""
                INSERT INTO member (id, name, email, student_id, password_hash, contributor,
                                    approval, grade, status, version)
                VALUES ('migration-fixture', 'Migration fixture', 'fixture@example.invalid',
                        'test-student', 'fixture-hash', false, 'APPROVED', 'REGULAR', 'ACTIVE', 0)
                """);
    }

    private void assertMemberPreserved() throws SQLException {
        try (var result = connection.createStatement().executeQuery(
                "SELECT name, email, password_hash, status FROM member WHERE id = 'migration-fixture'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("name")).isEqualTo("Migration fixture");
            assertThat(result.getString("email")).isEqualTo("fixture@example.invalid");
            assertThat(result.getString("password_hash")).isEqualTo("fixture-hash");
            assertThat(result.getString("status")).isEqualTo("ACTIVE");
            assertThat(result.next()).isFalse();
        }
    }
}
