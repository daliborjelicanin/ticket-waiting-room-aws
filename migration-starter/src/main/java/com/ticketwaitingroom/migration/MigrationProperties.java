package com.ticketwaitingroom.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * The service's own login role, which the migration creates and keeps in step with its
 * secret (D9, D13). The datasource itself is the master user's, because only the migration
 * runs as master.
 *
 * <p>In ECS both values come from the service's Secrets Manager secret through
 * {@code valueFrom}: {@code MIGRATE_APP_ROLE} from its {@code username} key and
 * {@code MIGRATE_APP_PASSWORD} from its {@code password} key.
 *
 * @param appRole             the role the service logs in as, e.g. {@code checkout_app}
 * @param appPassword         its password, set on every run so a rotated secret takes effect
 * @param maintenanceDatabase where the role and database are created from, before the
 *                            service's database exists
 */
@ConfigurationProperties("migrate")
public record MigrationProperties(
        String appRole,
        String appPassword,
        @DefaultValue("postgres") String maintenanceDatabase
) {

    /** Terraform mints 32 characters (modules/rds); anything short is a local default leaking in. */
    static final int MIN_PASSWORD_LENGTH = 16;

    // The binder passes null for an unset property, whatever the package's @NullMarked says;
    // StringUtils takes a nullable argument, so the checks below catch that too.
    public MigrationProperties {
        if (!StringUtils.hasText(appRole)) {
            throw new IllegalArgumentException("migrate.app-role must name the service's database role");
        }
        if (!StringUtils.hasLength(appPassword) || appPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "migrate.app-password must be set from the service's secret (at least "
                            + MIN_PASSWORD_LENGTH + " characters)");
        }
    }
}
