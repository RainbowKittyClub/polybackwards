package club.rainbowkitty.polybackwards.client;

import java.util.Locale;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import club.rainbowkitty.polybackwards.Polybackwards;

/**
 * The release each player's client runs, so an older client can be shown what its release lacks.
 *
 * <p>Via on the proxy hides the client's protocol from this server, so rkcore-proxy reports it as
 * {@code polybackwards:client_version} while the player configures. Without that report, Via on
 * this server answers when installed (dev servers), else the client counts as running this
 * server's release. The answer is kept on the connection, so it holds from configuration into
 * play. {@code /polybackwards client <player>} prints what is kept.
 */
public final class ClientVersions {
    // The proxy's report, shared with rkcore-proxy's ClientVersionReport.
    private static final CustomPacketPayload.Type<Report> TYPE = new CustomPacketPayload.Type<>(
            Polybackwards.id("client_version"));
    private static final PacketContext.Key<Seen> SEEN =
            PacketContext.key(Polybackwards.id("client_version"));
    // Set on snapshot and release-candidate protocols, which name no release.
    private static final int SNAPSHOT_BIT = 1 << 30;
    private static final Release[] RELEASES = Release.values();
    private static final Release SERVER = serverRelease();

    private ClientVersions() {}

    /** Takes the proxy's report, falls back on Via on this server, and adds the query command. */
    public static void register() {
        PayloadTypeRegistry.serverboundConfiguration().register(TYPE, Report.CODEC);
        ServerConfigurationNetworking.registerGlobalReceiver(TYPE, (report, context) ->
                record(context.packetContext(), report.protocol(), Source.PROXY));
        if (FabricLoader.getInstance().isModLoaded("viaversion")) {
            ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register((handler, server) ->
                    askVia(handler.getPacketContext(), handler.getOwner()));
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                registerCommand(dispatcher));
    }

    /** The release the player's client runs; the server's own when unknown. */
    public static Release of(ServerPlayer player) {
        return of(contextOf(player));
    }

    /**
     * Same, from a connection's packet context, which answers from configuration on, before the
     * connection has a player; a null context is the server's own.
     */
    public static Release of(@Nullable PacketContext context) {
        Seen seen = context == null ? null : context.get(SEEN);
        return seen == null || seen.release() == null ? SERVER : seen.release();
    }

    /** Whether the player's client lacks the blocks first shipped in {@code release}. */
    public static boolean predates(ServerPlayer player, Release release) {
        return of(player).compareTo(release) < 0;
    }

    // A player's connection context; null for one without a connection.
    private static @Nullable PacketContext contextOf(ServerPlayer player) {
        return player.connection == null ? null : player.connection.getPacketContext();
    }

    // The newest release at or below the protocol, else the oldest; null for a snapshot or unknown.
    private static @Nullable Release releaseOf(int protocol) {
        if (protocol <= 0 || (protocol & SNAPSHOT_BIT) != 0) {
            return null;
        }
        Release found = RELEASES[0];
        for (Release release : RELEASES) {
            if (protocol >= protocol(release)) {
                found = release;
            }
        }
        return found;
    }

    // Each release's protocol, from its client jar's version.json; Via's ProtocolVersion agrees.
    private static int protocol(Release release) {
        return switch (release) {
            case V26_1 -> 775;
            case V26_2 -> 776;
            case V26_3 -> 777;
        };
    }

    // This server's release, the newest named when its own protocol names none.
    private static Release serverRelease() {
        Release release = releaseOf(SharedConstants.getProtocolVersion());
        return release != null ? release : RELEASES[RELEASES.length - 1];
    }

    // Keeps a reported protocol on the connection; a later report replaces an earlier one.
    private static void record(PacketContext context, int protocol, Source source) {
        context.set(SEEN, new Seen(protocol, source, releaseOf(protocol)));
        Polybackwards.LOGGER.debug("Client protocol {} from {}", protocol, source);
    }

    // Asks Via on this server, unless the proxy reported first (a reconfiguration keeps it).
    private static void askVia(PacketContext context, GameProfile profile) {
        if (context.get(SEEN) != null) {
            return;
        }
        // Polymer asks Via by profile id; Via answers -1 for a connection it does not track.
        int protocol = PolymerCommonUtils.getPlayerGameProtocol(profile);
        if (protocol > 0) {
            record(context, protocol, Source.VIA);
        }
    }

    // `/polybackwards client <player>`, for operators.
    private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Polybackwards.MOD_ID)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("client")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(command -> describe(command.getSource(),
                                        EntityArgument.getPlayer(command, "player"))))));
    }

    // Prints the release a player counts as running, and what it was told from.
    private static int describe(CommandSourceStack source, ServerPlayer player) {
        PacketContext context = contextOf(player);
        Seen seen = context == null ? null : context.get(SEEN);
        String name = player.getGameProfile().name();
        String from = seen == null
                ? "nothing reported, the server's own"
                : "protocol " + seen.protocol() + " from "
                        + seen.source().name().toLowerCase(Locale.ROOT);
        String text = name + ": " + of(context).name() + " (" + from + ")";
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    // Who reported a client's protocol.
    private enum Source {
        PROXY,
        VIA
    }

    // A connection's reported protocol, who reported it, and its release (null if it names none).
    private record Seen(int protocol, Source source, @Nullable Release release) {
    }

    // The proxy's report: the client's protocol, as its handshake gave it, in one varint.
    private record Report(int protocol) implements CustomPacketPayload {
        private static final StreamCodec<ByteBuf, Report> CODEC =
                ByteBufCodecs.VAR_INT.map(Report::new, Report::protocol);

        @Override
        public Type<Report> type() {
            return TYPE;
        }
    }
}
