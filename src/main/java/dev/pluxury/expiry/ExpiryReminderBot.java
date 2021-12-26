package dev.pluxury.expiry;

import dev.pluxury.expiry.storage.ReminderStore;
import dev.pluxury.expiry.telegram.TelegramClient;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class ExpiryReminderBot {
    public static void main(String[] args) {
        SpringApplication.run(ExpiryReminderBot.class, args);
    }

    @Bean
    BotSettings botSettings(Environment environment) {
        return BotSettings.from(environment);
    }

    @Bean
    ReminderStore reminderStore(BotSettings settings) throws SQLException, IOException {
        return new ReminderStore(settings.databasePath());
    }

    @Bean
    TelegramClient telegramClient(Environment environment) {
        return new TelegramClient(environment.getRequiredProperty("TELEGRAM_BOT_TOKEN"));
    }

    @Bean
    Clock expiryClock(BotSettings settings) {
        return Clock.system(settings.zone());
    }
}
