package dev.denismasterherobrine.generatoraccelerator2.dfc.lift;

/** The lifter cannot express a construct. The message says which one and where. */
public final class LiftException extends RuntimeException {
    public LiftException(String message) {
        super(message);
    }
}
