package club.rainbowkitty.polybackwards;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

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
    }
}
