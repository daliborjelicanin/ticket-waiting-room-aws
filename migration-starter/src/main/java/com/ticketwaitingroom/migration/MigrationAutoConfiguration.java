package com.ticketwaitingroom.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The {@code migrate} profile's run (D9, D10, D13): one ECS task, as the master user, before
 * the service is deployed.
 *
 * <ol>
 *   <li>On the maintenance database: the service's database and login role exist, the role's
 *       password is its secret's, and no other role may connect ({@link DatabaseRoles#prepare}).</li>
 *   <li>Flyway migrates the service's database, as master, so the master owns every table.</li>
 *   <li>The role gets DML on those tables and nothing else ({@link DatabaseRoles#grant}).</li>
 *   <li>The JVM exits once the context is up: 0. Any failure above fails startup instead, and
 *       Boot exits 1, which stops the deploy before a service task sees the new schema.</li>
 * </ol>
 *
 * <p>All of it runs inside Boot's own Flyway initializer, as its {@link FlywayMigrationStrategy},
 * so it happens where Flyway would have, before JPA validates the entities against the result.
 * A migrated schema the image's entities do not match therefore fails here, too.
 */
@AutoConfiguration(before = FlywayAutoConfiguration.class)
@Profile(MigrateProfile.NAME)
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MigrationAutoConfiguration.class);

    @Bean
    FlywayMigrationStrategy databaseRolesAroundFlyway(DataSourceProperties dataSource, MigrationProperties properties) {
        return flyway -> {
            try {
                migrate(flyway, dataSource, properties);
            } catch (SQLException failure) {
                throw new IllegalStateException("Migration failed: " + failure.getMessage(), failure);
            }
        };
    }

    private static void migrate(Flyway flyway, DataSourceProperties dataSource, MigrationProperties properties) throws SQLException {
        DatabaseUrl url = DatabaseUrl.parse(dataSource.determineUrl());
        try (Connection maintenance = DriverManager.getConnection(
                url.withDatabase(properties.maintenanceDatabase()),
                dataSource.determineUsername(), dataSource.determinePassword())) {
            DatabaseRoles.prepare(maintenance, url.database(), properties.appRole(), properties.appPassword());
        }

        MigrateResult result = flyway.migrate();
        // Flyway leaves the target version unset when there was nothing to apply.
        log.info("Flyway applied {} migration(s) to {}; schema version {}", result.migrationsExecuted, url.database(),
                result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion);

        try (Connection database = flyway.getConfiguration().getDataSource().getConnection()) {
            DatabaseRoles.grant(database, properties.appRole(), flyway.getConfiguration().getTable());
        }
    }

    /**
     * A migration task that stayed up would never let {@code aws ecs run-task --wait} return.
     * Off only in tests, which run the profile inside the test JVM.
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "migrate.exit", matchIfMissing = true)
    ApplicationListener<ApplicationReadyEvent> exitAfterMigration() {
        return event -> {
            log.info("Migration complete; exiting");
            System.exit(SpringApplication.exit(event.getApplicationContext()));
        };
    }
}
