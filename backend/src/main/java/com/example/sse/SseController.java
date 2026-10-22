package com.example.sse;

import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class SseController {
    private static final long EVENT_INTERVAL_SECONDS = 5;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    private final AtomicLong eventIds = new AtomicLong();
    private final AtomicLong clientIds = new AtomicLong();
    private final Map<Long, StreamSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "sse-stream-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        // A zero timeout keeps the connection open until the client disconnects or the app shuts down.
        SseEmitter emitter = new SseEmitter(0L);
        long clientId = clientIds.incrementAndGet();
        StreamSession session = new StreamSession(emitter);
        sessions.put(clientId, session);

        Runnable cleanup = () -> {
            sessions.remove(clientId);
            ScheduledFuture<?> task = session.task;
            if (task != null) {
                task.cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .id(Long.toString(eventIds.incrementAndGet()))
                    .data(Map.of("clientId", clientId, "message", "SSE connection established", "time", Instant.now().toString())));
            session.task = scheduler.scheduleAtFixedRate(
                    new StreamTask(clientId, session), EVENT_INTERVAL_SECONDS, 1, TimeUnit.SECONDS);
        } catch (Exception exception) {
            cleanup.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @GetMapping(path = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        return Map.of("status", "UP", "activeStreams", sessions.size(), "time", Instant.now().toString());
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(session -> session.emitter.complete());
        scheduler.shutdownNow();
    }

    private final class StreamTask implements Runnable {
        private final long clientId;
        private final StreamSession session;
        private long ticks;

        private StreamTask(long clientId, StreamSession session) {
            this.clientId = clientId;
            this.session = session;
        }

        @Override
        public void run() {
            if (!sessions.containsKey(clientId)) {
                return;
            }
            try {
                ticks++;
                if (ticks % HEARTBEAT_INTERVAL_SECONDS == 0) {
                    // Comments keep proxies and browsers from considering an otherwise quiet stream idle.
                    session.emitter.send(SseEmitter.event().comment("heartbeat " + Instant.now()));
                }
                if (ticks % EVENT_INTERVAL_SECONDS == 0) {
                    session.emitter.send(SseEmitter.event()
                            .name("message")
                            .id(Long.toString(eventIds.incrementAndGet()))
                            .data(Map.of(
                                    "clientId", clientId,
                                    "sequence", ticks / EVENT_INTERVAL_SECONDS,
                                    "message", "Server event " + (ticks / EVENT_INTERVAL_SECONDS),
                                    "time", Instant.now().toString())));
                }
            } catch (Exception exception) {
                sessions.remove(clientId);
                session.emitter.completeWithError(exception);
            }
        }
    }

    private static final class StreamSession {
        private final SseEmitter emitter;
        private volatile ScheduledFuture<?> task;

        private StreamSession(SseEmitter emitter) {
            this.emitter = emitter;
        }
    }
}
