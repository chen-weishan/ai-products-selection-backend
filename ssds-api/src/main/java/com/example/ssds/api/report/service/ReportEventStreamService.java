package com.example.ssds.api.report.service;

import com.example.ssds.api.report.dto.ReportJobResponse;
import com.example.ssds.infra.entity.ReportJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Keeps page-scoped SSE connections and sends each user only their own report updates. */
@Service
public class ReportEventStreamService {
    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final long RECONNECT_MILLIS = 3_000L;

    private final Map<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public ReportEventStreamService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SseEmitter subscribe(String requesterEmail) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.computeIfAbsent(requesterEmail, ignored -> new CopyOnWriteArraySet<>())
                .add(emitter);
        emitter.onCompletion(() -> remove(requesterEmail, emitter));
        emitter.onTimeout(() -> remove(requesterEmail, emitter));
        emitter.onError(ignored -> remove(requesterEmail, emitter));
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .reconnectTime(RECONNECT_MILLIS)
                    .data(Map.of("connectedAt", Instant.now().toString())));
        } catch (IOException exception) {
            remove(requesterEmail, emitter);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    /** Captures a safe snapshot now and sends it only after the surrounding transaction commits. */
    public void publishAfterCommit(ReportJob job) {
        if (job.getRequestedBy() == null || job.getRequestedBy().getEmail() == null) {
            return;
        }
        String requesterEmail = job.getRequestedBy().getEmail();
        ReportJobResponse response = ReportJobResponse.from(job, mapper);
        Runnable publish = () -> send(requesterEmail, "report-status", response);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    @Scheduled(fixedDelayString = "${ssds.report.sse-heartbeat:15s}")
    void heartbeat() {
        emitters.forEach((email, subscribers) -> send(
                email, "heartbeat", Map.of("sentAt", Instant.now().toString())));
    }

    int subscriberCount(String requesterEmail) {
        Set<SseEmitter> subscribers = emitters.get(requesterEmail);
        return subscribers == null ? 0 : subscribers.size();
    }

    private void send(String requesterEmail, String eventName, Object data) {
        Set<SseEmitter> subscribers = emitters.get(requesterEmail);
        if (subscribers == null) {
            return;
        }
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .reconnectTime(RECONNECT_MILLIS)
                        .data(data));
            } catch (IOException | IllegalStateException exception) {
                remove(requesterEmail, emitter);
                emitter.complete();
            }
        }
    }

    private void remove(String requesterEmail, SseEmitter emitter) {
        emitters.computeIfPresent(requesterEmail, (ignored, subscribers) -> {
            subscribers.remove(emitter);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }
}
