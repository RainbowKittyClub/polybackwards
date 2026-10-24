package club.rainbowkitty.polybackwards.item;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.Release;
import club.rainbowkitty.polybackwards.sound.OlderClientSounds;
import club.rainbowkitty.polybackwards.standin.OlderClientMapper;
import club.rainbowkitty.polybackwards.standin.OlderClientStandIns;

/**
 * What a client that lacks an item may predict when it uses the item it is sent instead. A block
 * whose placements all show one pooled stand-in sounding like the block gets that stand-in's own
 * block item, pinned by {@code block_state}, so the client places exactly what the server sends.
 * Any other item that places something gets a firework star, which predicts nothing; the server
 * then plays its sounds and swing to that client too, which vanilla leaves to the client.
 */
public final class ClientPlacement {
    // Properties the world sets after a placement, as the RKC Polymer fork's
    // PolymerBlockItemStandIns.LATER_PROPERTIES; a state differing in one is never placed.
    private static final Set<String> LATER_PROPERTIES = Set.of(
            "lit", "powered", "open", "triggered", "enabled", "extended", "occupied", "has_record",
            "has_book", "attached", "disarmed", "inverted", "unstable", "cracked", "bloom",
            "shrieking", "can_summon", "crafting", "ominous", "eye", "drag", "berries", "dusted",
            "age", "power", "level", "honey_level", "moisture", "note", "stage", "charges", "hatch",
            "bites", "delay", "tilt", "natural");
    private static final Release[] RELEASES = Release.values();
    // A success's swing the client shows itself, and one the server sends it.
    private static final InteractionResult.SwingSource CLIENT_SWING =
            InteractionResult.SwingSource.PREDICTED;
    private static final InteractionResult.SwingSource SERVER_SWING =
            InteractionResult.SwingSource.SERVER_ONLY;
    // The client whose item use the server now runs unpredicted, so its exclusions lift.
    private static final ThreadLocal<ServerPlayer> UNPREDICTED = new ThreadLocal<>();
    // Per item and client release; filled on first use, after the claims.
    private static final Map<Item, Choice[]> CHOICES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> SURVIVAL_RULES = new ConcurrentHashMap<>();
    // Stand-ins held in the fork's stable note block slots; built on first use.
    private static volatile @Nullable Set<BlockState> stableStandIns;

    private ClientPlacement() {}

    /** Adds {@code /polybackwards placement <item>}, which prints each release's choice. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
                registerCommand(dispatcher));
    }

    /**
     * What a client on {@code release} is sent for {@code real}, which it lacks and would get as
     * {@code substitute}.
     */
    public static Choice choose(Item real, Item substitute, Release release) {
        Choice[] byRelease = CHOICES.computeIfAbsent(real, item -> new Choice[RELEASES.length]);
        Choice choice = byRelease[release.ordinal()];
        if (choice == null) {
            choice = decide(real, substitute, release);
            byRelease[release.ordinal()] = choice;
        }
        return choice;
    }

    /**
     * Runs a player's use of {@code stack}, the item's own and not the clicked block's. When that
     * player's client holds a firework star for it, the use's sounds reach the player too and a
     * success swings the player's arm from the server.
     */
    public static InteractionResult use(ServerPlayer player, ItemStack stack,
            Supplier<InteractionResult> use) {
        Choice choice = choiceFor(player, stack);
        if (choice == null || choice.kind() != Kind.UNPREDICTED) {
            return use.get();
        }
        ServerPlayer outer = UNPREDICTED.get();
        UNPREDICTED.set(player);
        InteractionResult result;
        try {
            result = use.get();
        } finally {
            if (outer == null) {
                UNPREDICTED.remove();
            } else {
                UNPREDICTED.set(outer);
            }
        }
        if (result instanceof InteractionResult.Success success
                && success.swingSource() == CLIENT_SWING) {
            return new InteractionResult.Success(SERVER_SWING, success.itemContext());
        }
        return result;
    }

    /** The entity a sound leaves out: none while that entity's use runs unpredicted. */
    public static @Nullable Entity exclusion(@Nullable Entity except) {
        return except != null && except == UNPREDICTED.get() ? null : except;
    }

    // The choice for the item the player's client holds, as OlderClientItems swaps it.
    private static @Nullable Choice choiceFor(ServerPlayer player, ItemStack stack) {
        PacketContext context = player.connection == null ? null
                : player.connection.getPacketContext();
        return context == null ? null
                : OlderClientItems.choiceFor(clientItem(player, stack, context), context);
    }

