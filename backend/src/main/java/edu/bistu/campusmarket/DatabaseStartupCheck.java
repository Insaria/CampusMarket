package edu.bistu.campusmarket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("db")
public class DatabaseStartupCheck implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseStartupCheck.class);
    private final JdbcTemplate jdbcTemplate;
    private final String expectedSchema;

    public DatabaseStartupCheck(JdbcTemplate jdbcTemplate, @Value("${app.database.expected-schema:campus_market_dev}") String expectedSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.expectedSchema = expectedSchema;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (expectedSchema.isBlank() || !expectedSchema.equals(schema)) {
            throw new IllegalStateException("The db profile must connect to the schema configured by DB_SCHEMA");
        }

        jdbcTemplate.queryForList("SELECT id FROM users LIMIT 1", Long.class);
        jdbcTemplate.queryForList("SELECT id FROM products LIMIT 1", Long.class);
        LOGGER.info("Database startup check passed: schema={}, readable tables=users, products", schema);
    }
}
