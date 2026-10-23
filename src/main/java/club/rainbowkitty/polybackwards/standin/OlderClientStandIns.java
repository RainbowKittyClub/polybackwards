package club.rainbowkitty.polybackwards.standin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.BlockResourceCreator;
import eu.pb4.polymer.blocks.api.MultiPolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.Release;

/**
 * Vanilla stand-ins for the vanilla blocks some older client lacks, one per model variant, claimed
 * from Polymer's pools before any mod requests one. Which clients see them is not decided here.
 */
public final class OlderClientStandIns {
    // Stand-ins by real state; filled once by claim, then only read.
    private static volatile Map<BlockState, BlockState> standIns = Map.of();
    private static volatile Map<BlockState, BlockState> displayStandIns = Map.of();
    private static volatile Set<BlockState> emptyStandIns = Set.of();
    private static volatile Set<Block> displayFallback = Set.of();
    private static volatile List<Claim> claims = List.of();
    private static volatile @Nullable StableSlots slots;
    private static boolean claimed;

    private OlderClientStandIns() {}

    /**
     * The stand-in a client predating the block's release sees for real, pooled or a double
     * slab's full block; null when the state takes the display fallback or is no newer than 26.1.
     */
    public static @Nullable BlockState standIn(BlockState real) {
        return standIns.get(real);
    }

    /**
     * Every real state with a stand-in of its own, pooled or a double slab's full block; empty
     * before claims.
     */
    public static Map<BlockState, BlockState> standIns() {
        return standIns;
    }

    /**
     * The same-shape vanilla stand-in for a state on the display fallback, a copy's included, which
     * a display covers with the real model; null for a pooled state or a block no older than 26.1.
     */
    public static @Nullable BlockState displayStandIn(BlockState real) {
        return displayStandIns.get(real);
    }

    /** Every state on the display fallback with its same-shape stand-in; empty before claims. */
    public static Map<BlockState, BlockState> displayStandIns() {
        return displayStandIns;
    }

    /**
     * Whether a client shows nothing of a display stand-in, so no hit particles either: a barrier,
     * a state without terrain particles, or one of Polymer's empty states.
     */
    public static boolean drawsNothing(BlockState standIn) {
        return standIn.getRenderShape() == RenderShape.INVISIBLE
                || !standIn.shouldSpawnTerrainParticles() || emptyStandIns.contains(standIn);
    }

    /** The vanilla block a copy-kind block goes out as, or null for any other block. */
    public static @Nullable Block copyOf(Block block) {
        StandInTable.Entry entry = Table.TABLE.get(block);
        return entry == null || entry.kind() != StandInKind.COPY ? null : entry.copy();
    }

    /** The release that added the block, or null for a block 26.1 already has. */
    public static @Nullable Release introducedIn(Block block) {
        StandInTable.Entry entry = Table.TABLE.get(block);
        return entry == null ? null : entry.release();
    }

    /**
     * Blocks with a state that has no pooled stand-in (stairs, top slabs, fences, walls, buttons,
     * signs, and any pooled block a claim failed for); {@link #models} gives what each state draws.
     */
    public static Set<Block> displayFallbackBlocks() {
        return displayFallback;
    }

    /**
     * What a real state draws by the table: one array of weighted alternatives per variant or
     * applying multipart part, empty for a block not in it.
     */
    public static List<PolymerBlockModel[]> models(BlockState real) {
        StandInTable.Entry entry = Table.TABLE.get(real.getBlock());
        return entry == null ? List.of() : entry.models(real);
    }

    /** Every claim, in table order. */
    public static List<Claim> claims() {
        return claims;
    }

    /** The fork's stable slots as claims found them, or null before claims or on stock Polymer. */
    public static @Nullable String stableSlotSummary() {
        StableSlots found = slots;
        if (found == null || found.count() == 0) {
            return null;
        }
        return found.taken() + " taken of " + found.free() + " free, " + found.count() + " slots";
    }

