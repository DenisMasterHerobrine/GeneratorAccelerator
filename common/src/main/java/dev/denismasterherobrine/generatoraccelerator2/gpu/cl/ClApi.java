package dev.denismasterherobrine.generatoraccelerator2.gpu.cl;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The OpenCL calls made by the GPU path. Handles are {@code long} and data are direct buffers, so no binding type crosses
 * into GA2's own class loader. The only implementation ({@code gpu.cl.lwjgl.LwjglCl}) is loaded by {@link ClLoader} in a
 * dedicated class loader alongside an LWJGL core. Every call throws {@link ClException} on an OpenCL error.
 */
public interface ClApi {
    int CL_COMPLETE = 0x0;

    /** Memory flags (OpenCL's values). */
    long MEM_READ_WRITE = 1L, MEM_WRITE_ONLY = 1L << 1, MEM_READ_ONLY = 1L << 2, MEM_ALLOC_HOST_PTR = 1L << 4, MEM_COPY_HOST_PTR = 1L << 5;

    /** Every GPU device of every platform, in platform then device order (also CPU and accelerator devices when {@code all}). */
    List<ClDeviceInfo> devices(boolean all);

    long createContext(long platform, long device);

    /** Creates an in-order queue. {@code lowPriority} requests the low priority of {@code cl_khr_priority_hints} where supported. */
    long createQueue(long context, long device, boolean profiling, boolean lowPriority);

    /** Builds a program from source. The build log goes to {@code log}. Throws with the log when the build fails. */
    long buildProgram(long context, long device, String source, String options, StringBuilder log);

    /** Builds a program from a binary previously returned by {@link #programBinary}. Throws when the device rejects it. */
    long buildProgramFromBinary(long context, long device, byte[] binary, String options, StringBuilder log);

    /** The device binary of a built program (one device), for the binary cache. */
    byte[] programBinary(long program);

    long createKernel(long program, String name);

    long createBuffer(long context, long flags, long bytes);

    /** A buffer initialized from {@code data} (copied). */
    long createBuffer(long context, long flags, ByteBuffer data);

    /**
     * Host memory the device can transfer to and from without a staging copy: a buffer allocated in pinned host memory and mapped
     * once. Reads into it and writes from it run asynchronously (NVIDIA serializes transfers to and from ordinary Java memory).
     * {@code memOut[0]} receives the buffer object, for {@link #unpin}.
     */
    ByteBuffer pinned(long context, long queue, long bytes, long[] memOut);

    void unpin(long queue, long mem, ByteBuffer mapped);

    /** Writes {@code data} (position to limit) at {@code offset}. Returns an event (0 when blocking). */
    long write(long queue, long mem, boolean blocking, long offset, ByteBuffer data);

    /** Reads into {@code into} (position to limit) from {@code offset}. Returns an event (0 when blocking). */
    long read(long queue, long mem, boolean blocking, long offset, ByteBuffer into);

    /** Copies {@code bytes} from one buffer to another on the device. Returns an event. */
    long copy(long queue, long src, long dst, long srcOffset, long dstOffset, long bytes);

    void argMem(long kernel, int index, long mem);

    void argInt(long kernel, int index, int value);

    void argLong(long kernel, int index, long value);

    /**
     * Enqueues a 1D launch. With {@code local} &lt;= 0, the driver picks the local size. The global size is rounded up to the
     * local size. Returns the launch event.
     */
    long launch(long queue, long kernel, long global, long local);

    /**
     * {@link #launch(long, long, long, long)}, started once {@code after} has completed. {@code after} is a flushed event of any
     * queue of the context.
     */
    long launch(long queue, long kernel, long global, long local, long after);

    /**
     * Makes later commands on {@code queue} wait for {@code after}, a flushed event of any queue of the context. Returns the
     * barrier's event. With {@code after} = 0, the barrier is a marker. Its event completes once everything enqueued before it
     * has completed.
     */
    long barrier(long queue, long after);

    /** {@link #CL_COMPLETE} once done, a positive status while queued or running, negative after an error. */
    int eventStatus(long event);

    void waitFor(long event);

    /** What {@link #wakeOnComplete} runs (on a driver thread) when an event completes. */
    void setWaker(Runnable waker);

    /** Runs the waker once {@code event} completes (or fails). */
    void wakeOnComplete(long event);

    /** Device time of a finished event (needs a profiling queue). */
    long eventNanos(long event);

    void flush(long queue);

    void finish(long queue);

    void releaseEvent(long event);

    void releaseMem(long mem);

    void releaseKernel(long kernel);

    void releaseProgram(long program);

    void releaseQueue(long queue);

    void releaseContext(long context);

    /** The LWJGL version the binding runs against. */
    String lwjglVersion();
}
