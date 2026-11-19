/**
 * The {@code migrate} profile (D9, D10, D13). Non-null by default, as Spring Framework 7 and
 * Boot 4 are: the callbacks this package implements ({@code EnvironmentPostProcessor},
 * {@code FlywayMigrationStrategy}) are {@code @NullMarked}.
 */
@NullMarked
package com.ticketwaitingroom.migration;

import org.jspecify.annotations.NullMarked;
