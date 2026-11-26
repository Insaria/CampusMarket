package edu.bistu.campusmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.config.PasswordConfig;
import java.nio.CharBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminBootstrapTest {
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet schema = mock(ResultSet.class);
    private final ResultSet admins = mock(ResultSet.class);
    private final PreparedStatement insert = mock(PreparedStatement.class);

    @BeforeEach
    void arrange() throws Exception {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE(), GET_LOCK('campus_market_dev.admin_bootstrap', 10)")).thenReturn(schema);
        when(schema.next()).thenReturn(true);
        when(schema.getString(1)).thenReturn("campus_market_dev");
        when(schema.getInt(2)).thenReturn(1);
        when(statement.executeQuery("SELECT 1 FROM users WHERE role_code = 'ADMIN' LIMIT 1")).thenReturn(admins);
        when(connection.prepareStatement(anyString())).thenReturn(insert);
        when(insert.executeUpdate()).thenReturn(1);
    }

    @Test
    void createsFirstAdministratorWithLoginCompatibleHash() throws Exception {
        var encoder = new PasswordConfig().passwordEncoder();
        var secret = CharBuffer.wrap("synthetic-test-passphrase".toCharArray());
        String hash = AdminBootstrap.encodePassword("synthetic-test-passphrase".toCharArray());
        assertThat(hash).startsWith("{pbkdf2-sha256-v1}");
        assertThat(encoder.matches(secret, hash)).isTrue();
        assertThat(AdminBootstrap.initialize(connection, AdminBootstrap.normalize(" Demo.Admin "), hash)).isTrue();
        verify(insert).setString(1, "demo.admin");
        verify(insert).setString(2, hash);
        verify(connection).commit();
    }

    @Test
    void refusesIfAnAdminAlreadyExists() throws Exception {
        when(admins.next()).thenReturn(true);
        assertThat(AdminBootstrap.initialize(connection, "demo.admin", "hash")).isFalse();
        verify(connection, never()).prepareStatement(anyString());
        verify(connection).rollback();
    }

    @Test
    void rejectsWrongSchemaAndRollsBackWriteFailures() throws Exception {
        when(schema.getString(1)).thenReturn("other");
        assertThatThrownBy(() -> AdminBootstrap.initialize(connection, "demo.admin", "hash"))
                .isInstanceOf(IllegalStateException.class);
        when(schema.getString(1)).thenReturn("campus_market_dev");
        when(insert.executeUpdate()).thenThrow(new SQLException());
        assertThatThrownBy(() -> AdminBootstrap.initialize(connection, "demo.admin", "hash")).isInstanceOf(SQLException.class);
        verify(connection, never()).commit();
        verify(connection).rollback();
    }

    @Test
    void resetsOnlyTheApprovedEnabledAdminAndVerifiesWithoutReadingTheHash() throws Exception {
        when(statement.executeQuery("SELECT DATABASE()")).thenReturn(schema);
        var account = mock(ResultSet.class);
        when(statement.executeQuery(org.mockito.ArgumentMatchers.contains("SELECT role_code, registration_status, account_status")))
                .thenReturn(account);
        when(account.next()).thenReturn(true);
        when(account.getString(1)).thenReturn("ADMIN");
        when(account.getString(2)).thenReturn("APPROVED");
        when(account.getString(3)).thenReturn("ENABLED");

        assertThat(AdminBootstrap.resetAdminPassword(connection, "synthetic-hash")).isTrue();
        var statementSql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(statementSql.capture());
        assertThat(statementSql.getValue()).contains("SET password_hash = ?, updated_at = updated_at",
                "login_name = 'admin'", "role_code = 'ADMIN'", "registration_status = 'APPROVED'", "account_status = 'ENABLED'");
        verify(insert).setString(1, "synthetic-hash");
        verify(connection).commit();
    }

    @Test
    void resetDoesNotCommitForAnIneligibleAccountOrFailedStatusVerification() throws Exception {
        when(statement.executeQuery("SELECT DATABASE()")).thenReturn(schema);
        var eligible = eligibleAdmin();
        var missing = mock(ResultSet.class);
        when(statement.executeQuery(org.mockito.ArgumentMatchers.contains("SELECT role_code, registration_status, account_status")))
                .thenReturn(missing, eligible, missing);

        assertThat(AdminBootstrap.resetAdminPassword(connection, "synthetic-hash")).isFalse();
        assertThatThrownBy(() -> AdminBootstrap.resetAdminPassword(connection, "synthetic-hash"))
                .isInstanceOf(SQLException.class);
        verify(connection, never()).commit();
        verify(connection, org.mockito.Mockito.times(2)).rollback();
    }

    @Test
    void distinguishesWriteAndUnacknowledgedCommitWithoutExposingDriverDetails() throws Exception {
        when(statement.executeQuery("SELECT DATABASE()")).thenReturn(schema);
        var eligible = eligibleAdmin();
        when(statement.executeQuery(org.mockito.ArgumentMatchers.contains("SELECT role_code, registration_status, account_status")))
                .thenReturn(eligible);
        when(insert.executeUpdate()).thenThrow(new SQLException("private driver message", "42000", 1142)).thenReturn(1);
        org.mockito.Mockito.doThrow(new SQLException("private driver message", "08S01", 0)).when(connection).commit();

        var write = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> AdminBootstrap.resetAdminPassword(connection, "synthetic-hash"));
        assertThat(AdminBootstrap.resetDiagnostic(write)).contains("password write", "42000", "1142")
                .doesNotContain("private driver message");
        var commit = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> AdminBootstrap.resetAdminPassword(connection, "synthetic-hash"));
        assertThat(AdminBootstrap.resetDiagnostic(commit)).contains("commit", "not acknowledged", "08S01")
                .doesNotContain("private driver message");
    }

    private ResultSet eligibleAdmin() throws SQLException {
        var account = mock(ResultSet.class);
        when(account.next()).thenReturn(true);
        when(account.getString(1)).thenReturn("ADMIN");
        when(account.getString(2)).thenReturn("APPROVED");
        when(account.getString(3)).thenReturn("ENABLED");
        return account;
    }
}
