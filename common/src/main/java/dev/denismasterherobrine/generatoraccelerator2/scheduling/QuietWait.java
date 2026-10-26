package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * Waits for a future that one of GA2's own threads completes, such as a GPU queue, without the worker pool's compensation.
 *
 * <p>A pool thread that waits in {@code CompletableFuture.get} counts as blocked. The pool then starts another thread for the
 * time of the wait. That thread stays for 60 s after its last task, and every buffer kept per thread is kept once more for it.
 * In a pregeneration the GPU surface waits alone took the pool from 15 to 49 threads. The futures waited for here never need
 * a pool thread to complete, so the pool loses nothing by not compensating.
 */
public final class QuietWait {
    private QuietWait() {}

    /**
     * {@code future.get(timeoutMs, MILLISECONDS)}, with the same exceptions, parked without compensation. The time the whole JVM
     * stood still meanwhile does not count toward the timeout ({@link JvmStalls}).
     */
    public static <T> T get(CompletableFuture<T> future, long timeoutMs) throws InterruptedException, ExecutionException, TimeoutException {
        if (!future.isDone()) {
            Thread waiter = Thread.currentThread();
            // An unpark after the wait is over is harmless. Parks may always return early.
            future.whenComplete((r, e) -> LockSupport.unpark(waiter));
            JvmStalls.Wait wait = new JvmStalls.Wait(TimeUnit.MILLISECONDS.toNanos(timeoutMs));
            while (!future.isDone()) {
                long left = wait.left();
                if (left == 0) {
                    throw new TimeoutException();
                }
                LockSupport.parkNanos(future, left);
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        }
        // Done, so this returns or throws at once.
        return future.get();
    }
}
