package club.rainbowkitty.polybackwards.display;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether FactoryTools is loaded with the crack model API of the RKC fork, which draws mining
 * cracks shaped like display-fallback blocks. Reads no class of it, so the mixin plugin can ask
 * before any mixin applies.
 */
public final class CrackSupport {
    private static final String MOD_ID = "factorytools";
    private static final String API = "eu/pb4/factorytools/api/util/MiningStageModel.class";
    private static final boolean AVAILABLE = FabricLoader.getInstance().isModLoaded(MOD_ID)
            && CrackSupport.class.getClassLoader().getResource(API) != null;

    private CrackSupport() {}

    /** Whether FactoryTools and its crack model API are both there. */
    public static boolean available() {
        return AVAILABLE;
    }
}
