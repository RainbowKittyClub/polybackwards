package club.rainbowkitty.polybackwards.entity;

import java.util.List;
import java.util.Map;

import com.mojang.brigadier.context.CommandContext;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.ClientVersions;
import club.rainbowkitty.polybackwards.client.Release;

/**
 * Shows clients that predate 26.3 its new entities as something close, in place of ViaBackwards'
 * look-alikes: a cushion as an interaction of its size under its own id, so clicks and riding
 * reach it, with an item display of its model; a poplar boat as a pale oak boat, the wood poplar
 * signs go out as too, wearing a shell of the poplar hull. Like the block stand-ins, only clients
 * holding the server pack get them. Newer clients get the entities as they are.
 *
 * <p>{@code /polybackwards entities} prints what each release's client is sent for each.
 */
public final class OlderClientEntities {
    // The release that added every entity here.
    private static final Release INTRODUCED = Release.V26_3;
    // Poplar boats by what older clients see instead.
    private static final Map<EntityType<?>, EntityType<?>> BOATS = Map.of(
            EntityTypes.POPLAR_BOAT, EntityTypes.PALE_OAK_BOAT,
            EntityTypes.POPLAR_CHEST_BOAT, EntityTypes.PALE_OAK_CHEST_BOAT);

    private OlderClientEntities() {}

    /** Puts each entity's stand-in on Polymer and their models in the overlay. */
    public static void register() {
        // A constructor without a synced object keeps the vanilla types in registry sync.
        PolymerEntityUtils.registerPolymerEntityConstructor(EntityTypes.CUSHION,
                CushionStandIn::new);
        registerBoat(EntityTypes.POPLAR_BOAT, BoatShell.BOAT);
        registerBoat(EntityTypes.POPLAR_CHEST_BOAT, BoatShell.CHEST_BOAT);
        CushionModels.register();
        BoatShell.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> warmTrackedData());
        CommandRegistrationCallback.EVENT.register((dispatcher, build, environment) ->
                dispatcher.register(Commands.literal(Polybackwards.MOD_ID)
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("entities")
                                .executes(OlderClientEntities::describe))));
        Polybackwards.LOGGER.info("Older-client entities: cushions and {} boat types",
                BOATS.size());
    }

    /** Whether a client is shown stand-ins: it predates these entities and holds the pack. */
    static boolean drawsStandIns(@Nullable PacketContext context) {
        return context != null && drawsStandIns(ClientVersions.of(context),
                PolymerResourcePackUtils.hasMainPack(context));
    }

    /** Same, for a player's connection. */
    static boolean drawsStandIns(ServerPlayer player) {
        return player.connection != null
                && drawsStandIns(player.connection.getPacketContext());
    }

    // The rule itself, by the client's release and whether it holds the server pack.
    private static boolean drawsStandIns(Release release, boolean pack) {
        return pack && release.compareTo(INTRODUCED) < 0;
    }

    // What a client is sent for an entity of the type: the stand-in's type, or the type itself.
    private static EntityType<?> sentType(EntityType<?> type, Release release, boolean pack) {
        if (!drawsStandIns(release, pack)) {
            return type;
        }
        return type == EntityTypes.CUSHION ? EntityTypes.INTERACTION : BOATS.getOrDefault(type,
                type);
    }

    // `/polybackwards entities`, for operators: each type as each release is sent it.
    private static int describe(CommandContext<CommandSourceStack> context) {
        StringBuilder text = new StringBuilder("Older-client entities, with the server pack:");
        for (EntityType<?> type : List.of(EntityTypes.CUSHION, EntityTypes.POPLAR_BOAT,
                EntityTypes.POPLAR_CHEST_BOAT)) {
            text.append('\n').append(BuiltInRegistries.ENTITY_TYPE.getKey(type)).append(':');
            for (Release release : Release.values()) {
                text.append(' ').append(release.name()).append('=').append(
                        BuiltInRegistries.ENTITY_TYPE.getKey(sentType(type, release, true)));
            }
            Release oldest = Release.values()[0];
            text.append("; ").append(oldest.name()).append(" without it=").append(
                    BuiltInRegistries.ENTITY_TYPE.getKey(sentType(type, oldest, false)));
        }
        text.append('\n').append(PoplarBoatElementHolder.summary());
        text.append('\n').append(CushionElementHolder.summary());
        String reply = text.toString();
        context.getSource().sendSuccess(() -> Component.literal(reply), false);
        return 1;
    }

    // Each boat of the type is sent as its stand-in and wears the shell.
    private static <T extends AbstractBoat> void registerBoat(EntityType<T> type,
            BoatShell shell) {
        EntityType<?> standIn = BOATS.get(type);
        PolymerEntityUtils.registerPolymerEntityConstructor(type,
                boat -> new PoplarBoatStandIn(boat, standIn, shell));
    }

    // Polymer caches tracked-data templates unsynchronised, from Netty threads too; filling the
    // shell's on the server thread first keeps a crowd of new boats from racing it.
    private static void warmTrackedData() {
        for (EntityType<?> type : List.of(EntityTypes.DOLPHIN, EntityTypes.ITEM_DISPLAY,
                EntityTypes.PALE_OAK_BOAT, EntityTypes.PALE_OAK_CHEST_BOAT)) {
            PolymerEntityUtils.getDefaultSynchedEntityData(type);
        }
    }
}
