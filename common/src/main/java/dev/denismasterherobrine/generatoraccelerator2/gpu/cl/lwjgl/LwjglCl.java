package dev.denismasterherobrine.generatoraccelerator2.gpu.cl.lwjgl;

import static org.lwjgl.opencl.CL10.*;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClDeviceInfo;
import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.Version;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL11;
import org.lwjgl.opencl.CL12;
import org.lwjgl.opencl.CLEventCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link ClApi} over LWJGL's OpenCL bindings. Loaded only by {@code ClLoader}'s class loader, never by GA2's own. GA2's loader
 * cannot see the bindings. Constructed reflectively through the public no-argument constructor.
 */
public final class LwjglCl implements ClApi {
    private static final int CL_DEVICE_HOST_UNIFIED_MEMORY = 0x1035;

    public LwjglCl() {}

    private static void check(int rc, String what) {
        if (rc != CL_SUCCESS) {
            throw new ClException(what + " failed: OpenCL error " + rc, rc);
        }
    }

    @Override
    public List<ClDeviceInfo> devices(boolean all) {
        List<ClDeviceInfo> out = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer n = stack.mallocInt(1);
            int rc = clGetPlatformIDs(null, n);
            if (rc != CL_SUCCESS || n.get(0) == 0) {
                return out;
            }
            PointerBuffer platforms = stack.mallocPointer(n.get(0));
            check(clGetPlatformIDs(platforms, (IntBuffer) null), "clGetPlatformIDs");
            long type = all ? CL_DEVICE_TYPE_ALL : CL_DEVICE_TYPE_GPU;
            for (int p = 0; p < platforms.capacity(); p++) {
                long platform = platforms.get(p);
                IntBuffer dn = stack.mallocInt(1);
                if (clGetDeviceIDs(platform, type, null, dn) != CL_SUCCESS || dn.get(0) == 0) {
                    continue;
                }
                PointerBuffer devices = stack.mallocPointer(dn.get(0));
                if (clGetDeviceIDs(platform, type, devices, (IntBuffer) null) != CL_SUCCESS) {
                    continue;
                }
                String platformName = platformString(platform, CL_PLATFORM_NAME);
                for (int d = 0; d < devices.capacity(); d++) {
                    long device = devices.get(d);
                    out.add(new ClDeviceInfo(platform, device, platformName, deviceString(device, CL_DEVICE_NAME), deviceString(device, CL_DEVICE_VENDOR),
                        deviceString(device, CL_DEVICE_VERSION), deviceString(device, CL_DRIVER_VERSION), deviceString(device, CL_DEVICE_EXTENSIONS),
                        deviceLong(device, CL_DEVICE_TYPE), deviceInt(device, CL_DEVICE_MAX_COMPUTE_UNITS), deviceLong(device, CL_DEVICE_GLOBAL_MEM_SIZE),
                        deviceLong(device, CL_DEVICE_MAX_MEM_ALLOC_SIZE), deviceLong(device, CL12.CL_DEVICE_DOUBLE_FP_CONFIG),
                        deviceInt(device, CL_DEVICE_HOST_UNIFIED_MEMORY) != 0));
                }
            }
        }
        return out;
    }

    @Override
    public long createContext(long platform, long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            PointerBuffer props = stack.mallocPointer(3).put(CL_CONTEXT_PLATFORM).put(platform).put(0).flip();
            long context = clCreateContext(props, device, null, MemoryUtil.NULL, err);
            check(err.get(0), "clCreateContext");
            return context;
        }
    }

    @Override
    public long createQueue(long context, long device, boolean profiling) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long props = profiling ? CL_QUEUE_PROFILING_ENABLE : 0;
            long queue = clCreateCommandQueue(context, device, props, err);
            check(err.get(0), "clCreateCommandQueue");
            return queue;
        }
    }

    @Override
    public long buildProgram(long context, long device, String source, String options, StringBuilder log) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long program = clCreateProgramWithSource(context, source, err);
            check(err.get(0), "clCreateProgramWithSource");
            return finishBuild(program, device, options, log);
        }
    }

    @Override
    public long buildProgramFromBinary(long context, long device, byte[] binary, String options, StringBuilder log) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            IntBuffer status = stack.mallocInt(1);
            ByteBuffer bin = MemoryUtil.memAlloc(binary.length);
            try {
                bin.put(binary).flip();
                PointerBuffer devices = stack.mallocPointer(1).put(0, device);
                PointerBuffer lengths = stack.mallocPointer(1).put(0, binary.length);
                PointerBuffer binaries = stack.mallocPointer(1).put(0, MemoryUtil.memAddress(bin));
                long program = clCreateProgramWithBinary(context, devices, lengths, binaries, status, err);
                check(err.get(0), "clCreateProgramWithBinary");
                if (status.get(0) != CL_SUCCESS) {
                    clReleaseProgram(program);
                    throw new ClException("binary rejected: status " + status.get(0), status.get(0));
                }
                return finishBuild(program, device, options, log);
            } finally {
                MemoryUtil.memFree(bin);
            }
        }
    }

    private long finishBuild(long program, long device, String options, StringBuilder log) {
        int rc = clBuildProgram(program, device, options, null, MemoryUtil.NULL);
        String text = buildLog(program, device);
        if (log != null) {
            log.append(text);
        }
        if (rc != CL_SUCCESS) {
            clReleaseProgram(program);
            throw new ClException("clBuildProgram failed (" + rc + "):\n" + text, rc);
        }
        return program;
    }

    private static String buildLog(long program, long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, size);
            int n = (int) size.get(0);
            if (n <= 1) {
                return "";
            }
            ByteBuffer buf = MemoryUtil.memAlloc(n);
            try {
                clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, buf, null);
                return MemoryUtil.memUTF8(buf, n - 1);
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
    }

    @Override
    public byte[] programBinary(long program) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer sizes = stack.mallocPointer(1);
            check(clGetProgramInfo(program, CL_PROGRAM_BINARY_SIZES, sizes, null), "clGetProgramInfo(sizes)");
            int n = (int) sizes.get(0);
            if (n <= 0) {
                return new byte[0];
            }
            ByteBuffer buf = MemoryUtil.memAlloc(n);
            try {
                PointerBuffer ptrs = stack.mallocPointer(1).put(0, MemoryUtil.memAddress(buf));
                check(clGetProgramInfo(program, CL_PROGRAM_BINARIES, ptrs, null), "clGetProgramInfo(binaries)");
                byte[] out = new byte[n];
                buf.get(out);
                return out;
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
    }

    @Override
    public long createKernel(long program, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long k = clCreateKernel(program, name, err);
            check(err.get(0), "clCreateKernel " + name);
            return k;
        }
    }

    @Override
    public long createBuffer(long context, long flags, long bytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, flags, Math.max(16, bytes), err);
            check(err.get(0), "clCreateBuffer(" + bytes + ")");
            return mem;
        }
    }

    @Override
    public long createBuffer(long context, long flags, ByteBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, flags | CL_MEM_COPY_HOST_PTR, data, err);
            check(err.get(0), "clCreateBuffer(data)");
            return mem;
        }
    }

    @Override
    public ByteBuffer pinned(long context, long queue, long bytes, long[] memOut) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long size = Math.max(16, bytes);
            long mem = clCreateBuffer(context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, size, err);
            check(err.get(0), "clCreateBuffer(pinned " + bytes + ")");
            ByteBuffer b = clEnqueueMapBuffer(queue, mem, true, CL_MAP_READ | CL_MAP_WRITE, 0, size, null, null, err, null);
            if (err.get(0) != CL_SUCCESS || b == null) {
                clReleaseMemObject(mem);
                throw new ClException("clEnqueueMapBuffer failed: OpenCL error " + err.get(0), err.get(0));
            }
            memOut[0] = mem;
            return b.order(ByteOrder.nativeOrder());
        }
    }

    @Override
    public void unpin(long queue, long mem, ByteBuffer mapped) {
        if (mem != 0) {
            clEnqueueUnmapMemObject(queue, mem, mapped, null, null);
            clReleaseMemObject(mem);
        }
    }

    @Override
    public long write(long queue, long mem, boolean blocking, long offset, ByteBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer event = blocking ? null : stack.mallocPointer(1);
            check(clEnqueueWriteBuffer(queue, mem, blocking, offset, data, null, event), "clEnqueueWriteBuffer");
            return blocking ? 0L : event.get(0);
        }
    }

    @Override
    public long read(long queue, long mem, boolean blocking, long offset, ByteBuffer into) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer event = blocking ? null : stack.mallocPointer(1);
            check(clEnqueueReadBuffer(queue, mem, blocking, offset, into, null, event), "clEnqueueReadBuffer");
            return blocking ? 0L : event.get(0);
        }
    }

    @Override
    public long copy(long queue, long src, long dst, long srcOffset, long dstOffset, long bytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer event = stack.mallocPointer(1);
            check(CL10.clEnqueueCopyBuffer(queue, src, dst, srcOffset, dstOffset, bytes, null, event), "clEnqueueCopyBuffer");
            return event.get(0);
        }
    }

    @Override
    public void argMem(long kernel, int index, long mem) {
        check(clSetKernelArg1p(kernel, index, mem), "clSetKernelArg " + index);
    }

    @Override
    public void argInt(long kernel, int index, int value) {
        check(clSetKernelArg1i(kernel, index, value), "clSetKernelArg " + index);
    }

    @Override
    public void argLong(long kernel, int index, long value) {
        check(clSetKernelArg1l(kernel, index, value), "clSetKernelArg " + index);
    }

    @Override
    public long launch(long queue, long kernel, long global, long local) {
        return launch(queue, kernel, global, local, 0L);
    }

    @Override
    public long launch(long queue, long kernel, long global, long local, long after) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long l = local <= 0 ? 1 : local;
            PointerBuffer g = stack.mallocPointer(1).put(0, (global + l - 1) / l * l);
            PointerBuffer lo = local <= 0 ? null : stack.mallocPointer(1).put(0, local);
            PointerBuffer wait = after == 0L ? null : stack.mallocPointer(1).put(0, after);
            PointerBuffer event = stack.mallocPointer(1);
            check(clEnqueueNDRangeKernel(queue, kernel, 1, null, g, lo, wait, event), "clEnqueueNDRangeKernel");
            return event.get(0);
        }
    }

    @Override
    public long barrier(long queue, long after) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer wait = after == 0L ? null : stack.mallocPointer(1).put(0, after);
            PointerBuffer event = stack.mallocPointer(1);
            if (after == 0L) {
                check(CL12.clEnqueueMarkerWithWaitList(queue, null, event), "clEnqueueMarkerWithWaitList");
            } else {
                check(CL12.clEnqueueBarrierWithWaitList(queue, wait, event), "clEnqueueBarrierWithWaitList");
            }
            return event.get(0);
        }
    }

    @Override
    public int eventStatus(long event) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer v = stack.mallocInt(1);
            check(clGetEventInfo(event, CL_EVENT_COMMAND_EXECUTION_STATUS, v, null), "clGetEventInfo");
            return v.get(0);
        }
    }

    @Override
    public void waitFor(long event) {
        check(clWaitForEvents(event), "clWaitForEvents");
    }

    private volatile Runnable waker;
    /** One native callback shared by every event. LWJGL callbacks are native closures. This one lives as long as the binding. */
    private volatile CLEventCallback callback;

    @Override
    public void setWaker(Runnable waker) {
        this.waker = waker;
    }

    @Override
    public void wakeOnComplete(long event) {
        CLEventCallback cb = callback;
        if (cb == null) {
            synchronized (this) {
                cb = callback;
                if (cb == null) {
                    cb = CLEventCallback.create((ev, status, user) -> {
                        Runnable w = waker;
                        if (w != null) {
                            w.run();
                        }
                    });
                    callback = cb;
                }
            }
        }
        check(CL11.clSetEventCallback(event, CL_COMPLETE, cb, 0L), "clSetEventCallback");
    }

    @Override
    public long eventNanos(long event) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer start = stack.mallocLong(1);
            LongBuffer end = stack.mallocLong(1);
            check(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_START, start, null), "profiling start");
            check(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_END, end, null), "profiling end");
            return end.get(0) - start.get(0);
        }
    }

    @Override
    public void flush(long queue) {
        check(clFlush(queue), "clFlush");
    }

    @Override
    public void finish(long queue) {
        check(clFinish(queue), "clFinish");
    }

    @Override
    public void releaseEvent(long event) {
        if (event != 0) {
            clReleaseEvent(event);
        }
    }

    @Override
    public void releaseMem(long mem) {
        if (mem != 0) {
            clReleaseMemObject(mem);
        }
    }

    @Override
    public void releaseKernel(long kernel) {
        if (kernel != 0) {
            clReleaseKernel(kernel);
        }
    }

    @Override
    public void releaseProgram(long program) {
        if (program != 0) {
            clReleaseProgram(program);
        }
    }

    @Override
    public void releaseQueue(long queue) {
        if (queue != 0) {
            clReleaseCommandQueue(queue);
        }
    }

    @Override
    public void releaseContext(long context) {
        if (context != 0) {
            clReleaseContext(context);
        }
    }

    @Override
    public String lwjglVersion() {
        return Version.getVersion();
    }

    private static String platformString(long platform, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            if (clGetPlatformInfo(platform, param, (ByteBuffer) null, size) != CL_SUCCESS) {
                return "?";
            }
            int n = (int) size.get(0);
            ByteBuffer buf = stack.malloc(Math.max(1, n));
            clGetPlatformInfo(platform, param, buf, null);
            return MemoryUtil.memUTF8(buf, Math.max(0, n - 1));
        }
    }

    private static String deviceString(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            if (clGetDeviceInfo(device, param, (ByteBuffer) null, size) != CL_SUCCESS) {
                return "";
            }
            int n = (int) size.get(0);
            ByteBuffer buf = MemoryUtil.memAlloc(Math.max(1, n));
            try {
                clGetDeviceInfo(device, param, buf, null);
                return MemoryUtil.memUTF8(buf, Math.max(0, n - 1));
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
    }

    private static int deviceInt(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer v = stack.callocInt(1);
            clGetDeviceInfo(device, param, v, null);
            return v.get(0);
        }
    }

    private static long deviceLong(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer v = stack.callocLong(1);
            clGetDeviceInfo(device, param, v, null);
            return v.get(0);
        }
    }
}
