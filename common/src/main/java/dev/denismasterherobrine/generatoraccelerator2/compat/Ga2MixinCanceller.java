package dev.denismasterherobrine.generatoraccelerator2.compat;

import com.bawnorton.mixinsquared.api.MixinCanceller;
import java.util.List;

/** MixinSquared entry point (registered as a service): cancels the mixins listed in {@link Takeover}. */
public final class Ga2MixinCanceller implements MixinCanceller {
    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        return Takeover.shouldCancel(targetClassNames, mixinClassName);
    }
}
