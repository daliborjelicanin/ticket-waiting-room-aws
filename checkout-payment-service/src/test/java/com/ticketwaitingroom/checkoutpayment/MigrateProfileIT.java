package com.ticketwaitingroom.checkoutpayment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The migration task as ECS will run it (D10): this service's whole context in the
 * {@code migrate} profile, as the server's superuser, against an empty server. It must come up
 * without a web server or a Cognito issuer, migrate the real schema, pass JPA validation
 * against it, and leave {@code checkout_app} with DML on every table and no DDL.
 *
 * <p>migration-starter's own test covers the guarantees one by one; this one covers this
 * service's context and migrations meeting them.
 */
@Testcontainers
class MigrateProfileIT {

    private static final String DATABASE = "checkout";
    private static final String ROLE = "checkout_app";
    private static final String PASSWORD = UUID.randomUUID().toString().replace("-", "");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void migrateTwiceThenTheServiceRoleWorksEveryTableAndCannotDropOne() throws SQLException {
        migrate();
        migrate();

        try (Connection app = DriverManager.getConnection(url(), ROLE, PASSWORD);
             Statement statement = app.createStatement()) {
            for (String table : List.of("orders", "order_seats", "payments", "refunds")) {
                statement.execute("SELECT count(*) FROM " + table);
            }
            SQLException refused = catchThrowableOfType(SQLException.class, () -> statement.execute("DROP TABLE refunds"));
            assertThat(refused.getSQLState()).isEqualTo("42501");
        }
    }

    private static void migrate() {
        new SpringApplicationBuilder(CheckoutPaymentServiceApplication.class)
                .profiles("migrate")
                // Command-line arguments, not .properties(): those are Boot's defaults, the lowest
                // precedence, and application.yml's ${DB_URL:localhost...} would win over them.
                .run(
                        "--spring.datasource.url=" + url(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--migrate.app-role=" + ROLE,
                        "--migrate.app-password=" + PASSWORD,
                        "--migrate.exit=false")
                .close();
    }

    private static String url() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);
    }
}
