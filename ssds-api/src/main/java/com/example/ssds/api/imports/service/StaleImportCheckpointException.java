package com.example.ssds.api.imports.service;

/** Another worker has already committed this source range. Do not retry or fail its batch. */
final class StaleImportCheckpointException extends RuntimeException {}
