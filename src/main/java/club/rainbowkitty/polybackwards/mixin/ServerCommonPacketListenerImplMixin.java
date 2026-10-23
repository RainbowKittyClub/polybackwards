package club.rainbowkitty.polybackwards.mixin;

import io.netty.channel.ChannelFutureListener;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import club.rainbowkitty.polybackwards.sound.BreakSounds;

/** Replaces a block break effect for each client it would sound wrong to. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    // Every packet to one player passes here, so a broadcast event is decided per player.
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;"
            + "Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void polybackwards$replaceBreakEffect(Packet<?> packet,
            @Nullable ChannelFutureListener listener, CallbackInfo ci) {
        if (packet instanceof ClientboundLevelEventPacket event
                && (Object) this instanceof ServerGamePacketListenerImpl game
                && BreakSounds.replace(game.player, event)) {
            ci.cancel();
        }
    }
}
