package com.agentyard.worker.runtime;

import io.temporal.failure.ApplicationFailure;

/** Only authorization before side effects may produce this recoverable workflow control result. */
public final class ExecutionPaused {
    public static final String TYPE="EXECUTION_PAUSED";
    private ExecutionPaused() {}

    public static void rethrowIfPaused(RuntimeException failure) {
        if(failure instanceof WorkerHttpClient.WorkerHttpException http && http.statusCode()==423)
            throw ApplicationFailure.newNonRetryableFailure("Execution is paused before the operation starts",TYPE);
    }

    public static boolean isPaused(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof ApplicationFailure application && TYPE.equals(application.getType())) return true;
        return false;
    }
}
