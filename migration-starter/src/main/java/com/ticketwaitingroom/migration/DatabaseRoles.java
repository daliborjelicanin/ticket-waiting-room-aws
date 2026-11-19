package com.ticketwaitingroom.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The statements around Flyway that turn "the master owns everything" into "each service
 * can only touch its own rows" (D9). All of them are idempotent: every migration task runs
 * them again, which is what keeps the role's password equal to its secret after a rotation.
 *
 * <p><b>Quoting is Postgres's job.</b> A role name and a password cannot be bind parameters
 * in DDL, so each statement is built by the server with {@code format()}: {@code %I} quotes
 * an identifier, {@code %L} a literal. Nothing here concatenates a value into SQL, and no
 * statement text is logged, since one of them carries the password.
 */
final class DatabaseRoles {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRoles.class);

    private static final String DUPLICATE_DATABASE = "42P04";
    private static final String DUPLICATE_OBJECT = "42710";

    private DatabaseRoles() {
    }

    /**
     * Before Flyway, connected to the maintenance database as master: the service's database
     * and role exist, the role's password is the secret's, and only the role (and the master,
     * its owner) may connect.
     *
     * <p>{@code CREATE DATABASE} cannot run inside a transaction, so it cannot be a Flyway
     * migration; this is the step D10 adds in front. It is attempted and a duplicate is
     * accepted, rather than checked first, so two migration tasks started together cannot both
     * pass the check and one fail on the create.
     */
    static void prepare(Connection maintenance, String database, String role, String password) throws SQLException {
        if (createUnlessPresent(maintenance, DUPLICATE_DATABASE, "CREATE DATABASE %I", database)) {
            log.info("Created database {}", database);
        } else {
            log.info("Database {} exists", database);
        }

        // Re-asserted on every run, not only at creation: login, and none of the attributes that
        // would let the role make databases or other roles. (SUPERUSER and REPLICATION are not
        // named: only a superuser may name them, and RDS's master is not one. Both default off.)
        if (createUnlessPresent(maintenance, DUPLICATE_OBJECT,
                "CREATE ROLE %I LOGIN NOCREATEDB NOCREATEROLE PASSWORD %L", role, password)) {
            log.info("Created role {}", role);
        } else {
            execute(maintenance, "ALTER ROLE %I LOGIN NOCREATEDB NOCREATEROLE PASSWORD %L", role, password);
            log.info("Role {} exists; password set from its secret", role);
        }

        // Postgres grants CONNECT (and TEMP) on every database to PUBLIC. Without this, the other
        // service's role could open this database, and the separation would exist only on paper.
        execute(maintenance, "REVOKE ALL ON DATABASE %I FROM PUBLIC", database);
        execute(maintenance, "GRANT CONNECT ON DATABASE %I TO %I", database, role);
    }

    /**
     * After Flyway, connected to the service's database as master: DML on every table and the
     * sequences behind them, for this migration's tables and the next one's, and nothing else.
     * The master owns every table, so a {@code DROP} or {@code ALTER} from the role is refused
     * by ownership, and {@code CREATE} by the schema.
     */
    static void grant(Connection database, String role, String historyTable) throws SQLException {
        // Explicit although Postgres 15+ already withholds it: the claim should not rest on a version.
        execute(database, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        execute(database, "GRANT USAGE ON SCHEMA public TO %I", role);
        execute(database, "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I", role);
        execute(database, "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I", role);
        execute(database, "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I", role);
        execute(database, "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO %I", role);
        // The history is the migration's record, not the service's data: ALL TABLES above
        // included it, and a role that could edit it could make Flyway skip a migration.
        execute(database, "REVOKE ALL ON TABLE public.%I FROM %I", historyTable, role);
        log.info("Granted {} DML on the tables and sequences of schema public", role);
    }

    /** Runs the statement; {@code false} if it failed only because the object already exists. */
    private static boolean createUnlessPresent(Connection connection, String duplicateState, String template,
                                               String... arguments) throws SQLException {
        try {
            execute(connection, template, arguments);
            return true;
        } catch (SQLException failure) {
            if (duplicateState.equals(failure.getSQLState())) {
                return false;
            }
            throw failure;
        }
    }

    private static void execute(Connection connection, String template, String... arguments) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(format(connection, template, arguments));
        }
    }

    private static String format(Connection connection, String template, String... arguments) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT format(?, VARIADIC ?::text[])")) {
            query.setString(1, template);
            query.setArray(2, connection.createArrayOf("text", arguments));
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }
}
