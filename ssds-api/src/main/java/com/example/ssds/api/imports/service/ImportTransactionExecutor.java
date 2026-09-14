package com.example.ssds.api.imports.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Central FR-09 transaction boundary with a runtime-configurable timeout. */
@Component
public class ImportTransactionExecutor {
    private final ImportDatabaseLimits databaseLimits;
    private final TransactionTemplate required;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate requiresNew;

    public ImportTransactionExecutor(
            PlatformTransactionManager transactionManager,
            ImportDatabaseLimits databaseLimits
    ) {
        this.databaseLimits = databaseLimits;
        int timeout = databaseLimits.seconds();
        required = template(transactionManager, timeout, false,
                TransactionDefinition.PROPAGATION_REQUIRED);
        readOnly = template(transactionManager, timeout, true,
                TransactionDefinition.PROPAGATION_REQUIRED);
        requiresNew = template(transactionManager, timeout, false,
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T required(Supplier<T> action) {
        return required.execute(status -> limited(action));
    }

    public void required(Runnable action) {
        required.executeWithoutResult(status -> limited(action));
    }

    public <T> T readOnly(Supplier<T> action) {
        return readOnly.execute(status -> limited(action));
    }

    public <T> T requiresNew(Supplier<T> action) {
        return requiresNew.execute(status -> limited(action));
    }

    private <T> T limited(Supplier<T> action) {
        databaseLimits.apply();
        return action.get();
    }

    private void limited(Runnable action) {
        databaseLimits.apply();
        action.run();
    }

    private static TransactionTemplate template(
            PlatformTransactionManager manager,
            int timeout,
            boolean readOnly,
            int propagation
    ) {
        var template = new TransactionTemplate(manager);
        template.setTimeout(timeout);
        template.setReadOnly(readOnly);
        template.setPropagationBehavior(propagation);
        return template;
    }
}
