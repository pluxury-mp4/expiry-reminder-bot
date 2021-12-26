package dev.pluxury.expiry;

import dev.pluxury.expiry.storage.Reminder;
import dev.pluxury.expiry.storage.ReminderStore;
import dev.pluxury.expiry.telegram.TelegramClient;
import dev.pluxury.expiry.telegram.TelegramClient.Update;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
final class BotRuntime implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(BotRuntime.class);

    private final TelegramClient telegram;
    private final ReminderStore store;
    private final BotCommands commands;
    private final BotSettings settings;
    private final Clock clock;
    private final Thread polling = new Thread(this::poll, "telegram-polling");
    private final ScheduledExecutorService reminders = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "expiry-notifications"));
    private volatile boolean running;

    BotRuntime(TelegramClient telegram, ReminderStore store, BotCommands commands, BotSettings settings, Clock clock) {
        this.telegram = telegram;
        this.store = store;
        this.commands = commands;
        this.settings = settings;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        running = true;
        polling.start();
        reminders.scheduleWithFixedDelay(this::notifyDue, 0, 1, TimeUnit.MINUTES);
    }

    @Override
    public void destroy() {
        running = false;
        polling.interrupt();
        reminders.shutdownNow();
        try {
            polling.join(2_000);
            reminders.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void poll() {
        long offset = 0;
        try {
            while (running) {
                try {
                    List<Update> updates = telegram.updates(offset);
                    if (updates.isEmpty()) {
                        offset = 0;
                    }
                    for (Update update : updates) {
                        if (!running) {
                            return;
                        }
                        handle(update);
                        offset = Math.max(offset, update.id() + 1);
                    }
                } catch (IOException failure) {
                    LOG.warn("Polling will retry: {}", failure.getMessage());
                    Thread.sleep(2_000);
                } catch (SQLException failure) {
                    LOG.warn("Polling will retry after a database error (code {})", failure.getErrorCode());
                    Thread.sleep(2_000);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(Update update) throws SQLException, InterruptedException {
        try {
            if (update.callback() != null) {
                telegram.answer(update.callback().id(), commands.callback(update.callback()));
            } else if (update.message() != null) {
                var reply = commands.message(update.id(), update.message());
                if (reply.isPresent()) {
                    var response = reply.get();
                    telegram.send(update.message().chatId(), response.text(), response.buttons());
                }
            }
        } catch (IOException failure) {
            LOG.warn("Could not reply to update {}: {}", update.id(), failure.getMessage());
        }
    }

    private void notifyDue() {
        LocalDate today = LocalDate.now(clock);
        try {
            for (Reminder reminder : store.due(today, settings.leadDays())) {
                if (settings.allows(reminder.chatId())) {
                    notifyReminder(reminder, today);
                }
            }
        } catch (SQLException failure) {
            LOG.warn("Reminder delivery will retry after a database error (code {})", failure.getErrorCode());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void notifyReminder(Reminder reminder, LocalDate today) throws SQLException, InterruptedException {
        try {
            telegram.send(reminder.chatId(), notificationText(reminder, today), List.of(BotCommands.doneButton(reminder)));
            store.markNotified(reminder.id());
        } catch (IOException failure) {
            LOG.warn("Reminder #{} will retry: {}", reminder.id(), failure.getMessage());
        }
    }

    static String notificationText(Reminder reminder, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, reminder.expiresOn());
        String timing = days < 0 ? "Overdue" : switch ((int) days) {
            case 0 -> "Due today";
            case 1 -> "Due tomorrow";
            default -> "Due in " + days + " days";
        };
        return timing + " · " + reminder.title() + "\nDeadline: " + reminder.expiresOn();
    }
}
