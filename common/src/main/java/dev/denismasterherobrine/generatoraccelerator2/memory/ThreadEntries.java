package dev.denismasterherobrine.generatoraccelerator2.memory;

import java.lang.ref.WeakReference;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One object per thread, all of which a reader can walk, such as per-thread counts. A plain list of them grows by one entry for
 * every thread that ever used it. Worker threads come and go, so here the entry of a thread that ended is handed to
 * {@code retire} and dropped. That happens when the next thread makes its entry.
 */
public final class ThreadEntries<T> {
    private record Entry<T>(WeakReference<Thread> owner, T value) {}

    private final Queue<Entry<T>> all = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<T> local;
    private final Consumer<T> retire;

    /** @param retire receives the entry of an ended thread, for example to add its counts to a total */
    public ThreadEntries(Supplier<T> make, Consumer<T> retire) {
        this.retire = retire;
        this.local = ThreadLocal.withInitial(() -> {
            prune();
            T value = make.get();
            this.all.add(new Entry<>(new WeakReference<>(Thread.currentThread()), value));
            return value;
        });
    }

    /** This thread's entry. */
    public T get() {
        return this.local.get();
    }

    /** Every live thread's entry. An entry being retired at the same time may be seen or missed. */
    public void forEach(Consumer<T> action) {
        for (Entry<T> e : this.all) {
            action.accept(e.value());
        }
    }

    private void prune() {
        for (Entry<T> e : this.all) {
            Thread t = e.owner().get();
            if ((t == null || !t.isAlive()) && this.all.remove(e)) {
                this.retire.accept(e.value());
            }
        }
    }
}
