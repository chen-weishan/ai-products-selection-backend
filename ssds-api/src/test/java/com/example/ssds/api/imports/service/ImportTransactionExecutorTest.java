package com.example.ssds.api.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ImportTransactionExecutorTest {

    @Test
    void appliesConfiguredTimeoutAndReadOnlyThroughTransactionInfrastructure() {
        var manager = mock(PlatformTransactionManager.class);
        var limits = mock(ImportDatabaseLimits.class);
        when(limits.seconds()).thenReturn(17);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var executor = new ImportTransactionExecutor(manager, limits);

        assertThat(executor.readOnly(() -> "ok")).isEqualTo("ok");

        var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getTimeout()).isEqualTo(17);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        verify(limits).apply();
        verify(manager).commit(any());
    }

    @Test
    void requiresNewUsesTheSameConfiguredTimeout() {
        var manager = mock(PlatformTransactionManager.class);
        var limits = mock(ImportDatabaseLimits.class);
        when(limits.seconds()).thenReturn(23);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var executor = new ImportTransactionExecutor(manager, limits);

        executor.requiresNew(() -> 1);

        var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getTimeout()).isEqualTo(23);
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
