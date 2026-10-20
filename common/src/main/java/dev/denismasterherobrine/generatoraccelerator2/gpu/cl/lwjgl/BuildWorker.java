package dev.denismasterherobrine.generatoraccelerator2.gpu.cl.lwjgl;

import static org.lwjgl.opencl.CL10.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Builds one program in a process of its own and writes the device binary ({@code gpu.runtime.ProgramCache} starts it). The driver
 * builds one program at a time per process. Parts built in separate processes build at the same time. Only the JDK and LWJGL are on
 * this process's class path, so this class uses no other GA2 class.
 *
 * <p>Arguments: the platform name, the device name, the device's index among the platform's GPUs of that name, the options file,
 * the source file, and the binary to write. Exits with 0 once the binary is written, 2 when the build fails (its log on stderr).
 */
public final class BuildWorker {
    private BuildWorker() {}

    public static void main(String[] args) throws Exception {
        String platformName = args[0], deviceName = args[1];
        int index = Integer.parseInt(args[2]);
        String options = Files.readString(Path.of(args[3]), StandardCharsets.UTF_8);
        String source = Files.readString(Path.of(args[4]), StandardCharsets.UTF_8);
        Path out = Path.of(args[5]);
        // The driver builds on this thread. Above normal, as the game's prepare thread is (gpu.Gpu). With every processor busy, builds
        // at normal priority took about 1.5 times as long.
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY + 3);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] found = find(stack, platformName, deviceName, index);
            if (found == null) {
                System.err.println("no GPU '" + deviceName + "' #" + index + " on platform '" + platformName + "'");
                System.exit(3);
            }
            IntBuffer err = stack.mallocInt(1);
            PointerBuffer props = stack.mallocPointer(3).put(CL_CONTEXT_PLATFORM).put(found[0]).put(0).flip();
            long context = clCreateContext(props, found[1], null, MemoryUtil.NULL, err);
            check(err.get(0), "clCreateContext");
            long program = clCreateProgramWithSource(context, source, err);
            check(err.get(0), "clCreateProgramWithSource");
            int rc = clBuildProgram(program, found[1], options, null, MemoryUtil.NULL);
            if (rc != CL_SUCCESS) {
                System.err.println("clBuildProgram failed (" + rc + "):\n" + buildLog(program, found[1]));
                System.exit(2);
            }
            byte[] bin = binary(stack, program);
            Path tmp = Files.createTempFile(out.toAbsolutePath().getParent(), out.getFileName().toString(), ".tmp");
            Files.write(tmp, bin);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            clReleaseProgram(program);
            clReleaseContext(context);
        }
        System.exit(0);
    }

    /** The platform and device handles, or null. Devices are counted in the order the game's binding lists them. */
    private static long[] find(MemoryStack stack, String platformName, String deviceName, int index) {
        IntBuffer n = stack.mallocInt(1);
        if (clGetPlatformIDs(null, n) != CL_SUCCESS || n.get(0) == 0) {
            return null;
        }
        PointerBuffer platforms = stack.mallocPointer(n.get(0));
        check(clGetPlatformIDs(platforms, (IntBuffer) null), "clGetPlatformIDs");
        int seen = 0;
        for (int p = 0; p < platforms.capacity(); p++) {
            long platform = platforms.get(p);
            if (!platformName.equals(info(platform, CL_PLATFORM_NAME, true))) {
                continue;
            }
            IntBuffer dn = stack.mallocInt(1);
            if (clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, dn) != CL_SUCCESS || dn.get(0) == 0) {
                continue;
            }
            PointerBuffer devices = stack.mallocPointer(dn.get(0));
            if (clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices, (IntBuffer) null) != CL_SUCCESS) {
                continue;
            }
            for (int d = 0; d < devices.capacity(); d++) {
                if (deviceName.equals(info(devices.get(d), CL_DEVICE_NAME, false)) && seen++ == index) {
                    return new long[] { platform, devices.get(d) };
                }
            }
        }
        return null;
    }

    private static String info(long handle, int param, boolean platform) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            int rc = platform ? clGetPlatformInfo(handle, param, (ByteBuffer) null, size) : clGetDeviceInfo(handle, param, (ByteBuffer) null, size);
            if (rc != CL_SUCCESS) {
                return "";
            }
            ByteBuffer buf = stack.malloc((int) size.get(0));
            if (platform) {
                clGetPlatformInfo(handle, param, buf, null);
            } else {
                clGetDeviceInfo(handle, param, buf, null);
            }
            return MemoryUtil.memUTF8(buf, Math.max(0, buf.capacity() - 1)).trim();
        }
    }

    private static byte[] binary(MemoryStack stack, long program) {
        PointerBuffer sizes = stack.mallocPointer(1);
        check(clGetProgramInfo(program, CL_PROGRAM_BINARY_SIZES, sizes, null), "clGetProgramInfo(sizes)");
        int n = (int) sizes.get(0);
        ByteBuffer buf = MemoryUtil.memAlloc(Math.max(1, n));
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

    private static void check(int rc, String what) {
        if (rc != CL_SUCCESS) {
            throw new IllegalStateException(what + " failed: OpenCL error " + rc);
        }
    }
}
