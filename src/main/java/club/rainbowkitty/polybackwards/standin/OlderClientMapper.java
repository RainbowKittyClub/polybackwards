package club.rainbowkitty.polybackwards.standin;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.core.api.block.BlockMapper;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.ClientVersions;
import club.rainbowkitty.polybackwards.client.Release;
import club.rainbowkitty.polybackwards.display.CrackSupport;

/**
 * Sends each client older than a claimed block's release that block's stand-in, and has the server
 * mine it where the stand-in would break at another speed than the real block. Only clients with
 * the server pack get stand-ins; one without keeps ViaBackwards' look-alikes.
 */
public final class OlderClientMapper {
    private static final Identifier PHASE = Polybackwards.id("older_client_stand_ins");
    private static final Release[] RELEASES = Release.values();
    // Per client release, built on first use, after the claims.
    private static volatile @Nullable Map<Release, Map<BlockState, BlockState>> byRelease;
    // One mapper per release and base, since Polymer memoises item stand-ins per mapper.
    private static final Mapper[] SHARED = new Mapper[RELEASES.length];

    private OlderClientMapper() {}

    /** Hooks Polymer's mapper choice, server-side mining check and pack status changes. */
    public static void register() {
        // polymer-blocks' listener replaces whatever mapper it is handed, so this one runs after.
        BlockMapper.DEFAULT_MAPPER_EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        BlockMapper.DEFAULT_MAPPER_EVENT.register(PHASE, (context, base) ->
                mapperFor(standInRelease(context), base));
        PolymerBlockUtils.SERVER_SIDE_MINING_CHECK.register(OlderClientMapper::minesOnServer);
        // Before Polymer's own listener, which resends the world to the player.
        PolymerCommonUtils.ON_RESOURCE_PACK_STATUS_CHANGE.addPhaseOrdering(PHASE,
                Event.DEFAULT_PHASE);
        PolymerCommonUtils.ON_RESOURCE_PACK_STATUS_CHANGE.register(PHASE,
                OlderClientMapper::onPackStatus);
    }

    /**
     * The release whose missing blocks a connection is sent stand-ins and displays for: its
     * client's when it holds the server pack, else the server's own, so it is sent none.
     */
    public static Release standInRelease(@Nullable PacketContext context) {
        return context != null && PolymerResourcePackUtils.hasMainPack(context)
                ? ClientVersions.of(context) : ClientVersions.of((PacketContext) null);
    }

    /** Same, for a player's connection. */
    public static Release standInRelease(ServerPlayer player) {
        return standInRelease(player.connection == null ? null
                : player.connection.getPacketContext());
    }

    /** The stand-in a client on {@code release} is sent for {@code real}; null for none. */
    public static @Nullable BlockState standInFor(Release release, BlockState real) {
        return standInsFor(release).get(real);
    }

    /** Every real state a client on {@code release} is sent a stand-in for. */
    public static Map<BlockState, BlockState> standInsFor(Release release) {
        Map<Release, Map<BlockState, BlockState>> built = byRelease;
        if (built == null) {
            built = build();
            // Kept only once claims exist, so an early call never pins an empty split.
            if (!OlderClientStandIns.standIns().isEmpty()
                    || !OlderClientStandIns.displayStandIns().isEmpty()) {
                byRelease = built;
            }
        }
        return built.get(release);
    }

    // A mapper over base for a client with stand-ins to get, else null to keep base.
    private static synchronized @Nullable BlockMapper mapperFor(Release release, BlockMapper base) {
        Map<BlockState, BlockState> standIns = standInsFor(release);
        if (standIns.isEmpty()) {
            return null;
        }
        Mapper shared = SHARED[release.ordinal()];
        if (shared == null || shared.base != base) {
            shared = new Mapper(release, standIns, base);
            SHARED[release.ordinal()] = shared;
        }
        return shared;
    }

    // Server-side mining wherever the client's stand-in breaks faster or slower than the block,
    // and under every display when FactoryTools can crack it: Polymer reports only server mining.
    private static boolean minesOnServer(BlockState state, BlockPos pos, ServerPlayer player) {
        BlockState standIn = standInFor(standInRelease(player), state);
        if (standIn == null) {
            return false;
        }
        if (CrackSupport.available() && OlderClientStandIns.displayStandIn(state) != null) {
            return true;
        }
        Level level = player.level();
        return standIn.getDestroyProgress(player, level, pos)
                != state.getDestroyProgress(player, level, pos);
    }

    // A player who gains or drops the server pack mid-game gets the mapper that now fits; the
    // event can come on the network thread, so the swap waits for the server's.
    private static void onPackStatus(ServerCommonPacketListenerImpl handler, UUID pack,
            boolean oldStatus, boolean newStatus) {
        if (oldStatus != newStatus && handler instanceof ServerGamePacketListenerImpl game
                && pack.equals(PolymerResourcePackUtils.getMainUuid())) {
            ServerPlayer player = game.getPlayer();
            player.level().getServer().execute(() -> BlockMapper.resetMapper(player));
        }
    }

    // Splits the claims and the display fallback's stand-ins by the clients that lack each block.
    private static Map<Release, Map<BlockState, BlockState>> build() {
        Map<Release, Map<BlockState, BlockState>> built = new EnumMap<>(Release.class);
        for (Release client : RELEASES) {
            Map<BlockState, BlockState> standIns = new IdentityHashMap<>();
            BiConsumer<BlockState, BlockState> add = (real, standIn) -> {
                Release added = OlderClientStandIns.introducedIn(real.getBlock());
                if (added != null && client.compareTo(added) < 0) {
                    standIns.put(real, standIn);
                }
            };
            OlderClientStandIns.standIns().forEach(add);
            OlderClientStandIns.displayStandIns().forEach(add);
            built.put(client, standIns.isEmpty() ? Map.of() : standIns);
        }
        return built;
    }

    // Stand-ins first; base, which remaps pooled states for everyone, never sees them. A class,
    // not a record: Polymer keys item memos by mapper, and a record would hash the whole map.
    private static final class Mapper implements BlockMapper {
        private final Release release;
        private final Map<BlockState, BlockState> standIns;
        private final BlockMapper base;

        Mapper(Release release, Map<BlockState, BlockState> standIns, BlockMapper base) {
            this.release = release;
            this.standIns = standIns;
            this.base = base;
        }

        @Override
        public BlockState toClientSideState(BlockState state, @Nullable PacketContext context) {
            BlockState standIn = this.standIns.get(state);
            return standIn != null ? standIn : this.base.toClientSideState(state, context);
        }

        @Override
        public String getMapperName() {
            return "polybackwards:older_client_stand_ins [" + this.release + " | "
                    + this.base.getMapperName() + "]";
        }
    }
}
