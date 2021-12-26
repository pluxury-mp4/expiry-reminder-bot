package dev.pluxury.expiry;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

record BotSettings(Path databasePath, ZoneId zone, int leadDays, Set<Long> allowedChats) {
    BotSettings {
        if (leadDays < 0 || leadDays > 30) {
            throw new IllegalArgumentException("EXPIRY_LEAD_DAYS must be between 0 and 30");
        }
        allowedChats = Set.copyOf(allowedChats);
    }

    static BotSettings from(Environment environment) {
        Set<Long> allowedChats = Arrays.stream(environment.getProperty("TELEGRAM_ALLOWED_CHAT_IDS", "").split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toUnmodifiableSet());

        return new BotSettings(
                Path.of(environment.getProperty("EXPIRY_DATABASE", "data/reminders.db")),
                ZoneId.of(environment.getProperty("EXPIRY_ZONE", "UTC")),
                environment.getProperty("EXPIRY_LEAD_DAYS", Integer.class, 3),
                allowedChats
        );
    }

    boolean allows(long chatId) {
        return allowedChats.isEmpty() || allowedChats.contains(chatId);
    }
}
