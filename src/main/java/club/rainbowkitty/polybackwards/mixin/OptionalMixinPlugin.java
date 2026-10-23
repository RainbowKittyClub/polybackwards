package club.rainbowkitty.polybackwards.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

import club.rainbowkitty.polybackwards.display.CrackSupport;

/**
 * Skips the mixin on FactoryTools unless FactoryTools is loaded with the crack API, so a server
 * without it neither fails on the missing class nor loads it.
 */
public final class OptionalMixinPlugin implements IMixinConfigPlugin {
    private static final String CRACKS_MIXIN =
            "club.rainbowkitty.polybackwards.mixin.VirtualDestroyStageMixin";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !CRACKS_MIXIN.equals(mixinClassName) || CrackSupport.available();
    }
}
