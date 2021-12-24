package dev.pluxury.expiry.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class TelegramClient {
    private static final URI API_BASE = URI.create("https://api.telegram.org/");
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[0-9]+:[A-Za-z0-9_-]+");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int POLL_SECONDS = 25;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final URI botUri;

    public TelegramClient(String token) {
        this(token, API_BASE, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    TelegramClient(String token, URI apiBase, HttpClient http) {
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            throw new IllegalArgumentException("Invalid Telegram bot token format");
        }
        this.http = http;
        this.botUri = apiBase.resolve("/bot" + token + "/");
    }

    public List<Update> updates(long offset) throws IOException, InterruptedException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("offset", offset);
        payload.put("timeout", POLL_SECONDS);
        payload.putArray("allowed_updates").add("message").add("callback_query");

        String method = "getUpdates";
        JsonNode result = post(method, payload, POLL_TIMEOUT);
        if (!result.isArray()) {
            throw invalidResponse(method);
        }

        List<Update> updates = new ArrayList<>(result.size());
        for (JsonNode item : result) {
            JsonNode id = item.path("update_id");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 0) {
                throw invalidResponse(method);
            }

            updates.add(new Update(id.longValue(), message(item.path("message")),
                    callback(item.path("callback_query"))));
        }
        return updates;
    }

    public void send(long chatId, String text, List<Button> buttons) throws IOException, InterruptedException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        if (!buttons.isEmpty()) {
            ArrayNode keyboard = payload.putObject("reply_markup").putArray("inline_keyboard");
            for (Button button : buttons) {
                keyboard.addArray().addObject().put("text", button.label()).put("callback_data", button.data());
            }
        }

        String method = "sendMessage";
        if (!post(method, payload, REQUEST_TIMEOUT).isObject()) {
            throw invalidResponse(method);
        }
    }

    public void answer(String callbackId, String text) throws IOException, InterruptedException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("callback_query_id", callbackId);
        if (text != null && !text.isEmpty()) {
            payload.put("text", text);
        }

        String method = "answerCallbackQuery";
        if (!post(method, payload, REQUEST_TIMEOUT).booleanValue()) {
            throw invalidResponse(method);
        }
    }

    private JsonNode post(String method, ObjectNode payload, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(botUri.resolve(method))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new IOException("Telegram " + method + " request failed");
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Telegram " + method + " returned HTTP " + status);
        }

        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (JsonProcessingException error) {
            throw invalidResponse(method);
        }

        if (body == null || !body.path("ok").isBoolean()) {
            throw invalidResponse(method);
        }
        if (!body.get("ok").booleanValue()) {
            JsonNode errorCode = body.path("error_code");
            if (!errorCode.isIntegralNumber() || !errorCode.canConvertToInt()) {
                throw invalidResponse(method);
            }
            throw new IOException("Telegram " + method + " returned API error " + errorCode.intValue());
        }
        if (!body.hasNonNull("result")) {
            throw invalidResponse(method);
        }
        return body.get("result");
    }

    private static Message message(JsonNode node) {
        JsonNode chat = node.path("chat");
        JsonNode id = chat.path("id");
        JsonNode type = chat.path("type");
        if (!id.isIntegralNumber() || !id.canConvertToLong() || !type.isTextual()) {
            return null;
        }
        return new Message(id.longValue(), type.textValue(), node.path("text").textValue());
    }

    private static Callback callback(JsonNode node) {
        JsonNode id = node.path("id");
        JsonNode data = node.path("data");
        if (!id.isTextual() || !data.isTextual()) {
            return null;
        }
        return new Callback(id.textValue(), message(node.path("message")), data.textValue());
    }

    private static IOException invalidResponse(String method) {
        return new IOException("Telegram " + method + " returned an invalid response");
    }

    public record Update(long id, Message message, Callback callback) {
    }

    public record Message(long chatId, String chatType, String text) {
    }

    public record Callback(String id, Message message, String data) {
    }

    public record Button(String label, String data) {
    }
}
