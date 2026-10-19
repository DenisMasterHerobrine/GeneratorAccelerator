package dev.denismasterherobrine.generatoraccelerator2.gpu.cl;

/** A failed OpenCL call. The message names the call, the error code and, for builds, the build log. */
public final class ClException extends RuntimeException {
    public final int code;

    public ClException(String message, int code) {
        super(message);
        this.code = code;
    }
}
