package club.rainbowkitty.polybackwards.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import club.rainbowkitty.polybackwards.item.ClientPlacement;

/** Plays a use's sounds to its player too when that player's client predicted none of it. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    // A sound at a position, as a block placement plays it.
    @ModifyVariable(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDD"
            + "Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private @Nullable Entity polybackwards$liftAtPosition(@Nullable Entity except) {
        return ClientPlacement.exclusion(except);
    }

    // A sound following an entity.
    @ModifyVariable(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;"
            + "Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private @Nullable Entity polybackwards$liftOnEntity(@Nullable Entity except) {
        return ClientPlacement.exclusion(except);
    }
}
