package club.rainbowkitty.polybackwards.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import club.rainbowkitty.polybackwards.display.FallbackHitParticles;
import club.rainbowkitty.polybackwards.item.ClientPlacement;

/**
 * Shows a player the uses of an item their client holds a firework star for, and sends hit
 * particles of a display-fallback block being mined to older clients whose stand-in shows none.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow
    protected ServerLevel level;

    @Shadow
    private BlockPos destroyPos;

    @Shadow
    private Direction destroyDirection;

    // Beside 26.3's per-tick hit event, whose particles each client makes from its own state.
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/"
            + "ServerLevel;levelEvent(Lnet/minecraft/world/entity/Entity;"
            + "ILnet/minecraft/core/BlockPos;I)V"))
    private void polybackwards$sendHitParticles(CallbackInfo ci) {
        FallbackHitParticles.send(this.level, this.destroyPos, this.destroyDirection);
    }
    // The item's own use on a block, after the block passed on the click.
    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/"
            + "world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)"
            + "Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult polybackwards$useOn(ItemStack stack, UseOnContext context,
            Operation<InteractionResult> original, @Local(argsOnly = true) ServerPlayer player) {
        return ClientPlacement.use(player, stack, () -> original.call(stack, context));
    }

    // The item's use in the air.
    @WrapOperation(method = "useItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/"
            + "world/item/ItemStack;use(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)"
            + "Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult polybackwards$use(ItemStack stack, Level level, Player user,
            InteractionHand hand, Operation<InteractionResult> original,
            @Local(argsOnly = true) ServerPlayer player) {
        return ClientPlacement.use(player, stack, () -> original.call(stack, level, user, hand));
    }
}
