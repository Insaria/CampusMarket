package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseStartupCheckTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DatabaseStartupCheck startupCheck = new DatabaseStartupCheck(jdbcTemplate, "campus_market_dev");

    @Test
    void checksSchemaBeforeReadingBusinessTables() {
        when(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).thenReturn("campus_market_dev");

        startupCheck.run(new DefaultApplicationArguments());

        InOrder calls = inOrder(jdbcTemplate);
        calls.verify(jdbcTemplate).queryForObject("SELECT DATABASE()", String.class);
        calls.verify(jdbcTemplate).queryForList("SELECT id FROM users LIMIT 1", Long.class);
        calls.verify(jdbcTemplate).queryForList("SELECT id FROM products LIMIT 1", Long.class);
    }

    @Test
    void rejectsUnexpectedSchemaBeforeReadingBusinessTables() {
        when(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).thenReturn("other_schema");

        assertThrows(IllegalStateException.class, () -> startupCheck.run(new DefaultApplicationArguments()));
        verify(jdbcTemplate).queryForObject("SELECT DATABASE()", String.class);
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void acceptsTheConfiguredSchema() {
        when(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).thenReturn("configured_schema");
        new DatabaseStartupCheck(jdbcTemplate, "configured_schema").run(new DefaultApplicationArguments());
        verify(jdbcTemplate).queryForList("SELECT id FROM users LIMIT 1", Long.class);
    }
}
