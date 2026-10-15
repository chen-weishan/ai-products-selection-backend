package com.example.ssds.ingest.Threads;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ThreadsSearchClientTest {

    @Test
    void acceptsTaipeiBusinessDateAfterMidnightEvenWhenUtcIsPreviousDay() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/actors/futurizerush~meta-threads-scraper-zh-tw/run-sync-get-dataset-items", exchange -> {
            requests.incrementAndGet();
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ZoneId taipei = ZoneId.of("Asia/Taipei");
            Clock clock = Clock.fixed(Instant.parse("2026-10-14T16:36:00Z"), taipei);
            RestClient restClient = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build();
            ThreadsIngestProperties properties = new ThreadsIngestProperties(
                    "test-token", 10, 3, 0, "top");
            ThreadsSearchClient client = new ThreadsSearchClient(restClient, properties, clock);

            assertEquals(
                    0,
                    client.fetchEngagementHeatByDate(
                                    "巧克力",
                                    LocalDate.of(2026, 10, 2),
                                    LocalDate.of(2026, 10, 15))
                            .size());
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
