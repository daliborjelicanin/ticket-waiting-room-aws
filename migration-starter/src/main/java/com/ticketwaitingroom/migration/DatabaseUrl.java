package com.ticketwaitingroom.migration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Postgres JDBC URL split at its database name, so the migration can connect to the
 * maintenance database on the same server, with the same parameters ({@code sslmode} and the
 * rest), before the service's own database exists.
 *
 * @param server   everything up to and including the slash before the database name
 * @param database the database name
 * @param query    the parameters, with their leading {@code ?}, or empty
 */
record DatabaseUrl(
        String server,
        String database,
        String query
) {

    private static final Pattern POSTGRES = Pattern.compile("^(jdbc:postgresql://[^/?]+/)([^/?]+)(\\?.*)?$");

    static DatabaseUrl parse(String url) {
        Matcher matcher = POSTGRES.matcher(url);
        if (!matcher.matches()) {
            // Not echoed: a JDBC URL may carry a password parameter.
            throw new IllegalArgumentException(
                    "spring.datasource.url must be jdbc:postgresql://<host>[:<port>]/<database>[?<parameters>]");
        }
        String query = matcher.group(3);
        return new DatabaseUrl(matcher.group(1), matcher.group(2), query == null ? "" : query);
    }

    String withDatabase(String other) {
        return server + other + query;
    }
}
