package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

/** A block state's GPU id ({@link StateIds}), cached on the state for the ids it was last asked by. */
public interface Ga2StateId {
    record Cache(StateIds owner, int id) {}

    Cache ga2$stateId();

    void ga2$setStateId(Cache c);
}
