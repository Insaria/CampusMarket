package edu.bistu.campusmarket;

import edu.bistu.campusmarket.config.PasswordConfig;
import edu.bistu.campusmarket.dto.LoginAccountQuery;
import java.io.Console;
import java.nio.CharBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;

final class AdminBootstrap {
    private AdminBootstrap() {
    }

    static int run(Console console, boolean resetPassword) {
        if (console == null) {
            System.err.println("Use an interactive terminal for administrator maintenance.");
            return 1;
        }
        char[] databasePassword = null;
        char[] password = null;
        char[] confirmation = null;
        boolean resetCommitted = false;
        try {
            String url = required(console.readLine("Development JDBC URL [jdbc:mysql://127.0.0.1:3306/campus_market_dev]: "),
                    "jdbc:mysql://127.0.0.1:3306/campus_market_dev");
            if (!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]{1,5})?/campus_market_dev")) {
                throw new IllegalArgumentException("Use the local campus_market_dev JDBC URL without credentials or options.");
            }
            String username = required(console.readLine("MySQL development username: "), null);
            databasePassword = console.readPassword("MySQL password: ");
            if (databasePassword == null || databasePassword.length == 0) {
                throw new IllegalArgumentException("A MySQL password is required.");
            }
            String loginName = resetPassword ? "admin" : normalize(console.readLine("New administrator login name: "));
            password = console.readPassword("New administrator password (12-256 characters): ");
            confirmation = console.readPassword("Confirm administrator password: ");
            validatePassword(password, confirmation);
            String hash = encodePassword(password);
            try (Connection connection = DriverManager.getConnection(url, username, String.valueOf(databasePassword))) {
                if (resetPassword) {
                    if (resetAdminPassword(connection, hash)) {
                        resetCommitted = true;
                        console.printf("Password reset complete. Verified admin: ADMIN / APPROVED / ENABLED.%n");
                        return 0;
                    }
                    System.err.println("The approved and enabled admin account was not found. No account was changed.");
                    return 1;
                }
                if (initialize(connection, loginName, hash)) {
                    console.printf("Administrator created. Start the normal db server or sign in to the running server.%n");
                    return 0;
                }
                console.printf("An administrator already exists. No account was changed.%n");
                return 1;
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            System.err.println(exception.getMessage());
        } catch (SQLException exception) {
            if (resetCommitted) {
                System.err.println("Password reset was committed. Closing the database connection failed.");
                return 0;
            }
            System.err.println(resetPassword ? resetDiagnostic(exception)
                    : "Administrator initialization failed. Check database access, schema, or an existing login name.");
        } finally {
            clear(databasePassword);
            clear(password);
            clear(confirmation);
        }
        return 1;
    }

    static String normalize(String login) {
        String name = new LoginAccountQuery(login).loginName();
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Use 1-64 ASCII letters, digits, periods, underscores or hyphens for the login name.");
        }
        return new LoginAccountQuery(name).canonicalLoginName();
    }

    private static String required(String value, String fallback) {
        if (value == null || value.isBlank()) {
            if (fallback == null) {
                throw new IllegalArgumentException("A MySQL development username is required.");
            }
            return fallback;
        }
        return value;
    }

    private static void validatePassword(char[] password, char[] confirmation) {
        if (password == null || password.length < 12 || password.length > 256 || CharBuffer.wrap(password).chars()
                .allMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Use a nonblank administrator password of 12-256 characters.");
        }
        if (!Arrays.equals(password, confirmation)) {
            throw new IllegalArgumentException("The administrator passwords do not match.");
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    static String encodePassword(char[] password) {
        var encoder = new PasswordConfig().passwordEncoder();
        var input = CharBuffer.wrap(password).asReadOnlyBuffer();
        String hash = encoder.encode(input);
        if (!encoder.matches(input, hash)) {
            throw new IllegalStateException("The generated password hash could not be verified. No change was made.");
        }
        return hash;
    }

    static String resetDiagnostic(SQLException exception) {
        String stage = exception instanceof ResetSqlException failure ? failure.stage : "JDBC connection";
        String state = exception.getSQLState();
        String safeState = state != null && state.matches("[0-9A-Z]{5}") ? state : "unavailable";
        String outcome = "commit".equals(stage)
                ? " Commit was not acknowledged. Try signing in with the new password once before retrying."
                : " The tool did not reach a successful commit.";
        return "Password reset failed at " + stage + " (SQLState " + safeState
                + ", MySQL error " + exception.getErrorCode() + ")." + outcome;
    }

    static boolean resetAdminPassword(Connection connection, String passwordHash) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT DATABASE()")) {
            if (!result.next() || !"campus_market_dev".equals(result.getString(1))) {
                throw new IllegalStateException("Use the local campus_market_dev database for password reset.");
            }
        } catch (SQLException exception) {
            throw new ResetSqlException("schema check", exception);
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException exception) {
            throw new ResetSqlException("transaction start", exception);
        }
        String stage = "admin account lookup";
        try {
            if (!hasEligibleAdmin(connection)) {
                connection.rollback();
                return false;
            }
            stage = "password write";
            try (var statement = connection.prepareStatement("""
                    UPDATE users SET password_hash = ?, updated_at = updated_at
                    WHERE login_name = 'admin' AND role_code = 'ADMIN'
                        AND registration_status = 'APPROVED' AND account_status = 'ENABLED'
                    """)) {
                statement.setString(1, passwordHash);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            stage = "account status verification";
            if (!hasEligibleAdmin(connection)) {
                throw new SQLException("Unexpected admin account state");
            }
            stage = "commit";
            connection.commit();
            return true;
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw new ResetSqlException(stage, exception);
        }
    }

    private static boolean hasEligibleAdmin(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT role_code, registration_status, account_status FROM users WHERE login_name = 'admin'
                """)) {
            return result.next() && "ADMIN".equals(result.getString(1)) && "APPROVED".equals(result.getString(2))
                    && "ENABLED".equals(result.getString(3));
        }
    }

    private static final class ResetSqlException extends SQLException {
        private final String stage;

        private ResetSqlException(String stage, SQLException cause) {
            super("Password reset failed at " + stage, cause.getSQLState(), cause.getErrorCode(), cause);
            this.stage = stage;
        }
    }

    // The connection owns the MySQL named lock until it is closed by the caller.
    static boolean initialize(Connection connection, String login, String passwordHash) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT DATABASE(), GET_LOCK('campus_market_dev.admin_bootstrap', 10)")) {
            if (!result.next() || !"campus_market_dev".equals(result.getString(1)) || result.getInt(2) != 1) {
                throw new IllegalStateException("Could not lock the campus_market_dev administrator initialization.");
            }
        }
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT 1 FROM users WHERE role_code = 'ADMIN' LIMIT 1")) {
                if (result.next()) {
                    connection.rollback();
                    return false;
                }
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO users (login_name, password_hash, role_code, real_name, phone, email,
                                       registration_status, account_status)
                    VALUES (?, ?, 'ADMIN', 'Campus Market Administrator', '+12025550100',
                            'bootstrap-admin@campus-market.invalid', 'APPROVED', 'ENABLED')
                    """)) {
                statement.setString(1, login);
                statement.setString(2, passwordHash);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Expected one administrator insert");
                }
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        }
    }
}
