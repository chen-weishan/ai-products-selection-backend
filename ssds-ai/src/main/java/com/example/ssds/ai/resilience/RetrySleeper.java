package com.example.ssds.ai.resilience;

@FunctionalInterface
public interface RetrySleeper {
    void sleep(long millis) throws InterruptedException;
}
