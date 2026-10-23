package club.rainbowkitty.polybackwards.mixin;

import eu.pb4.polymer.blocks.api.BlockResourceCreator;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import club.rainbowkitty.polybackwards.standin.OlderClientStandIns;

/** Claims older-client stand-ins as Polymer's shared creator is built, before any mod's request. */
@Mixin(PolymerBlockResourceUtils.class)
public abstract class PolymerBlockResourceUtilsMixin {
    @Shadow
    @Final
    private static BlockResourceCreator CREATOR;

    // Mods claim inside their own registration, long before this mod's entrypoint runs.
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void polybackwards$claimOlderClientStandIns(CallbackInfo ci) {
        OlderClientStandIns.claim(CREATOR);
    }
}
