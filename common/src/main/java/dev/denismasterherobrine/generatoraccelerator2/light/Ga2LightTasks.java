package dev.denismasterherobrine.generatoraccelerator2.light;

/** A server light engine whose tasks go straight to the light thread ({@link LightTasks}). Implemented by {@code ThreadedLightTasksMixin}. */
public interface Ga2LightTasks {
    /** Whether tasks are queued for the light thread or waiting in its task list. */
    boolean ga2$hasTasks();
}
