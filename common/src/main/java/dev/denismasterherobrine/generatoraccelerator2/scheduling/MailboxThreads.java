package dev.denismasterherobrine.generatoraccelerator2.scheduling;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;

/**
 * Own threads for a dimension's chunk scheduling mailboxes.
 *
 * <p>Vanilla runs the task dispatchers, the worldgen executor and the light executor on the shared worker pool.
 * A pool worker drains its own queue before it looks at other work. So once the steps run in parallel, the
 * mailboxes wait behind hundreds of step bodies, and dispatch stalls. The light engine, one thread's worth
 * of work by design, only gets to run when the pool runs dry, and so holds finished chunks back from FULL.
 * Here the dispatchers and the worldgen executor share one thread, and the light executor gets another. Both threads
 * only schedule and light, and nothing they run waits on the pool. What runs, and in what order within each
 * executor, is unchanged.
 */
public final class MailboxThreads {
    private MailboxThreads() {}

    private static final Logger LOGGER = Ga2Log.get("Scheduling");
    private static final AtomicInteger IDS = new AtomicInteger();

    /**
     * A single daemon thread that exits after 30 s without work. It runs above the worker pool, at Java priority 7
     * (Windows' "above normal"). Priorities 5 and 6 are both "normal" on Windows. These threads are single stages that
     * every chunk goes through. At the workers' priority, the system gave them a time slice only now and then while
     * the pool kept every processor busy. A light update of 10,000 entries took 185-370 ms instead of a few. The
     * threads wait parked when idle. They never outrank the thread that creates them. That thread is the server
     * thread, making the dimension's chunk map. Vanilla runs it at 8 on machines with more than 4 processors. So mods
     * that lower the integrated server's priority so the client renders smoothly (ModernFix) keep that order.
     */
    public static Executor dedicated(String name) {
        int id = IDS.incrementAndGet();
        int priority = Math.min(Thread.NORM_PRIORITY + 2, Thread.currentThread().getPriority());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, name + " #" + id);
            thread.setDaemon(true);
            thread.setPriority(priority);
            thread.setUncaughtExceptionHandler(MailboxThreads::onException);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * As on vanilla's worker threads ({@code Util.onThreadException}). The crash report goes to the server, which stops with
     * it. Otherwise a failed chunk step would leave the chunks waiting for it, and the server would wait forever.
     */
    private static void onException(Thread thread, Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException completion && completion.getCause() != null ? completion.getCause() : throwable;
        CrashReport report = cause instanceof ReportedException reported ? reported.getReport()
            : CrashReport.forThrowable(cause, "Exception on worker thread");
        report.addCategory("ThreadInfo").setDetail("Name", thread.getName());
        LOGGER.error("uncaught exception on {}\n{}", thread.getName(), report.getFriendlyReport(ReportType.CRASH));
        BlockableEventLoop.relayDelayCrash(report);
    }
}
