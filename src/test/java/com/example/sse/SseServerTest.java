package com.example.sse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseServerTest {
    private SseServer server;
    private HttpClient http;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = new SseServer("127.0.0.1", 0, 10, 1_000);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void healthEndpointReportsStatus() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health")).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"ok\""));
        assertTrue(response.body().contains("\"clients\":0"));
    }

    @Test
    void publishDeliversSseEventToConnectedClient() throws Exception {
        HttpRequest streamRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/events")).GET().build();
        HttpResponse<java.io.InputStream> stream = http.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
        try (var input = stream.body();
             var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            assertEquals(200, stream.statusCode());
            assertTrue(stream.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
            assertEquals("retry: 3000", reader.readLine());
            assertEquals(": connected", reader.readLine());
            assertEquals("", reader.readLine());

            HttpRequest publishRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/publish"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"event\":\"order.created\",\"data\":{\"orderId\":42}}"))
                    .build();
            HttpResponse<String> published = http.send(publishRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(202, published.statusCode());
            assertTrue(published.body().contains("\"id\":\"1\""));

            assertEquals("id: 1", reader.readLine());
            assertEquals("event: order.created", reader.readLine());
            assertEquals("data: {\"orderId\":42}", reader.readLine());
            assertEquals("", reader.readLine());
        }
    }

    @Test
    void rejectsInvalidEventName() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/publish"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"event\":\"bad event\",\"data\":1}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("event must be"));
    }
}
