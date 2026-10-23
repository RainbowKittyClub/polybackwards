package club.rainbowkitty.polybackwards.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.context.CommandContext;
import eu.pb4.factorytools.api.util.LazyItemStack;
import eu.pb4.factorytools.api.util.MiningStageModel;
import eu.pb4.factorytools.api.util.VirtualDestroyStage;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.Release;
import club.rainbowkitty.polybackwards.standin.OlderClientMapper;
import club.rainbowkitty.polybackwards.standin.OlderClientStandIns;

/**
 * Mining cracks over display-fallback blocks for the older clients that see their displays,
 * through FactoryTools' crack models: a client draws cracks on the stand-in it holds, which the
 * display hides or which is invisible. Each display gets the cracks of its own grown copy, placed
 * as the display is, as ppb does for its display-drawn blocks. Nothing here touches FactoryTools'
 * classes unless {@link CrackSupport} finds its crack API.
 *
 * <p>{@code /polybackwards cracks} prints whether the API is there and how many copies have cracks.
 */
public final class FallbackCracks {
    private FallbackCracks() {}

    /** Requests crack models at each pack build after the copies are written, and the probe. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, build, environment) ->
                dispatcher.register(Commands.literal(Polybackwards.MOD_ID)
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("cracks").executes(FallbackCracks::describe))));
        if (CrackSupport.available()) {
            PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT.register(
                    builder -> Api.request());
        }
    }

    /**
     * Whether a player must not watch a crack holder: it sits on a display-fallback block that
     * player is not shown displays for, such as a 26.3 or pack-less client watching an older miner.
     */
    public static boolean hidesFrom(ElementHolder stage, ServerGamePacketListenerImpl watcher) {
        HolderAttachment attachment = stage.getAttachment();
        if (attachment == null) {
            return false;
        }
        BlockState state = attachment.getWorld().getBlockState(
                BlockPos.containing(attachment.getPos()));
        Release introduced = OlderClientStandIns.introducedIn(state.getBlock());
        return introduced != null && OlderClientStandIns.displayStandIn(state) != null
                && OlderClientMapper.standInRelease(watcher.getPlayer()).compareTo(introduced) >= 0;
    }

    // The holder drawing a state for a player, when that player is shown its displays.
    private static @Nullable FallbackBlockElementHolder holderFor(BlockState state, BlockPos pos,
            ServerPlayer player) {
        Release introduced = OlderClientStandIns.introducedIn(state.getBlock());
        if (introduced == null || OlderClientStandIns.displayStandIn(state) == null
                || OlderClientMapper.standInRelease(player).compareTo(introduced) >= 0) {
            return null;
        }
        BlockBoundAttachment attachment = BlockBoundAttachment.get(player.level(), pos);
        return attachment != null
                && attachment.holder() instanceof FallbackBlockElementHolder holder ? holder : null;
    }

    // `/polybackwards cracks`, for operators: the API and the copies with cracks.
    private static int describe(CommandContext<CommandSourceStack> context) {
        boolean available = CrackSupport.available();
        int copies = available ? Api.count() : 0;
        String reply = "Fallback cracks: FactoryTools crack API "
                + (available ? "loaded, " + copies + " of "
                        + DisplayFallbackModels.geometry().size() + " display copies with cracks"
                        : "missing, so clients keep their own cracks");
        context.getSource().sendSuccess(() -> Component.literal(reply), false);
        return copies;
    }

    /**
     * The FactoryTools side, loaded only when its crack API is there: the marker FactoryTools
     * asks for a mined block, and the crack models of every display copy.
     */
    public static final class Api implements VirtualDestroyStage.Marker {
        private static final Api INSTANCE = new Api();
        // Each display item model with its copy's crack models, from the last pack build.
        private static volatile Map<Identifier, List<LazyItemStack>> stages = Map.of();

        private Api() {}

        /** The marker for a block a player mines, or null to leave it to FactoryTools. */
        public static VirtualDestroyStage.@Nullable Marker marker(BlockState state, BlockPos pos,
                ServerPlayer player) {
            return holderFor(state, pos, player) != null ? INSTANCE : null;
        }

        // Requests the crack models of every copy the pack build just wrote, one set per copy
        // holding elements, which a family's models share.
        static void request() {
            Map<Identifier, List<LazyItemStack>> bySource = new HashMap<>();
            Map<Identifier, List<LazyItemStack>> byItemModel = new HashMap<>();
            DisplayFallbackModels.geometry().forEach((itemModel, source) -> {
                try {
                    byItemModel.put(itemModel,
                            bySource.computeIfAbsent(source, MiningStageModel::stagesOf));
                } catch (RuntimeException e) {
                    Polybackwards.LOGGER.warn("Fallback cracks: none for {}", source, e);
                }
            });
            stages = Map.copyOf(byItemModel);
            Polybackwards.LOGGER.info("Fallback cracks: {} crack model sets for {} display copies",
                    bySource.size(), byItemModel.size());
        }

        // Display copies with crack models.
        static int count() {
            return stages.size();
        }

        // One crack model per display, placed as that display; null keeps FactoryTools' cube.
        @Override
        public @Nullable List<MiningStageModel> getMiningStageModels(BlockState state,
                BlockPos pos, ServerPlayer player) {
            FallbackBlockElementHolder holder = holderFor(state, pos, player);
            if (holder == null || holder.getAttachment() == null) {
                return null;
            }
            Vec3 holderOffset = holder.getAttachment().getPos().subtract(Vec3.atCenterOf(pos));
            Map<Identifier, List<LazyItemStack>> known = stages;
            List<MiningStageModel> models = new ArrayList<>();
            for (ItemDisplayElement display : holder.displays()) {
                List<LazyItemStack> crack =
                        known.get(display.getItem().get(DataComponents.ITEM_MODEL));
                if (crack == null) {
                    return null;
                }
                models.add(new MiningStageModel(crack, transformation(display, holderOffset)));
            }
            return models.isEmpty() ? null : models;
        }

        // A display's whole placement from the block's centre, as the client draws it.
        private static Matrix4f transformation(ItemDisplayElement display, Vec3 holderOffset) {
            Vec3 offset = holderOffset.add(display.getOffset());
            return new Matrix4f()
                    .translate((float) offset.x, (float) offset.y, (float) offset.z)
                    .rotateYXZ(-display.getYaw() * Mth.DEG_TO_RAD,
                            display.getPitch() * Mth.DEG_TO_RAD, 0)
                    .translate(display.getTranslation())
                    .rotate(display.getLeftRotation())
                    .scale(display.getScale())
                    .rotate(display.getRightRotation());
        }
    }
}
