package club.rainbowkitty.polybackwards;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import club.rainbowkitty.polybackwards.client.ClientVersions;
import club.rainbowkitty.polybackwards.display.DisplayFallbackOverlay;
import club.rainbowkitty.polybackwards.display.FallbackCracks;
import club.rainbowkitty.polybackwards.pack.Pre97Overlay;
import club.rainbowkitty.polybackwards.standin.OlderClientMapper;
import club.rainbowkitty.polybackwards.standin.OlderClientStandIns;
import club.rainbowkitty.polybackwards.standin.StandInCommand;

/** Mod entrypoint. **/
public class Polybackwards implements ModInitializer {
    public static final String MOD_ID = "polybackwards";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** {@code polybackwards:<path>} identifier. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        Pre97Overlay.register();
        ClientVersions.register();
        // Claims happen as Polymer's creator is built; this builds it when no mod has.
        OlderClientStandIns.init();
        OlderClientMapper.register();
        DisplayFallbackOverlay.register();
        // After the overlay, whose copies its crack models are made from at each pack build.
        FallbackCracks.register();
        StandInCommand.register();
    }
}
