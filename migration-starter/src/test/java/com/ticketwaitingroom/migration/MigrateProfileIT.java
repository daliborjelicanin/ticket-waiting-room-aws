package com.ticketwaitingroom.migration;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.fail;

/**
 * The D10 proof: the {@code migrate} profile, run as the server's superuser exactly as the ECS
 * task runs as the RDS master, leaves a service role that can work its own rows and nothing else.
 *
 * <p>Each test uses its own database and role, so they are independent on one server, which
 * is itself the production shape: checkout and ticketing share one RDS instance (D9).
 */
@Testcontainers
class MigrateProfileIT {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String INVALID_PASSWORD = "28P01";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class MigratingApp {
    }

    @Test
    void secondRunIsANoOp() throws SQLException {
        var service = Service.fresh();
        service.migrate();
        service.migrate();

        try (Connection master = connectAsMaster(service.database)) {
            assertThat(queryLong(master, "SELECT count(*) FROM flyway_schema_history WHERE success")).isEqualTo(1);
        }
        try (Connection app = service.connect()) {
            assertThat(queryLong(app, "SELECT count(*) FROM things")).isZero();
        }
    }

    @Test
    void roleWorksItsOwnRows() throws SQLException {
        var service = Service.fresh();
        service.migrate();

        try (Connection app = service.connect(); Statement statement = app.createStatement()) {
            // The insert draws on the bigserial's sequence: the grant on sequences, not only tables.
            statement.execute("INSERT INTO things (name) VALUES ('seat A1')");
            statement.execute("UPDATE things SET name = 'seat A2'");
            assertThat(queryLong(app, "SELECT count(*) FROM things WHERE name = 'seat A2'")).isEqualTo(1);
            statement.execute("DELETE FROM things");
        }
    }

    @Test
    void roleIsRefusedDdl() throws SQLException {
        var service = Service.fresh();
        service.migrate();

        try (Connection app = service.connect()) {
            assertThat(sqlStateOf(() -> execute(app, "DROP TABLE things"))).isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThat(sqlStateOf(() -> execute(app, "ALTER TABLE things ADD COLUMN price INT"))).isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThat(sqlStateOf(() -> execute(app, "CREATE TABLE extra (id INT)"))).isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThat(sqlStateOf(() -> execute(app, "TRUNCATE things"))).isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
    }

    @Test
    void roleIsRefusedTheOtherServicesDatabase() {
        var checkout = Service.fresh();
        var ticketing = Service.fresh();
        checkout.migrate();
        ticketing.migrate();

        assertThat(sqlStateOf(() -> DriverManager.getConnection(url(ticketing.database), checkout.role, checkout.password).close()))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlStateOf(() -> DriverManager.getConnection(url(checkout.database), ticketing.role, ticketing.password).close()))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void roleCannotReadOrEditTheMigrationHistory() throws SQLException {
        var service = Service.fresh();
        service.migrate();

        try (Connection app = service.connect()) {
            assertThat(sqlStateOf(() -> queryLong(app, "SELECT count(*) FROM flyway_schema_history"))).isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThat(sqlStateOf(() -> execute(app, "DELETE FROM flyway_schema_history"))).isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
    }

    /** D13: bump the secret, migrate, and the role's password is the new one, the old refused. */
    @Test
    void rerunAppliesARotatedPassword() throws SQLException {
        var before = Service.fresh();
        before.migrate();
        var after = before.withPassword(newPassword());
        after.migrate();

        assertThat(sqlStateOf(() -> before.connect().close())).isEqualTo(INVALID_PASSWORD);
        try (Connection app = after.connect()) {
            assertThat(queryLong(app, "SELECT count(*) FROM things")).isZero();
        }
    }

    /** The service task definition turns Flyway off (D10); a migration task copied from it must still migrate. */
    @Test
    void profileMigratesEvenWithFlywayDisabledOnTheCommandLine() throws SQLException {
        var service = Service.fresh();
        service.migrate("--spring.flyway.enabled=false");

        try (Connection app = service.connect()) {
            assertThat(queryLong(app, "SELECT count(*) FROM things")).isZero();
        }
    }

    /** Local bootRun is unchanged: Flyway as before, and no role work without the profile. */
    @Test
    void withoutTheProfileFlywayRunsAsBeforeAndNoRoleIsMade() throws SQLException {
        var service = Service.fresh();
        try (Connection maintenance = connectAsMaster("postgres")) {
            execute(maintenance, "CREATE DATABASE " + service.database);
        }

        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(MigratingApp.class)
                .properties(service.datasource().toArray(String[]::new))
                .run()) {
            assertThat(ignored.isActive()).isTrue();
        }

        try (Connection master = connectAsMaster(service.database)) {
            assertThat(queryLong(master, "SELECT count(*) FROM things")).isZero();
            assertThat(queryLong(master, "SELECT count(*) FROM pg_roles WHERE rolname = '" + service.role + "'")).isZero();
        }
    }

    @Test
    void aShortPasswordFailsTheRunBeforeAnythingIsCreated() throws SQLException {
        var service = Service.fresh().withPassword("checkout");

        assertThatThrownBy(service::migrate).hasStackTraceContaining("migrate.app-password");

        try (Connection maintenance = connectAsMaster("postgres")) {
            assertThat(queryLong(maintenance, "SELECT count(*) FROM pg_database WHERE datname = '" + service.database + "'")).isZero();
        }
    }

    private record Service(String database, String role, String password) {

        static Service fresh() {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            return new Service("db_" + suffix, "app_" + suffix, newPassword());
        }

        Service withPassword(String other) {
            return new Service(database, role, other);
        }

        List<String> datasource() {
            return List.of(
                    "spring.datasource.url=" + url(database),
                    "spring.datasource.username=" + POSTGRES.getUsername(),
                    "spring.datasource.password=" + POSTGRES.getPassword());
        }

        void migrate(String... arguments) {
            List<String> properties = new ArrayList<>(datasource());
            properties.add("migrate.app-role=" + role);
            properties.add("migrate.app-password=" + password);
            properties.add("migrate.exit=false");
            // Startup is the whole run: the context is up once the migration is done.
            new SpringApplicationBuilder(MigratingApp.class)
                    .profiles(MigrateProfile.NAME)
                    .properties(properties.toArray(String[]::new))
                    .run(arguments)
                    .close();
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(url(database), role, password);
        }
    }

    private static String newPassword() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String url(String database) {
        return DatabaseUrl.parse(POSTGRES.getJdbcUrl()).withDatabase(database);
    }

    private static Connection connectAsMaster(String database) throws SQLException {
        return DriverManager.getConnection(url(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String sqlStateOf(ThrowingCallable call) {
        SQLException failure = catchThrowableOfType(SQLException.class, call);
        if (failure == null) {
            // Not assertThat(failure): SQLException is also an Iterable<Throwable>, which makes it ambiguous.
            return fail("expected the statement to be refused, and it succeeded");
        }
        return failure.getSQLState();
    }
}
