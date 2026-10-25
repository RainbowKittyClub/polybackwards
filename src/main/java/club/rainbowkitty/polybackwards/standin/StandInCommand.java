package club.rainbowkitty.polybackwards.standin;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.core.api.block.BlockMapper;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.ClientVersions;
import club.rainbowkitty.polybackwards.client.Release;
import club.rainbowkitty.polybackwards.display.FallbackBlockElementHolder;

/**
 * {@code /polybackwards standins}: what the older-client stand-in claims took, for checking them.
 * {@code block <id>} lists one block's claims, {@code slabs} counts each namespace's Polymer slabs
 * on a pooled bottom stand-in against those on a display fallback, {@code player <player>} what
 * that player's client is sent, {@code state <state>} what each release's client gets for a state,
 * {@code displays [at <pos>]} what the display fallback's holders draw, loaded or at one block.
 */
public final class StandInCommand {
    private StandInCommand() {}

    /** Registers the command; Brigadier merges it with any other {@code /polybackwards} branch. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, build, environment) ->
                dispatcher.register(Commands.literal(Polybackwards.MOD_ID)
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("standins")
                                .executes(StandInCommand::summary)
                                .then(Commands.literal("block")
                                        .then(Commands.argument("block", IdentifierArgument.id())
                                                .suggests((ctx, builder) ->
                                                        SharedSuggestionProvider.suggestResource(
                                                                tableBlocks(), builder))
                                                .executes(StandInCommand::block)))
                                .then(Commands.literal("slabs")
                                        .executes(StandInCommand::slabs))
                                .then(Commands.literal("player")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(StandInCommand::player)))
                                .then(Commands.literal("state")
                                        .then(Commands.argument("state",
                                                        BlockStateArgument.block(build))
                                                .executes(StandInCommand::state)))
                                .then(Commands.literal("displays")
                                        .executes(StandInCommand::displays)
                                        .then(Commands.literal("at")
                                                .then(Commands.argument("pos",
                                                                BlockPosArgument.blockPos())
                                                        .executes(StandInCommand::displaysAt)))))));
    }

    // Claim counts by pool, stable slots and the display fallback.
    private static int summary(CommandContext<CommandSourceStack> context) {
        Map<BlockModelType, Integer> byPool = new EnumMap<>(BlockModelType.class);
        int stable = 0;
        for (OlderClientStandIns.Claim claim : OlderClientStandIns.claims()) {
            if (claim.stableSlot() >= 0) {
                stable++;
            } else {
                byPool.merge(claim.pool(), 1, Integer::sum);
            }
        }
        String slots = OlderClientStandIns.stableSlotSummary();
        send(context, OlderClientStandIns.claims().size() + " claims; stable slots: "
                + (slots == null ? "none on this Polymer" : slots));
        send(context, "Pooled: " + byPool + ", stable " + stable);
        send(context, "Display fallback: " + OlderClientStandIns.displayFallbackBlocks().size()
                + " blocks, " + OlderClientStandIns.displayStandIns().size() + " states");
        return OlderClientStandIns.claims().size();
    }

    // One block's claims: real state, the stand-in older clients get, its pool.
    private static int block(CommandContext<CommandSourceStack> context) {
        Identifier id = IdentifierArgument.getId(context, "block");
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null || OlderClientStandIns.introducedIn(block) == null) {
            context.getSource().sendFailure(Component.literal(id + " is not newer than 26.1"));
            return 0;
        }
        int count = 0;
        for (OlderClientStandIns.Claim claim : OlderClientStandIns.claims()) {
            if (claim.real().getBlock() == block) {
                count++;
                send(context, BlockStateParser.serialize(claim.real()) + " -> "
                        + BlockStateParser.serialize(claim.standIn()) + " " + claim.pool()
                        + (claim.stableSlot() < 0 ? "" : " slot " + claim.stableSlot()));
            }
        }
        // Double slabs and copies claim nothing; they go out as a full block or the copy.
        boolean copy = OlderClientStandIns.copyOf(block) != null;
        for (BlockState real : block.getStateDefinition().getPossibleStates()) {
            BlockState standIn = copy ? OlderClientStandIns.displayStandIn(real)
                    : OlderClientStandIns.standIn(real);
            boolean doubleSlab = real.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && real.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
            if (standIn != null && (doubleSlab || copy)) {
                send(context, BlockStateParser.serialize(real) + " -> "
                        + BlockStateParser.serialize(standIn) + (copy ? " copy" : " full block"));
            }
        }
        send(context, id + ": " + OlderClientStandIns.introducedIn(block) + ", " + count
                + " claims" + (OlderClientStandIns.displayFallbackBlocks().contains(block)
                ? ", display fallback for the rest" : ""));
        return count;
    }

    // Per namespace, Polymer slabs whose bottom state goes out as a pooled stand-in, or not.
    private static int slabs(CommandContext<CommandSourceStack> context) {
        Map<String, int[]> counts = new TreeMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof SlabBlock)) {
                continue;
            }
            BlockState bottom = block.defaultBlockState()
                    .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
                    .setValue(BlockStateProperties.WATERLOGGED, false);
            if (!PolymerBlockUtils.IS_POLYMER_BLOCK_STATE_PREDICATE.test(bottom)) {
                continue;
            }
            // A pooled bottom slab goes out as a sculk sensor; a display one as an empty slab.
            boolean pooled = !(PolymerBlockUtils.getPolymerBlockState(bottom, null).getBlock()
                    instanceof SlabBlock);
            int[] count = counts.computeIfAbsent(
                    BuiltInRegistries.BLOCK.getKey(block).getNamespace(), namespace -> new int[2]);
            count[pooled ? 0 : 1]++;
        }
        counts.forEach((namespace, count) -> send(context, namespace + ": " + count[0]
                + " bottom slabs pooled, " + count[1] + " on a display fallback"));
        return counts.size();
    }

    // A player's release, how many stand-ins it is sent and the mapper Polymer holds for it.
    private static int player(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        int count = OlderClientMapper.standInsFor(OlderClientMapper.standInRelease(player)).size();
        boolean pack = PolymerResourcePackUtils.hasMainPack(player.connection.getPacketContext());
        send(context, player.getGameProfile().name() + ": " + ClientVersions.of(player) + ", "
                + count + " stand-ins, mapper " + BlockMapper.getFrom(player).getMapperName()
                + (pack ? "" : "; no server pack"));
        return count;
    }

    // One state's stand-in for each client release, on one line.
    private static int state(CommandContext<CommandSourceStack> context) {
        BlockState real = BlockStateArgument.getBlock(context, "state").getState();
        StringBuilder line = new StringBuilder(BlockStateParser.serialize(real));
        int count = 0;
        for (Release release : Release.values()) {
            BlockState standIn = OlderClientMapper.standInFor(release, real);
            line.append(' ').append(release).append('=')
                    .append(standIn == null ? "none" : BlockStateParser.serialize(standIn));
            if (standIn != null && OlderClientStandIns.displayStandIn(real) != null) {
                line.append(" (display)");
            }
            count += standIn == null ? 0 : 1;
        }
        send(context, line.toString());
        return count;
    }

    // Loaded display-fallback blocks: how many have displays built, for how many watchers.
    private static int displays(CommandContext<CommandSourceStack> context) {
        FallbackBlockElementHolder.Summary summary = FallbackBlockElementHolder.summary();
        send(context, "Display fallback: " + summary.holders() + " blocks loaded, "
                + summary.drawn() + " drawn with " + summary.displays() + " displays for "
                + summary.watchers() + " watchers");
        return summary.displays();
    }

    // One block's display-fallback holder: its displays and watchers, or that it has none.
    private static int displaysAt(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        BlockBoundAttachment attachment =
                BlockBoundAttachment.get(context.getSource().getLevel(), pos);
        if (attachment == null
                || !(attachment.holder() instanceof FallbackBlockElementHolder holder)) {
            send(context, pos.toShortString() + ": no display-fallback holder");
            return 0;
        }
        send(context, pos.toShortString() + ": " + holder.displayCount() + " displays, "
                + holder.getWatchingPlayers().size() + " watchers");
        return holder.displayCount();
    }

    // Ids of every block with a stand-in, for suggestions.
    private static Iterable<Identifier> tableBlocks() {
        return Stream.concat(OlderClientStandIns.standIns().keySet().stream(),
                        OlderClientStandIns.displayStandIns().keySet().stream())
                .map(state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                .distinct()
                .toList();
    }

    // Sends one line of feedback to the source only.
    private static void send(CommandContext<CommandSourceStack> context, String line) {
        context.getSource().sendSuccess(() -> Component.literal(line), false);
    }
}
