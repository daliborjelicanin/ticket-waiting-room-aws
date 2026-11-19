package com.ticketwaitingroom.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseUrlTest {

    @Test
    void keepsServerAndParametersWhenSwitchingDatabase() {
        var url = DatabaseUrl.parse("jdbc:postgresql://db.internal:5432/checkout?sslmode=require&tcpKeepAlive=true");

        assertThat(url.database()).isEqualTo("checkout");
        assertThat(url.withDatabase("postgres"))
                .isEqualTo("jdbc:postgresql://db.internal:5432/postgres?sslmode=require&tcpKeepAlive=true");
    }

    @Test
    void worksWithoutPortOrParameters() {
        var url = DatabaseUrl.parse("jdbc:postgresql://localhost/ticketing");

        assertThat(url.database()).isEqualTo("ticketing");
        assertThat(url.withDatabase("postgres")).isEqualTo("jdbc:postgresql://localhost/postgres");
    }

    @Test
    void refusesAUrlWithoutADatabaseAndDoesNotEchoIt() {
        assertThatThrownBy(() -> DatabaseUrl.parse("jdbc:postgresql://localhost:5432/?password=hunter2hunter2"))
                .isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("hunter2");
    }
}
