package kr.co.pricelog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PostgresConnectionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17.6-alpine").withDatabaseName("pricelog_test");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void writesAndReadsUsingTheTestDatabase() {
        assertThat(jdbc.queryForObject("select current_database()", String.class))
            .isEqualTo(postgres.getDatabaseName());
        jdbc.execute(
            "create temporary table messages (id integer primary key, body text not null)");
        jdbc.update("insert into messages (id, body) values (?, ?)", 1, "프라이스로그");

        assertThat(jdbc.queryForObject("select body from messages where id = ?", String.class, 1))
            .isEqualTo("프라이스로그");
    }
}
