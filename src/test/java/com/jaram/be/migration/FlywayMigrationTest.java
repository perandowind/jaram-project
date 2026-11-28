package com.jaram.be.migration;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.support.PostgresTest;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlywayMigrationTest extends PostgresTest {
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transaction;

    private String schema;
    private String memberId;
    private Flyway flyway;

    @BeforeEach
    void setUp() {
        schema = "flyway_test_" + UUID.randomUUID().toString().replace("-", "");
        flyway = configuration().load();
    }

    @AfterEach
    void tearDown() {
        transaction.executeWithoutResult(status ->
                entityManager.createNativeQuery("drop schema if exists " + schema + " cascade").executeUpdate());
    }

    @Test
    void emptyDatabaseMigratesOnceAndPreservesDataOnRestart() {
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        insertMember();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertMemberPreserved();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void existingDatabaseRequiresExplicitBaseline() {
        createExistingDatabase();
        assertThatThrownBy(() -> flyway.migrate()).isInstanceOf(FlywayException.class);
        assertMemberPreserved();
    }

    @Test
    void explicitBaselineSkipsInitialSchemaAndPreservesExistingMember() {
        createExistingDatabase();
        executeDdl("create table legacy_marker (id integer)");
        flyway.baseline();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        assertMemberPreserved();
        transaction.executeWithoutResult(status -> {
            selectSchema();
            Session session = entityManager.unwrap(Session.class);
            List<Object[]> history = session.createNativeQuery("""
                    select type, version from flyway_schema_history where success order by installed_rank
                    """, Object[].class).getResultList();
            assertThat(history).extracting(row -> tuple(row[0], row[1]))
                    .containsExactly(tuple("BASELINE", "1"), tuple("SQL", "2"), tuple("SQL", "3"), tuple("SQL", "4"));
            assertThat(session.createNativeQuery("select id from legacy_marker", Integer.class).getResultList())
                    .isEmpty();
        });
    }

    private FluentConfiguration configuration() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .baselineVersion("1")
                .baselineOnMigrate(false)
                .cleanDisabled(true);
    }

    private void createExistingDatabase() {
        configuration().target("1").load().migrate();
        executeDdl("drop table flyway_schema_history");
        insertMember();
    }

    private void insertMember() {
        Member member = Member.newPending("Migration fixture", "test-student", "fixture@example.invalid", "fixture-hash");
        member.setApproval(MemberApproval.APPROVED);
        member.setGrade(MemberGrade.REGULAR);
        member.setStatus(MemberStatus.ACTIVE);
        memberId = member.getId();
        transaction.executeWithoutResult(status -> {
            selectSchema();
            entityManager.persist(member);
        });
    }

    private void assertMemberPreserved() {
        transaction.executeWithoutResult(status -> {
            selectSchema();
            Member member = entityManager.find(Member.class, memberId);
            assertThat(member).isNotNull();
            assertThat(member.getName()).isEqualTo("Migration fixture");
            assertThat(member.getEmail()).isEqualTo("fixture@example.invalid");
            assertThat(member.getPasswordHash()).isEqualTo("fixture-hash");
            assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        });
    }

    private void executeDdl(String sql) {
        transaction.executeWithoutResult(status -> {
            selectSchema();
            entityManager.createNativeQuery(sql).executeUpdate();
        });
    }

    private void selectSchema() {
        entityManager.createNativeQuery("set local search_path to " + schema).executeUpdate();
    }
}
