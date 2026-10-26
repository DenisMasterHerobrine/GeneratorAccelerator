package dev.denismasterherobrine.generatoraccelerator2.gpu;

import static org.lwjgl.opencl.CL10.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL12;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Minimal OpenCL host layer for the GPU go/no-go harness (LWJGL bindings): one GPU device, one in-order profiling queue,
 * programs built from source, device buffers, kernel launches timed by their events.
 */
public final class Cl implements AutoCloseable {
    public final long platform;
    public final long device;
    public final long context;
    public final long queue;
    public final String deviceName;
    public final String deviceVersion;
    public final String driverVersion;
    public final String platformName;
    public final int computeUnits;
    public final long doubleFpConfig;
    public final String extensions;
    private final List<Long> mems = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> programs = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> kernels = Collections.synchronizedList(new ArrayList<>());

    private Cl(long platform, long device) {
        this.platform = platform;
        this.device = device;
        this.platformName = platformString(platform, CL_PLATFORM_NAME);
        this.deviceName = deviceString(device, CL_DEVICE_NAME).trim();
        this.deviceVersion = deviceString(device, CL_DEVICE_VERSION);
        this.driverVersion = deviceString(device, CL_DRIVER_VERSION);
        this.extensions = deviceString(device, CL_DEVICE_EXTENSIONS);
        this.computeUnits = deviceInt(device, CL_DEVICE_MAX_COMPUTE_UNITS);
        this.doubleFpConfig = deviceLong(device, CL12.CL_DEVICE_DOUBLE_FP_CONFIG);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            PointerBuffer props = stack.mallocPointer(3).put(CL_CONTEXT_PLATFORM).put(platform).put(0).flip();
            this.context = clCreateContext(props, device, null, MemoryUtil.NULL, err);
            check(err.get(0), "clCreateContext");
            this.queue = clCreateCommandQueue(context, device, CL_QUEUE_PROFILING_ENABLE, err);
            check(err.get(0), "clCreateCommandQueue");
        }
    }

    /**
     * The test JVM already has LWJGL's native library loaded by the game's class loader. The copy of LWJGL core that comes with
     * the OpenCL binding lives in another loader and may not load the same file. Give that copy a renamed file of its own.
     */
    private static void isolateNatives() {
        String dir = System.getProperty("org.lwjgl.librarypath");
        System.out.println("[GpuProbe] natives: org.lwjgl.librarypath=" + dir + ", Configuration loader " + Configuration.class.getClassLoader()
            + ", CL10 loader " + CL10.class.getClassLoader() + ", Cl loader " + Cl.class.getClassLoader());
        if (dir == null) {
            dir = System.getProperty("java.io.tmpdir") + File.separator + "lwjgl_" + System.getProperty("user.name");
        }
        try {
            Path src;
            try (Stream<Path> s = Files.walk(Path.of(dir), 3)) {
                src = s.filter(p -> p.getFileName().toString().equals("lwjgl.dll") && p.toString().contains("x64")).findFirst().orElse(null);
            }
            if (src == null) {
                System.out.println("[GpuProbe] no lwjgl.dll under org.lwjgl.librarypath=" + dir);
                return;
            }
            Path dst = Path.of("build", "gpu-probe", "natives", "lwjgl_ga2gpu.dll").toAbsolutePath();
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            // a system property, not Configuration.set. This class may see the game's copy of Configuration, while the binding
            // sees its own copy
            System.setProperty("org.lwjgl.libname", dst.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The first GPU of the first platform that has one (-Dga2.gpu.device=N picks the N-th GPU overall). */
    public static Cl open() {
        if (Configuration.class.getClassLoader() != CL10.class.getClassLoader()) {
            isolateNatives();
        }
        int want = Integer.getInteger("ga2.gpu.device", 0);
        int seen = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer n = stack.mallocInt(1);
            check(clGetPlatformIDs(null, n), "clGetPlatformIDs");
            PointerBuffer platforms = stack.mallocPointer(n.get(0));
            check(clGetPlatformIDs(platforms, (IntBuffer) null), "clGetPlatformIDs");
            for (int p = 0; p < platforms.capacity(); p++) {
                long platform = platforms.get(p);
                IntBuffer dn = stack.mallocInt(1);
                if (clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, dn) != CL_SUCCESS || dn.get(0) == 0) {
                    continue;
                }
                PointerBuffer devices = stack.mallocPointer(dn.get(0));
                check(clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices, (IntBuffer) null), "clGetDeviceIDs");
                for (int d = 0; d < devices.capacity(); d++) {
                    if (seen++ == want) {
                        return new Cl(platform, devices.get(d));
                    }
                }
            }
        }
        throw new IllegalStateException("no OpenCL GPU device found");
    }

    public boolean hasFp64() {
        return doubleFpConfig != 0 && extensions.contains("cl_khr_fp64");
    }

    /** Builds a program and returns it with the build log. Throws with the log when the build fails. */
    public long build(String source, String options, StringBuilder logOut) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long program = clCreateProgramWithSource(context, source, err);
            check(err.get(0), "clCreateProgramWithSource");
            programs.add(program);
            int rc = clBuildProgram(program, device, options, null, MemoryUtil.NULL);
            String log = buildLog(program);
            if (logOut != null) {
                logOut.append(log);
            }
            if (rc != CL_SUCCESS) {
                throw new IllegalStateException("clBuildProgram failed (" + rc + "):\n" + log);
            }
            return program;
        }
    }

    private String buildLog(long program) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, size);
            ByteBuffer buf = BufferUtils.createByteBuffer((int) size.get(0));
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, buf, null);
            return MemoryUtil.memUTF8(buf, Math.max(0, buf.capacity() - 1));
        }
    }

    public long kernel(long program, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long k = clCreateKernel(program, name, err);
            check(err.get(0), "clCreateKernel " + name);
            kernels.add(k);
            return k;
        }
    }

    public long buffer(long bytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_WRITE, Math.max(16, bytes), err);
            check(err.get(0), "clCreateBuffer " + bytes);
            mems.add(mem);
            return mem;
        }
    }

    public long buffer(ByteBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, err);
            check(err.get(0), "clCreateBuffer(data)");
            mems.add(mem);
            return mem;
        }
    }

    public void release(long mem) {
        if (mems.remove(Long.valueOf(mem))) {
            clReleaseMemObject(mem);
        }
    }

    public void write(long mem, ByteBuffer data) {
        check(clEnqueueWriteBuffer(queue, mem, true, 0, data, null, null), "clEnqueueWriteBuffer");
    }

    public void read(long mem, ByteBuffer into) {
        check(clEnqueueReadBuffer(queue, mem, true, 0, into, null, null), "clEnqueueReadBuffer");
    }

    public static void arg(long kernel, int index, long mem) {
        check(clSetKernelArg1p(kernel, index, mem), "clSetKernelArg " + index);
    }

    public static void argInt(long kernel, int index, int v) {
        check(clSetKernelArg1i(kernel, index, v), "clSetKernelArg " + index);
    }

    public static void argLong(long kernel, int index, long v) {
        check(clSetKernelArg1l(kernel, index, v), "clSetKernelArg " + index);
    }

    /** Blocking read into a fresh native-order buffer. Returns the wall time in nanoseconds through {@code nanosOut[0]}. */
    public ByteBuffer readAll(long mem, long bytes, long[] nanosOut) {
        ByteBuffer b = BufferUtils.createByteBuffer((int) bytes);
        long t0 = System.nanoTime();
        read(mem, b);
        if (nanosOut != null) {
            nanosOut[0] = System.nanoTime() - t0;
        }
        return b;
    }

    /** Enqueues a 1D launch. Returns the launch's event, to be released with {@link #nanos}. */
    public long launch(long kernel, long globalSize, long localSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer global = stack.mallocPointer(1).put(0, roundUp(globalSize, localSize <= 0 ? 1 : localSize));
            PointerBuffer local = localSize <= 0 ? null : stack.mallocPointer(1).put(0, localSize);
            PointerBuffer event = stack.mallocPointer(1);
            check(clEnqueueNDRangeKernel(queue, kernel, 1, null, global, local, null, event), "clEnqueueNDRangeKernel");
            return event.get(0);
        }
    }

    public void finish() {
        check(clFinish(queue), "clFinish");
    }

    /** Device time of a finished event in nanoseconds. Releases the event. */
    public static long nanos(long event) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(clWaitForEvents(event), "clWaitForEvents");
            LongBuffer start = stack.mallocLong(1);
            LongBuffer end = stack.mallocLong(1);
            check(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_START, start, null), "profiling start");
            check(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_END, end, null), "profiling end");
            clReleaseEvent(event);
            return end.get(0) - start.get(0);
        }
    }

    private static long roundUp(long v, long m) {
        return (v + m - 1) / m * m;
    }

    public static void check(int rc, String what) {
        if (rc != CL_SUCCESS) {
            throw new IllegalStateException(what + " failed: OpenCL error " + rc);
        }
    }

    private static String platformString(long platform, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            clGetPlatformInfo(platform, param, (ByteBuffer) null, size);
            ByteBuffer buf = BufferUtils.createByteBuffer((int) size.get(0));
            clGetPlatformInfo(platform, param, buf, null);
            return MemoryUtil.memUTF8(buf, Math.max(0, buf.capacity() - 1));
        }
    }

    private static String deviceString(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            clGetDeviceInfo(device, param, (ByteBuffer) null, size);
            ByteBuffer buf = BufferUtils.createByteBuffer((int) size.get(0));
            clGetDeviceInfo(device, param, buf, null);
            return MemoryUtil.memUTF8(buf, Math.max(0, buf.capacity() - 1));
        }
    }

    private static int deviceInt(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer v = stack.mallocInt(1);
            clGetDeviceInfo(device, param, v, null);
            return v.get(0);
        }
    }

    private static long deviceLong(long device, int param) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer v = stack.mallocLong(1);
            clGetDeviceInfo(device, param, v, null);
            return v.get(0);
        }
    }

    @Override
    public void close() {
        for (long k : kernels) {
            clReleaseKernel(k);
        }
        for (long p : programs) {
            clReleaseProgram(p);
        }
        for (long m : mems) {
            clReleaseMemObject(m);
        }
        clReleaseCommandQueue(queue);
        clReleaseContext(context);
    }

    public String describe() {
        return deviceName + " (" + platformName.trim() + ", " + deviceVersion.trim() + ", driver " + driverVersion.trim() + ", " + computeUnits
            + " compute units, fp64 " + (hasFp64() ? "yes" : "NO") + ")";
    }
}
