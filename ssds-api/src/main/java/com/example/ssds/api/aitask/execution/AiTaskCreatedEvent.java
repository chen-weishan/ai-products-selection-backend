package com.example.ssds.api.aitask.execution;

public record AiTaskCreatedEvent(Long taskId, boolean forceRefresh) {}
