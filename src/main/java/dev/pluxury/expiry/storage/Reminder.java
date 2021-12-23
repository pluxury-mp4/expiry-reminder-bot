package dev.pluxury.expiry.storage;

import java.time.LocalDate;

public record Reminder(long id, long chatId, String title, LocalDate expiresOn) {
}
