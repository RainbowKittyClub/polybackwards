package club.rainbowkitty.polybackwards.mixin;

import eu.pb4.polymer.resourcepack.api.PackResource;
import eu.pb4.polymer.resourcepack.impl.generation.DefaultRPBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Runs a pack builder's resource converters, private to it. */
@Mixin(DefaultRPBuilder.class)
public interface DefaultRPBuilderAccessor {
    /** The file as the built pack serves it, or null when a converter drops it. */
    @Invoker("convertResource")
    PackResource polybackwards$convertResource(String path, PackResource resource);
}
