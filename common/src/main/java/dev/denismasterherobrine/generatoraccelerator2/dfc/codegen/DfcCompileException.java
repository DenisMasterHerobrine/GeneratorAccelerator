package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

/** Raised when code generation or class definition fails. Never swallowed silently. */
public final class DfcCompileException extends RuntimeException {
    /** The generated code was too large (for the JVM, or for the JIT). A more split-up layout may fit. */
    public final boolean sizeLimit;

    public DfcCompileException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public DfcCompileException(String message) {
        super(message);
        this.sizeLimit = false;
    }

    public DfcCompileException(String message, Throwable cause, boolean sizeLimit) {
        super(message, cause);
        this.sizeLimit = sizeLimit;
    }
}
