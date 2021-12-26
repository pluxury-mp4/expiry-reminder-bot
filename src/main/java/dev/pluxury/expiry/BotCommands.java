package dev.pluxury.expiry;

import dev.pluxury.expiry.storage.Reminder;
import dev.pluxury.expiry.storage.ReminderStore;
import dev.pluxury.expiry.telegram.TelegramClient.Button;
import dev.pluxury.expiry.telegram.TelegramClient.Callback;
import dev.pluxury.expiry.telegram.TelegramClient.Message;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
final class BotCommands {
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final int LIST_LIMIT = 10;
    private static final String ADD_USAGE = "Use /add YYYY-MM-DD what expires";
    private static final String HELP = """
            Expiry Reminder Bot

            /add YYYY-MM-DD what expires · save a deadline
            /list · see the next deadlines
            /done ID · close a reminder
            /id · show your chat ID
            /help · show this list
            """.strip();

    private final ReminderStore store;
    private final BotSettings settings;

    BotCommands(ReminderStore store, BotSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    Optional<Reply> message(long updateId, Message message) throws SQLException {
        if (!isPrivate(message) || message.text() == null || !message.text().startsWith("/")) {
            return Optional.empty();
        }

        String[] parts = message.text().strip().split("\\s+", 2);
        String command = parts[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        long chatId = message.chatId();
        if (command.equals("/id")) {
            return Optional.of(new Reply("Your chat ID is " + chatId));
        }
        if (!settings.allows(chatId)) {
            return Optional.of(new Reply("This bot is private."));
        }

        String arguments = parts.length == 2 ? parts[1].strip() : "";
        return Optional.of(switch (command) {
            case "/start", "/help" -> new Reply(HELP);
            case "/add" -> add(chatId, updateId, arguments);
            case "/list" -> list(chatId);
            case "/done" -> new Reply(complete(chatId, arguments));
            default -> new Reply("Unknown command. Send /help for the command list.");
        });
    }

    String callback(Callback callback) throws SQLException {
        Message message = callback.message();
        if (!isPrivate(message) || !settings.allows(message.chatId())) {
            return "This action isn't available in this chat.";
        }
        if (callback.data() == null || !callback.data().startsWith("done:")) {
            return "This action isn't available.";
        }
        return complete(message.chatId(), callback.data().substring("done:".length()));
    }

    private Reply add(long chatId, long updateId, String arguments) throws SQLException {
        String[] parts = arguments.split("\\s+", 2);
        if (parts.length != 2 || !DATE.matcher(parts[0]).matches()) {
            return new Reply(ADD_USAGE);
        }

        LocalDate expiry;
        try {
            expiry = LocalDate.parse(parts[0]);
        } catch (DateTimeParseException invalidDate) {
            return new Reply("That date doesn't exist. " + ADD_USAGE);
        }

        String title = parts[1].strip().replaceAll("\\s+", " ");
        if (title.isEmpty() || title.codePointCount(0, title.length()) > 120) {
            return new Reply("Use a title between 1 and 120 characters.");
        }

        Reminder reminder = store.add(chatId, updateId, title, expiry);
        return new Reply("Saved #" + reminder.id() + " · " + reminder.title() + "\nDue " + reminder.expiresOn());
    }

    private Reply list(long chatId) throws SQLException {
        List<Reminder> reminders = store.list(chatId, LIST_LIMIT);
        if (reminders.isEmpty()) {
            return new Reply("No open reminders. " + ADD_USAGE);
        }

        String lines = reminders.stream()
                .map(reminder -> "#" + reminder.id() + " · " + reminder.expiresOn() + " · " + reminder.title())
                .collect(Collectors.joining("\n"));
        return new Reply("Upcoming deadlines\n\n" + lines, reminders.stream().map(BotCommands::doneButton).toList());
    }

    private String complete(long chatId, String argument) throws SQLException {
        long id;
        try {
            id = Long.parseLong(argument);
        } catch (NumberFormatException invalidId) {
            return "Use /done followed by a reminder ID.";
        }
        if (id < 1) {
            return "Use /done followed by a reminder ID.";
        }
        return store.complete(chatId, id) ? "Closed #" + id : "That reminder is already closed or missing.";
    }

    static Button doneButton(Reminder reminder) {
        return new Button("Done #" + reminder.id(), "done:" + reminder.id());
    }

    private static boolean isPrivate(Message message) {
        return message != null && "private".equals(message.chatType());
    }

    record Reply(String text, List<Button> buttons) {
        Reply(String text) {
            this(text, List.of());
        }
    }
}
