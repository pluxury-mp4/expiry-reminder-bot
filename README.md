# Expiry Reminder Bot

A small Telegram bot for return windows, warranties, and other dates that tend to get buried in receipts or emails. Save a deadline in chat, check what's coming up, and close it when you're done.

## Run locally

Requires Java 17, Maven 3.8.4 or later, and a Telegram bot token. Create a bot with `@BotFather` and copy its token. [Telegram setup guide](https://core.telegram.org/bots/tutorial#obtain-your-bot-token)

From the repository root in Bash or Zsh:

```sh
mvn clean verify
printf 'Bot token: '
read -rs TELEGRAM_BOT_TOKEN
printf '\n'
export TELEGRAM_BOT_TOKEN
java -jar target/expiry-reminder-bot-0.1.0.jar
```

Open the bot's private chat and send `/help`. Run one instance for each token.

## In Telegram

| Command | What it does |
| --- | --- |
| `/add 2022-01-05 Return headphones` | Saves a reminder. Use your own date in `YYYY-MM-DD` format. |
| `/list` | Lists the next ten open reminders in deadline order. |
| `/done 1` | Closes reminder `#1`. The **Done #1** button in its notification does the same thing. |
| `/help` | Shows examples and available commands. |
| `/id` | Shows your private chat ID. |

## Configuration

| Environment variable | Default | What it does |
| --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | Required | Telegram token from `@BotFather`. |
| `TELEGRAM_ALLOWED_CHAT_IDS` | Any private chat | Optional comma-separated list of private chat IDs. Use `/id` to find yours. |
| `EXPIRY_ZONE` | `UTC` | IANA time zone for deciding which dates are due. |
| `EXPIRY_LEAD_DAYS` | `3` | Number of days before the deadline to send the reminder, from `0` to `30`. |
| `EXPIRY_DATABASE` | `data/reminders.db` | Path to the SQLite database. |

## How it works

The bot long-polls Telegram and checks SQLite for due reminders every minute. Each check includes overdue items, so deadlines can still surface after downtime. The database records delivery after Telegram accepts a notification. A restart between those two operations can produce a repeat.

The build uses a late-2021 stack with Java 17, Spring Boot 2.6.2, Jackson 2.13.1, and SQLite JDBC 3.36.0.3. The tests use a local Telegram API fixture and run without a bot token.