    /** Initialises PolymerBlockResourceUtils, whose initialiser makes the claims, if none has. */
    public static void init() {
        PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.FULL_BLOCK);
    }

    /**
     * Claims every pooled stand-in from Polymer's creator; PolymerBlockResourceUtils' static
     * initialiser calls this once, before any request.
     */
    public static synchronized void claim(BlockResourceCreator creator) {
        if (claimed) {
            return;
        }
        claimed = true;
        StableSlots found = StableSlots.find();
        Map<BlockState, BlockState> claimedStandIns = new IdentityHashMap<>();
        Set<Block> fallback = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Claim> made = new ArrayList<>();
        for (StandInTable.Entry entry : Table.TABLE.entries()) {
            StandInKind kind = entry.kind();
            if (!kind.isPooled()) {
                if (kind == StandInKind.DISPLAY || kind == StandInKind.COPY) {
                    fallback.add(entry.block());
                }
                continue;
            }
            if (!kind.fits(entry.block())) {
                Polybackwards.LOGGER.warn("Older-client stand-ins: {} lacks the properties of {}",
                        entry.block(), kind);
                fallback.add(entry.block());
                continue;
            }
            claimBlock(creator, found, entry, claimedStandIns, fallback, made);
        }
        // Double slabs last, once every full block they draw as has its stand-in.
        for (StandInTable.Entry entry : Table.TABLE.entries()) {
            if (entry.kind() == StandInKind.SLAB && entry.kind().fits(entry.block())) {
                mapDoubleSlabs(entry, claimedStandIns, fallback);
            }
        }
        // Empty stand-ins come from the pools too, so they are taken before any mod's request.
        Set<BlockState> empty = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<BlockState, BlockState> shaped = DisplayStandIns.pick(creator, fallback,
                claimedStandIns, block -> Table.TABLE.get(block).kind(), empty);
        long copies = shaped.keySet().stream()
                .filter(real -> Table.TABLE.get(real.getBlock()).kind() == StandInKind.COPY)
                .count();
        standIns = Collections.unmodifiableMap(claimedStandIns);
        displayStandIns = Collections.unmodifiableMap(shaped);
        emptyStandIns = Collections.unmodifiableSet(empty);
        displayFallback = Collections.unmodifiableSet(fallback);
        claims = List.copyOf(made);
        slots = found;
        logSummary(made, found, copies, fallback.size(), shaped.size());
    }

    // Claims one stand-in per variant and pool of a block, mapping each of its states to one.
    private static void claimBlock(BlockResourceCreator creator, StableSlots found,
            StandInTable.Entry entry, Map<BlockState, BlockState> claimedStandIns,
            Set<Block> fallback, List<Claim> made) {
        Map<String, Claim> byVariant = new HashMap<>();
        BlockState donor = null;
        for (BlockState real : entry.block().getStateDefinition().getPossibleStates()) {
            BlockModelType pool = entry.kind().pool(real);
            if (pool == null) {
                if (!isDoubleSlab(real)) {
                    fallback.add(entry.block());
                }
                continue;
            }
            String key = entry.variant(real) + "|" + pool;
            Claim claim = byVariant.get(key);
            if (claim == null && !byVariant.containsKey(key)) {
                claim = request(creator, found, entry, real, pool, donor);
                byVariant.put(key, claim);
                if (claim != null) {
                    made.add(claim);
                    if (donor == null && claim.stableSlot() < 0) {
                        donor = claim.standIn();
                    }
                }
            }
            if (claim == null) {
                fallback.add(entry.block());
            } else {
                claimedStandIns.put(real, claim.standIn());
            }
        }
    }

    // One stand-in for a real state, from a free stable slot for wood, else from its pool.
    private static @Nullable Claim request(BlockResourceCreator creator, StableSlots found,
            StandInTable.Entry entry, BlockState real, BlockModelType pool,
            @Nullable BlockState donor) {
        String variant = entry.variant(real);
        List<PolymerBlockModel[]> models = entry.models(real);
        if (variant == null || models.isEmpty()) {
            Polybackwards.LOGGER.warn("Older-client stand-ins: no variant for {}", real);
            return null;
        }
        // Wood keeps its slot's look when an older client re-tunes a note block next to it.
        if (pool == BlockModelType.FULL_BLOCK && real.instrument() == NoteBlockInstrument.BASS
                && models.size() == 1) {
            StableSlots.Claimed stable = found.request(models.getFirst());
            if (stable != null) {
                return new Claim(real, variant, stable.state(), pool, stable.slot());
            }
        }
        // Polymer keeps one state of each pool for an empty request until someone makes one.
        int left = creator.getBlocksLeft(pool) - (creator.hasRequestedEmpty(pool) ? 0 : 1);
        if (left <= 0) {
            return null;
        }
        Predicate<BlockState> fits = state -> introducedIn(state.getBlock()) == null
                && sharesProperties(entry.kind(), state, real);
        List<Predicate<BlockState>> preferences = new ArrayList<>();
        if (donor != null && entry.kind().keepsOneDonor()) {
            preferences.add(fits.and(state -> sameDonor(entry.kind(), state, donor)));
        }
        preferences.add(fits.and(state -> state.getSoundType() == real.getSoundType()));
        preferences.add(fits);
        // A multipart state's parts go together; Polymer merges or writes them as multipart.
        MultiPolymerBlockModel parts = models.size() == 1 ? null
                : new MultiPolymerBlockModel(List.copyOf(models));
        for (Predicate<BlockState> preference : preferences) {
            BlockState standIn = parts == null
                    ? creator.requestBlock(pool, preference, models.getFirst())
                    : creator.requestBlock(pool, preference, parts);
            if (standIn != null) {
                return new Claim(real, variant, standIn, pool, -1);
            }
        }
        return null;
    }

    // Maps a slab's double states to its full block, or to that block's stand-in when it is new.
    private static void mapDoubleSlabs(StandInTable.Entry entry,
            Map<BlockState, BlockState> claimedStandIns, Set<Block> fallback) {
        Block full = entry.full();
        BlockState standIn = null;
        if (full != null) {
            standIn = introducedIn(full) == null ? full.defaultBlockState()
                    : claimedStandIns.get(full.defaultBlockState());
        }
        for (BlockState real : entry.block().getStateDefinition().getPossibleStates()) {
            if (!isDoubleSlab(real)) {
                continue;
            }
            if (standIn == null) {
                fallback.add(entry.block());
            } else {
                claimedStandIns.put(real, standIn);
            }
        }
    }

    // Whether a state is a double slab.
    private static boolean isDoubleSlab(BlockState state) {
        return state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
    }

    // Whether a candidate keeps every shared property the real state has.
    private static boolean sharesProperties(StandInKind kind, BlockState candidate,
            BlockState real) {
        for (Property<?> property : kind.sharedProperties()) {
            if (candidate.hasProperty(property) && real.hasProperty(property)
                    && !candidate.getValue(property).equals(real.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    // Whether a candidate is the donor's block with the donor's value of every unshared property.
    private static boolean sameDonor(StandInKind kind, BlockState candidate, BlockState donor) {
        if (candidate.getBlock() != donor.getBlock()) {
            return false;
        }
        for (Property<?> property : candidate.getProperties()) {
            if (!kind.sharedProperties().contains(property)
                    && !candidate.getValue(property).equals(donor.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    // Logs what the claims took, by pool.
    private static void logSummary(List<Claim> made, StableSlots found, long copies,
            int fallbackBlocks, int fallbackStates) {
        Map<BlockModelType, Integer> byPool = new EnumMap<>(BlockModelType.class);
        for (Claim claim : made) {
            if (claim.stableSlot() < 0) {
                byPool.merge(claim.pool(), 1, Integer::sum);
            }
        }
        Polybackwards.LOGGER.info("Older-client stand-ins: {} claimed, {} in stable slots ({} of "
                + "{} free); by pool {}; {} blocks on the display fallback, {} states, {} of them "
                + "copies", made.size(), found.taken(), found.free(), found.count(), byPool,
                fallbackBlocks, fallbackStates, copies);
    }

    /**
     * One stand-in claimed for a variant of a block some older client lacks.
     *
     * @param real the first real state found drawing that variant
     * @param variant the variant's key in the block's blockstate, or "#" and the indices of the
     *     multipart parts it draws
     * @param standIn the vanilla state an older client gets
     * @param pool the pool it came from
     * @param stableSlot the fork's stable note block slot it holds, or -1
     */
    public record Claim(BlockState real, String variant, BlockState standIn, BlockModelType pool,
            int stableSlot) {
    }

    // Loads the table on first use, after the vanilla registries are filled.
    private static final class Table {
        private static final StandInTable TABLE = StandInTable.load();

        // Holder only.
        private Table() {}
    }
}