    // The item Polymer sends for the stack: a Polymer item's client item, else the stack's own.
    private static Item clientItem(ServerPlayer player, ItemStack stack, PacketContext context) {
        return PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, stack.getItem())
                instanceof PolymerItem polymerItem
                ? PolymerItemUtils.getItemSafely(polymerItem, stack, context,
                        player.registryAccess()).item()
                : stack.getItem();
    }

    // Pins a lone matching stand-in, else a firework star where the client would place a block.
    private static Choice decide(Item real, Item substitute, Release release) {
        if (real instanceof BlockItem blockItem) {
            BlockState standIn = pinnableStandIn(blockItem, release);
            if (standIn != null) {
                return new Choice(Kind.PINNED, standIn.getBlock().asItem(), pin(standIn));
            }
            return new Choice(Kind.UNPREDICTED, Items.FIREWORK_STAR, null);
        }
        return substitute instanceof BlockItem
                ? new Choice(Kind.UNPREDICTED, Items.FIREWORK_STAR, null)
                : new Choice(Kind.KEPT, substitute, null);
    }

    // The one stand-in every placement of the item shows that client, if it may place it itself.
    private static @Nullable BlockState pinnableStandIn(BlockItem item, Release release) {
        Map<Block, Item> placed = new HashMap<>();
        item.registerBlocks(placed, item);
        Block block = item.getBlock();
        if (placed.size() != 1) {
            return null;
        }
        BlockState found = null;
        BlockState defaultState = block.defaultBlockState();
        for (BlockState real : block.getStateDefinition().getPossibleStates()) {
            if (!isPlacementState(real, defaultState)) {
                continue;
            }
            BlockState standIn = OlderClientStandIns.standIn(real);
            // Only a pooled stand-in draws the real model; a display one shows its own.
            if (standIn == null || standIn != OlderClientMapper.standInFor(release, real)
                    || found != null && found != standIn
                    || !OlderClientSounds.sameSounds(real.getSoundType(), standIn.getSoundType())) {
                return null;
            }
            found = standIn;
        }
        return found != null && canPlaceItself(found) ? found : null;
    }

    // Whether a vanilla client placing the stand-in from its own item gets that exact state.
    private static boolean canPlaceItself(BlockState standIn) {
        Block block = standIn.getBlock();
        Item item = block.asItem();
        if (standIn.getRenderShape() == RenderShape.INVISIBLE || item.getClass() != BlockItem.class
                || ((BlockItem) item).getBlock() != block || hasSurvivalRule(block)) {
            return false;
        }
        // A note block outside a stable slot changes model when the client re-tunes it.
        return !(block instanceof NoteBlock) || stableStandIns().contains(standIn);
    }

    // Whether a state can come out of a placement: every later property at its default.
    private static boolean isPlacementState(BlockState state, BlockState defaultState) {
        for (Property<?> property : state.getProperties()) {
            if (LATER_PROPERTIES.contains(property.getName())
                    && !state.getValue(property).equals(defaultState.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    // A block overriding canSurvive can refuse a placement on the client that the server allows.
    private static boolean hasSurvivalRule(Block block) {
        return SURVIVAL_RULES.computeIfAbsent(block.getClass(), type -> {
            for (Class<?> c = type; c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
                try {
                    c.getDeclaredMethod("canSurvive", BlockState.class, LevelReader.class,
                            BlockPos.class);
                    return true;
                } catch (NoSuchMethodException e) {
                    // Declared further up, or nowhere.
                }
            }
            return false;
        });
    }

    // Every claim's stand-in that sits in a stable slot.
    private static Set<BlockState> stableStandIns() {
        Set<BlockState> found = stableStandIns;
        if (found == null) {
            Set<BlockState> stable = Collections.newSetFromMap(new IdentityHashMap<>());
            for (OlderClientStandIns.Claim claim : OlderClientStandIns.claims()) {
                if (claim.stableSlot() >= 0) {
                    stable.add(claim.standIn());
                }
            }
            found = Collections.unmodifiableSet(stable);
            stableStandIns = found;
        }
        return found;
    }

    // Every property of a stand-in, as a block_state component.
    private static BlockItemStateProperties pin(BlockState standIn) {
        BlockItemStateProperties pin = BlockItemStateProperties.EMPTY;
        for (Property<?> property : standIn.getProperties()) {
            pin = with(pin, property, standIn);
        }
        return pin;
    }

    // One property's value from a state, added to a component.
    private static <T extends Comparable<T>> BlockItemStateProperties with(
            BlockItemStateProperties pin, Property<T> property, BlockState state) {
        return pin.with(property, state);
    }

    // `/polybackwards placement <item>`, for operators.
    private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Polybackwards.MOD_ID)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("placement")
                        .then(Commands.argument("item", IdentifierArgument.id())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggestResource(
                                                OlderClientItems.items(), builder))
                                .executes(ClientPlacement::describe))));
    }

    // One item's choice per client release on one line; "-" where the release has the item.
    private static int describe(CommandContext<CommandSourceStack> context) {
        Identifier id = IdentifierArgument.getId(context, "item");
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            context.getSource().sendFailure(Component.literal("No item " + id));
            return 0;
        }
        StringBuilder line = new StringBuilder(id.toString());
        int count = 0;
        for (Release release : RELEASES) {
            Item substitute = OlderClientItems.substituteFor(release, item);
            line.append(' ').append(release).append('=');
            if (substitute == null) {
                line.append('-');
                continue;
            }
            count++;
            Choice choice = choose(item, substitute, release);
            line.append(choice.kind().name().toLowerCase(Locale.ROOT)).append(' ');
            if (choice.pin() != null && choice.item() instanceof BlockItem pinned) {
                line.append(BlockStateParser.serialize(
                        choice.pin().apply(pinned.getBlock().defaultBlockState())));
            } else {
                line.append(BuiltInRegistries.ITEM.getKey(choice.item()));
            }
        }
        String text = line.toString();
        context.getSource().sendSuccess(() -> Component.literal(text), false);
        return count;
    }

    /** How a client that lacks an item gets it. */
    public enum Kind {
        /** As ViaBackwards' substitute, which places nothing. */
        KEPT,
        /** As its stand-in's own block item, pinned to that stand-in. */
        PINNED,
        /** As a firework star, which places nothing; the server shows the use instead. */
        UNPREDICTED
    }

    /**
     * What a client that lacks an item is sent for it.
     *
     * @param kind how it was chosen
     * @param item the item the client holds instead
     * @param pin the {@code block_state} pinning the stand-in, or null when not pinned
     */
    public record Choice(Kind kind, Item item, @Nullable BlockItemStateProperties pin) {
    }
}
