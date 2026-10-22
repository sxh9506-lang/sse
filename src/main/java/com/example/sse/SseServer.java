package com.example.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** A small, dependency-light HTTP service that broadcasts JSON events over SSE. */
public final class SseServer implements AutoCloseable {
    private static final int DEFAULT_PORT = 3000;
    private static final int DEFAULT_HISTORY_SIZE = 100;
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int CLIENT_QUEUE_SIZE = 128;
    private static final long DEFAULT_HEARTBEAT_MILLIS = 25_000;
    private static final Pattern EVENT_NAME = Pattern.compile("[\\w.-]{1,128}");
    private static final String CLOSE_SIGNAL = "";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Object stateLock = new Object();
    private final ArrayDeque<SseEvent> history = new ArrayDeque<>();
    private final List<Client> clients = new ArrayList<>();
    private final int historySize;
    private final long heartbeatMillis;
    private final HttpServer httpServer;
    private final ExecutorService executor;
    private long nextEventId = 1;

    public SseServer(String host, int port, int historySize, long heartbeatMillis) throws IOException {
        if (historySize < 0) throw new IllegalArgumentException("historySize must be >= 0");
        if (heartbeatMillis < 1) throw new IllegalArgumentException("heartbeatMillis must be > 0");
        this.historySize = historySize;
        this.heartbeatMillis = heartbeatMillis;
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpServer.setExecutor(executor);
        this.httpServer.createContext("/events", this::handleEvents);
        this.httpServer.createContext("/publish", this::handlePublish);
        this.httpServer.createContext("/health", this::handleHealth);
        this.httpServer.createContext("/", this::handleRoot);
    }

    public static void main(String[] args) throws IOException {
        String host = System.getenv().getOrDefault("HOST", "0.0.0.0");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", String.valueOf(DEFAULT_PORT)));
        int historySize = Integer.parseInt(System.getenv().getOrDefault("HISTORY_SIZE", String.valueOf(DEFAULT_HISTORY_SIZE)));
        long heartbeatMillis = Long.parseLong(System.getenv().getOrDefault(
                "HEARTBEAT_MS", String.valueOf(DEFAULT_HEARTBEAT_MILLIS)));

        SseServer service = new SseServer(host, port, historySize, heartbeatMillis);
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "sse-shutdown"));
        service.start();
        System.out.printf("SSE service listening on http://%s:%d%n", host, port);
    }

    public void start() {
        httpServer.start();
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public int clientCount() {
        synchronized (stateLock) {
            return clients.size();
        }
    }

    @Override
    public void close() {
        List<Client> toClose;
        synchronized (stateLock) {
            toClose = new ArrayList<>(clients);
            clients.clear();
        }
        toClose.forEach(Client::terminate);
        httpServer.stop(0);
        executor.shutdownNow();
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
            sendError(exchange, 404, "Not found");
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("name", "SSE long-connection service");
        ObjectNode endpoints = body.putObject("endpoints");
        endpoints.put("stream", "GET /events");
        endpoints.put("publish", "POST /publish");
        endpoints.put("health", "GET /health");
        sendJson(exchange, 200, body);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/health".equals(exchange.getRequestURI().getPath())) {
            sendError(exchange, 404, "Not found");
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("status", "ok");
        synchronized (stateLock) {
            body.put("clients", clients.size());
            body.put("historySize", history.size());
        }
        sendJson(exchange, 200, body);
    }

    private void handlePublish(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod()) || !"/publish".equals(exchange.getRequestURI().getPath())) {
            sendError(exchange, 404, "Not found");
            return;
        }

        JsonNode body;
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                sendError(exchange, 413, "Request body is too large (maximum 1 MiB)");
                return;
            }
            body = mapper.readTree(bytes);
        } catch (IOException exception) {
            sendError(exchange, 400, "Request body must be valid JSON");
            return;
        }

        if (body == null || !body.isObject() || !body.has("data")) {
            sendError(exchange, 400, "Expected a JSON object with a data field");
            return;
        }

        JsonNode eventNode = body.get("event");
        String eventName = eventNode == null || eventNode.isNull() ? "message"
                : eventNode.isTextual() ? eventNode.asText() : null;
        if (eventName == null || !EVENT_NAME.matcher(eventName).matches()) {
            sendError(exchange, 400, "event must be 1-128 letters, numbers, dots, underscores, or hyphens");
            return;
        }

        SseEvent event;
        int onlineClients;
        synchronized (stateLock) {
            event = new SseEvent(nextEventId++, eventName, body.get("data").deepCopy());
            if (historySize > 0) {
                history.addLast(event);
                while (history.size() > historySize) history.removeFirst();
            }
            List<Client> fullClients = new ArrayList<>();
            for (Client client : clients) {
                if (!client.offer(encodeEvent(event))) fullClients.add(client);
            }
            if (!fullClients.isEmpty()) {
                clients.removeAll(fullClients);
                fullClients.forEach(Client::terminate);
            }
            onlineClients = clients.size();
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("published", true);
        response.put("id", Long.toString(event.id()));
        response.put("clients", onlineClients);
        sendJson(exchange, 202, response);
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/events".equals(exchange.getRequestURI().getPath())) {
            sendError(exchange, 404, "Not found");
            return;
        }

        long cursor = readLastEventId(exchange);
        Client client = new Client(Math.max(CLIENT_QUEUE_SIZE, historySize));
        synchronized (stateLock) {
            clients.add(client);
            if (cursor >= 0) {
                for (SseEvent event : history) {
                    if (event.id() > cursor && !client.offer(encodeEvent(event))) {
                        clients.remove(client);
                        client.terminate();
                        sendError(exchange, 503, "Unable to queue event replay");
                        return;
                    }
                }
            }
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write("retry: 3000\n: connected\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            while (!client.isTerminated()) {
                String frame = client.poll(heartbeatMillis);
                if (frame == null) {
                    frame = ": heartbeat " + System.currentTimeMillis() + "\n\n";
                } else if (CLOSE_SIGNAL.equals(frame)) {
                    break;
                }
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (IOException ignored) {
            // A disconnected browser closes the stream; cleanup happens in finally.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (stateLock) {
                clients.remove(client);
            }
            client.terminate();
            exchange.close();
        }
    }

    private long readLastEventId(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (value == null || value.isBlank()) {
            value = queryParameter(exchange.getRequestURI().getRawQuery(), "lastEventId");
        }
        if (value == null) return 0;
        try {
            long id = Long.parseLong(value.trim());
            return id < 0 ? -1 : id;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String queryParameter(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String parameter : query.split("&")) {
            String[] pair = parameter.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private String encodeEvent(SseEvent event) throws IOException {
        return "id: " + event.id() + "\nevent: " + event.name() + "\ndata: "
                + mapper.writeValueAsString(event.data()) + "\n\n";
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("error", message);
        sendJson(exchange, status, body);
    }

    private void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record SseEvent(long id, String name, JsonNode data) {}

    private static final class Client {
        private final BlockingQueue<String> queue;
        private volatile boolean terminated;

        Client(int queueSize) {
            this.queue = new ArrayBlockingQueue<>(queueSize);
        }

        boolean offer(String event) {
            return !terminated && queue.offer(event);
        }

        String poll(long timeoutMillis) throws InterruptedException {
            return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        boolean isTerminated() {
            return terminated;
        }

        void terminate() {
            terminated = true;
            queue.clear();
            queue.offer(CLOSE_SIGNAL);
        }
    }
}
