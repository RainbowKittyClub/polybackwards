package club.rainbowkitty.polybackwards.mixin;

import eu.pb4.factorytools.api.util.VirtualDestroyStage;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.polybackwards.display.FallbackCracks;

/**
 * Gives a display-fallback block mined by an older client with the pack crack models shaped like
 * its displays, which FactoryTools finds on Polymer blocks only, and keeps those cracks from the
 * clients that see the block itself.
 */
@Mixin(VirtualDestroyStage.class)
public abstract class VirtualDestroyStageMixin extends ElementHolder {
    // A vanilla block has no marker of its own, so this one goes first.
    @Inject(method = "getMarker", at = @At("HEAD"), cancellable = true)
    private static void polybackwards$fallbackMarker(BlockState state, BlockPos pos,
            ServerPlayer player, CallbackInfoReturnable<VirtualDestroyStage.Marker> cir) {
        VirtualDestroyStage.Marker marker = FallbackCracks.Api.marker(state, pos, player);
        if (marker != null) {
            cir.setReturnValue(marker);
        }
    }

    // An override keeps its name; the miner's cracks reach only clients shown the displays.
    @Override
    public boolean startWatching(ServerGamePacketListenerImpl player) {
        return !FallbackCracks.hidesFrom(this, player) && super.startWatching(player);
    }
}
