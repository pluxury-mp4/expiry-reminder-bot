package dev.pluxury.expiry.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderStoreTest {
    private static final long CHAT_ID = 10;
    private static final long OTHER_CHAT_ID = 20;
    private static final LocalDate TODAY = LocalDate.of(2021, 12, 15);

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesAndDeduplicatesRemindersAfterReopening() throws Exception {
        Path databasePath = temporaryDirectory.resolve("data/reminders.sqlite");
        Reminder original;
        try (ReminderStore store = new ReminderStore(databasePath)) {
            original = store.add(CHAT_ID, 100, "Headphones return window", TODAY.plusDays(3));
        }

        try (ReminderStore store = new ReminderStore(databasePath)) {
            Reminder replay = store.add(CHAT_ID, 100, "A different title", TODAY.plusDays(1));
            Reminder otherChat = store.add(OTHER_CHAT_ID, 100, "Passport renewal", TODAY.plusDays(5));

            assertEquals(original, replay);
            assertEquals(List.of(original), store.list(CHAT_ID, 10));
            assertEquals(List.of(otherChat), store.list(OTHER_CHAT_ID, 10));
        }
    }

    @Test
    void listsOpenRemindersByDateAndRestrictsCompletionToTheirChat() throws Exception {
        try (ReminderStore store = new ReminderStore(temporaryDirectory.resolve("reminders.sqlite"))) {
            Reminder later = store.add(CHAT_ID, 100, "Bike warranty", TODAY.plusDays(6));
            Reminder soon = store.add(CHAT_ID, 101, "Return a charger", TODAY.plusDays(2));
            Reminder sameDay = store.add(CHAT_ID, 102, "Cancel trial", TODAY.plusDays(2));
            Reminder otherChat = store.add(OTHER_CHAT_ID, 103, "Return a kettle", TODAY);

            assertEquals(List.of(soon, sameDay, later), store.list(CHAT_ID, 10));
            assertEquals(List.of(soon, sameDay), store.list(CHAT_ID, 2));
            assertFalse(store.complete(OTHER_CHAT_ID, soon.id()));
            assertTrue(store.complete(CHAT_ID, soon.id()));
            assertFalse(store.complete(CHAT_ID, soon.id()));
            assertEquals(List.of(sameDay, later), store.list(CHAT_ID, 10));
            assertEquals(List.of(otherChat), store.list(OTHER_CHAT_ID, 10));
        }
    }

    @Test
    void catchesUpOverdueRemindersAndPersistsDeliveryAndCompletion() throws Exception {
        Path databasePath = temporaryDirectory.resolve("reminders.sqlite");
        Reminder overdue;
        Reminder dueToday;
        Reminder boundary;
        try (ReminderStore store = new ReminderStore(databasePath)) {
            store.add(CHAT_ID, 100, "Later", TODAY.plusDays(4));
            boundary = store.add(OTHER_CHAT_ID, 101, "End of the lead window", TODAY.plusDays(3));
            dueToday = store.add(CHAT_ID, 102, "Today", TODAY);
            overdue = store.add(CHAT_ID, 103, "Missed while offline", TODAY.minusDays(2));
            Reminder sent = store.add(CHAT_ID, 104, "Already notified", TODAY.minusDays(1));
            Reminder completed = store.add(CHAT_ID, 105, "Already completed", TODAY);

            store.markNotified(sent.id());
            assertTrue(store.complete(CHAT_ID, completed.id()));
            assertTrue(store.list(CHAT_ID, 10).contains(sent));
        }

        try (ReminderStore store = new ReminderStore(databasePath)) {
            assertEquals(List.of(overdue, dueToday, boundary), store.due(TODAY, 3));
            store.markNotified(overdue.id());
            assertEquals(List.of(dueToday, boundary), store.due(TODAY, 3));
        }
    }
}
