package dev.pluxury.expiry.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ReminderStore implements AutoCloseable {
    private final Connection connection;

    public ReminderStore(Path databasePath) throws SQLException, IOException {
        Path absolutePath = databasePath.toAbsolutePath().normalize();
        Path directory = absolutePath.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }

        Connection database = DriverManager.getConnection("jdbc:sqlite:" + absolutePath);
        try {
            initialize(database);
        } catch (SQLException failure) {
            try {
                database.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        connection = database;
    }

    public synchronized Reminder add(long chatId, long updateId, String title, LocalDate expiresOn)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO reminders (chat_id, update_id, title, expires_epoch_day)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (chat_id, update_id) DO NOTHING
                """)) {
            insert.setLong(1, chatId);
            insert.setLong(2, updateId);
            insert.setString(3, title);
            insert.setLong(4, expiresOn.toEpochDay());
            insert.executeUpdate();
        }

        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id, chat_id, title, expires_epoch_day
                FROM reminders
                WHERE chat_id = ? AND update_id = ?
                """)) {
            select.setLong(1, chatId);
            select.setLong(2, updateId);
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    return readReminder(row);
                }
                throw new SQLException("SQLite did not return the stored reminder");
            }
        }
    }

    public synchronized List<Reminder> list(long chatId, int limit) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id, chat_id, title, expires_epoch_day
                FROM reminders
                WHERE chat_id = ? AND completed = 0
                ORDER BY expires_epoch_day, id
                LIMIT ?
                """)) {
            select.setLong(1, chatId);
            select.setInt(2, limit);
            return readReminders(select);
        }
    }

    public synchronized boolean complete(long chatId, long id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE reminders
                SET completed = 1
                WHERE chat_id = ? AND id = ? AND completed = 0
                """)) {
            update.setLong(1, chatId);
            update.setLong(2, id);
            return update.executeUpdate() == 1;
        }
    }

    public synchronized List<Reminder> due(LocalDate today, int leadDays) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id, chat_id, title, expires_epoch_day
                FROM reminders
                WHERE completed = 0 AND notified = 0 AND expires_epoch_day <= ?
                ORDER BY expires_epoch_day, id
                """)) {
            select.setLong(1, today.plusDays(leadDays).toEpochDay());
            return readReminders(select);
        }
    }

    public synchronized void markNotified(long id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE reminders
                SET notified = 1
                WHERE id = ?
                """)) {
            update.setLong(1, id);
            update.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    private static void initialize(Connection database) throws SQLException {
        try (Statement statement = database.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reminders (
                        id INTEGER PRIMARY KEY,
                        chat_id INTEGER NOT NULL,
                        update_id INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        expires_epoch_day INTEGER NOT NULL,
                        completed INTEGER NOT NULL DEFAULT 0,
                        notified INTEGER NOT NULL DEFAULT 0,
                        UNIQUE (chat_id, update_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS reminders_open_by_chat
                    ON reminders (chat_id, expires_epoch_day, id)
                    WHERE completed = 0
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS reminders_pending_by_date
                    ON reminders (expires_epoch_day, id)
                    WHERE completed = 0 AND notified = 0
                    """);
        }
    }

    private static List<Reminder> readReminders(PreparedStatement select) throws SQLException {
        try (ResultSet rows = select.executeQuery()) {
            List<Reminder> reminders = new ArrayList<>();
            while (rows.next()) {
                reminders.add(readReminder(rows));
            }
            return reminders;
        }
    }

    private static Reminder readReminder(ResultSet row) throws SQLException {
        return new Reminder(
                row.getLong("id"),
                row.getLong("chat_id"),
                row.getString("title"),
                LocalDate.ofEpochDay(row.getLong("expires_epoch_day")));
    }
}
