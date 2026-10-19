package dev.denismasterherobrine.generatoraccelerator2.gpu.cl;

/**
 * One OpenCL device as the driver describes it.
 *
 * @param type OpenCL's device type bits (GPU = 4, CPU = 2, accelerator = 8)
 * @param doubleFpConfig {@code CL_DEVICE_DOUBLE_FP_CONFIG} (0 without fp64)
 * @param hostUnifiedMemory {@code CL_DEVICE_HOST_UNIFIED_MEMORY}: an integrated GPU sharing the host's memory
 */
public record ClDeviceInfo(long platform, long device, String platformName, String name, String vendor, String version, String driverVersion,
                           String extensions, long type, int computeUnits, long globalMemory, long maxAlloc, long doubleFpConfig, boolean hostUnifiedMemory) {
    public static final long TYPE_CPU = 1L << 1, TYPE_GPU = 1L << 2;

    public boolean isGpu() {
        return (type & TYPE_GPU) != 0;
    }

    public boolean hasFp64() {
        return doubleFpConfig != 0 && extensions.contains("cl_khr_fp64");
    }

    public String describe() {
        return name.trim() + " (" + platformName.trim() + ", " + version.trim() + ", driver " + driverVersion.trim() + ", " + computeUnits + " compute units, "
            + (globalMemory >> 20) + " MB, fp64 " + (hasFp64() ? "yes" : "no") + (hostUnifiedMemory ? ", integrated" : "") + ")";
    }
}
