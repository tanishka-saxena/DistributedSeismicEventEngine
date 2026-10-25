package com.seismic.ui;

import javafx.concurrent.Task;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs slow work (files, database, starting/stopping threads) off the JavaFX thread with a JavaFX
 * {@link Task}. The success and failure callbacks are delivered on the JavaFX thread by Task itself,
 * so controllers can update controls in them directly.
 */
public final class FxTasks {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ui-worker-" + COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private FxTasks() {
    }

    public static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> onFailure.accept(task.getException()));
        WORKERS.submit(task);
    }

    /** Variant for work without a result. */
    public static void runAction(ThrowingRunnable work, Runnable onSuccess, Consumer<Throwable> onFailure) {
        run(() -> {
            work.run();
            return null;
        }, ignored -> onSuccess.run(), onFailure);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void shutdown() {
        WORKERS.shutdown();
        try {
            WORKERS.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
