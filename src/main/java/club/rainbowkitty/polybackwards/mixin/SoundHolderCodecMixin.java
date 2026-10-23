package club.rainbowkitty.polybackwards.mixin;

import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.core.Holder;

import club.rainbowkitty.polybackwards.sound.OlderClientSounds;

/** Sends a client the older sound event in place of one its release lacks. */
@Mixin(targets = "net/minecraft/network/codec/ByteBufCodecs$33")
public abstract class SoundHolderCodecMixin {
    // ByteBufCodecs.holder's codec, which every sound event holder goes through on the wire.
    @ModifyVariable(method = "encode(Lnet/minecraft/network/RegistryFriendlyByteBuf;"
            + "Lnet/minecraft/core/Holder;)V", at = @At("HEAD"), argsOnly = true)
    private Holder<?> polybackwards$olderSound(Holder<?> holder) {
        return OlderClientSounds.forClient(holder, PacketContext.get());
    }
}
