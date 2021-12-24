package dev.pluxury.expiry.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "123:test-token";
    private static final long CHAT_ID = 4294967301L;

    private final AtomicReference<Request> receivedRequest = new AtomicReference<>();
    private HttpServer server;
    private TelegramClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new TelegramClient(TOKEN, base, HttpClient.newHttpClient());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void pollsForMessagesAndCallbacksAndPreservesUnknownUpdateIds() throws Exception {
        respond(200, """
                {"ok":true,"result":[
                  {"update_id":101,"message":{"chat":{"id":4294967301,"type":"private"},"text":"/list"}},
                  {"update_id":102,"callback_query":{"id":"cb-1","message":{"chat":{"id":4294967301,"type":"private"}},"data":"done:7"}},
                  {"update_id":103,"edited_message":{}}
                ]}
                """);

        assertEquals(List.of(
                new TelegramClient.Update(101, new TelegramClient.Message(CHAT_ID, "private", "/list"), null),
                new TelegramClient.Update(102, null, new TelegramClient.Callback("cb-1",
                        new TelegramClient.Message(CHAT_ID, "private", null), "done:7")),
                new TelegramClient.Update(103, null, null)), client.updates(100));
        assertRequest("getUpdates", """
                {"offset":100,"timeout":25,"allowed_updates":["message","callback_query"]}
                """);
    }

    @Test
    void sendsMessageWithInlineButton() throws Exception {
        respond(200, """
                {"ok":true,"result":{"message_id":160}}
                """);

        client.send(CHAT_ID, "Return the camera", List.of(new TelegramClient.Button("Done", "done:7")));

        assertRequest("sendMessage", """
                {"chat_id":4294967301,"text":"Return the camera","reply_markup":{
                  "inline_keyboard":[[{"text":"Done","callback_data":"done:7"}]]
                }}
                """);
    }

    @Test
    void exposesOnlyTheApiErrorCode() {
        respond(200, """
                {"ok":false,"error_code":429,"description":"Unexpected request at /bot123:test-token/sendMessage"}
                """);

        IOException failure = assertThrows(IOException.class, () -> client.send(CHAT_ID, "Deadline", List.of()));

        assertEquals("Telegram sendMessage returned API error 429", failure.getMessage());
    }

    private void respond(int status, String body) {
        server.createContext("/", exchange -> {
            try (exchange) {
                receivedRequest.set(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        JSON.readTree(exchange.getRequestBody())));
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
        });
    }

    private void assertRequest(String method, String json) throws IOException {
        assertEquals(new Request("POST", "/bot" + TOKEN + "/" + method, "application/json", JSON.readTree(json)),
                receivedRequest.get());
    }

    private record Request(String method, String path, String contentType, JsonNode body) {
    }
}
