package dev.pluxury.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pluxury.expiry.storage.Reminder;
import dev.pluxury.expiry.storage.ReminderStore;
import dev.pluxury.expiry.telegram.TelegramClient.Button;
import dev.pluxury.expiry.telegram.TelegramClient.Callback;
import dev.pluxury.expiry.telegram.TelegramClient.Message;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotCommandsTest {
    @TempDir
    Path directory;

    @Test
    void savesListsAndCompletesAnItemInChat() throws Exception {
        Path database = directory.resolve("reminders.db");
        try (ReminderStore store = new ReminderStore(database)) {
            BotCommands commands = new BotCommands(store, new BotSettings(database, ZoneOffset.UTC, 3, Set.of()));

            Message add = new Message(10, "private", "/add 2022-01-05 Return headphones");
            assertThat(commands.message(1, add).orElseThrow().text())
                    .isEqualTo("Saved #1 · Return headphones\nDue 2022-01-05");
            assertThat(commands.message(1, add).orElseThrow().text()).startsWith("Saved #1");

            Message list = new Message(10, "private", "/list");
            BotCommands.Reply reply = commands.message(2, list).orElseThrow();
            assertThat(reply.text()).isEqualTo("Upcoming deadlines\n\n#1 · 2022-01-05 · Return headphones");
            assertThat(reply.buttons()).containsExactly(new Button("Done #1", "done:1"));

            assertThat(commands.callback(new Callback("close", new Message(20, "private", null), "done:1")))
                    .contains("missing");
            assertThat(commands.callback(new Callback("close", list, "done:1"))).isEqualTo("Closed #1");
            assertThat(commands.message(3, list).orElseThrow().text()).startsWith("No open reminders");
        }
    }

    @Test
    void validatesDatesAndRestrictsCommandsToAllowedPrivateChats() throws Exception {
        Path database = directory.resolve("reminders.db");
        try (ReminderStore store = new ReminderStore(database)) {
            BotCommands commands = new BotCommands(store, new BotSettings(database, ZoneOffset.UTC, 3, Set.of(10L)));

            Message invalid = new Message(10, "private", "/add 2022-02-30 Warranty");
            assertThat(commands.message(1, invalid).orElseThrow().text()).startsWith("That date doesn't exist");
            assertThat(commands.message(2, new Message(20, "private", "/list")).orElseThrow().text())
                    .isEqualTo("This bot is private.");
            assertThat(commands.message(3, new Message(20, "private", "/id")).orElseThrow().text())
                    .isEqualTo("Your chat ID is 20");
            assertThat(commands.message(4, new Message(10, "group", "/list"))).isEmpty();
            assertThat(store.list(10, 10)).isEmpty();
        }
    }

    @Test
    void describesTheDeadlineRelativeToTheCurrentDate() {
        LocalDate expiry = LocalDate.of(2022, 1, 5);
        Reminder reminder = new Reminder(1, 10, "Return headphones", expiry);

        assertThat(BotRuntime.notificationText(reminder, expiry.minusDays(3)))
                .isEqualTo("Due in 3 days · Return headphones\nDeadline: 2022-01-05");
        assertThat(BotRuntime.notificationText(reminder, expiry.minusDays(1))).startsWith("Due tomorrow");
        assertThat(BotRuntime.notificationText(reminder, expiry)).startsWith("Due today");
        assertThat(BotRuntime.notificationText(reminder, expiry.plusDays(1))).startsWith("Overdue");
    }
}
