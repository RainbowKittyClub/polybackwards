package club.rainbowkitty.polybackwards;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import club.rainbowkitty.polybackwards.client.ClientVersions;
import club.rainbowkitty.polybackwards.pack.Pre97Overlay;

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
    }
}
