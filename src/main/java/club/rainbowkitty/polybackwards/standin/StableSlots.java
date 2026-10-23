package club.rainbowkitty.polybackwards.standin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.polybackwards.Polybackwards;

/**
 * The RKC Polymer fork's stable note block slots that no carrier table wants for a loaded mod.
 * Stock Polymer has none. The carrier tables (polymer-patch-bundle's, enderscape-polymer-patch's,
 * identical by rule) hand each slot to one modded block by a fixed list.
 */
final class StableSlots {
    // The fork's slot count, absent from stock Polymer.
    private static final @Nullable MethodHandle COUNT = find("getStableSlotCount", int.class);
    // The fork's slot request, absent from stock Polymer.
    private static final @Nullable MethodHandle REQUEST =
            find("requestStableBlock", BlockState.class, int.class, PolymerBlockModel[].class);
    // Carrier tables by class name; any one is enough, since they are kept identical.
    private static final List<String> CARRIER_TABLES = List.of("me.drex.ppb.block.CarrierTiers",
            "eu.pb4.enderscapepatch.impl.block.CarrierTiers");

    private final int count;
    // Free slots not yet tried, last slot first, since carrier tables put their least seen last.
    private final List<Integer> untried;
    private final int free;
    private int taken;

    // Captures which slots are free now, before any mod claims one.
    private StableSlots(int count, List<Integer> free) {
        this.count = count;
        this.untried = new ArrayList<>(free.reversed());
        this.free = free.size();
    }

    // The fork's slots as they stand; none on stock Polymer.
    static StableSlots find() {
        if (COUNT == null || REQUEST == null) {
            return new StableSlots(0, List.of());
        }
        int count;
        try {
            count = (int) COUNT.invokeExact();
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        List<String> wanted;
        try {
            wanted = carrierTable();
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A table we cannot read may want every slot.
            Polybackwards.LOGGER.warn("Older-client stand-ins: cannot read a carrier table", e);
            return new StableSlots(count, List.of());
        }
        List<Integer> free = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) {
            if (wanted == null || slot >= wanted.size() || !isLoaded(wanted.get(slot))) {
                free.add(slot);
            }
        }
        return new StableSlots(count, free);
    }

    // The fork's slot count, 0 on stock Polymer.
    int count() {
        return this.count;
    }

    // How many slots no carrier table wanted when claims began.
    int free() {
        return this.free;
    }

    // How many slots this mod took.
    int taken() {
        return this.taken;
    }

    // The next free slot drawn with these models, its state, or null when none is left.
    @Nullable Claimed request(PolymerBlockModel[] models) {
        while (!this.untried.isEmpty()) {
            int slot = this.untried.removeFirst();
            BlockState state;
            try {
                state = (BlockState) REQUEST.invokeExact(slot, models);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
            if (state != null) {
                this.taken++;
                return new Claimed(slot, state);
            }
        }
        return null;
    }

    // Block ids of the first carrier table on the classpath, by slot; null when there is none.
    private static @Nullable List<String> carrierTable() throws ReflectiveOperationException {
        for (String name : CARRIER_TABLES) {
            Class<?> table;
            try {
                table = Class.forName(name);
            } catch (ClassNotFoundException e) {
                continue;
            }
            Field slotsField = table.getDeclaredField("SLOTS");
            slotsField.setAccessible(true);
            List<String> blocks = new ArrayList<>();
            for (Object slot : (List<?>) slotsField.get(null)) {
                blocks.add((String) blockAccessor(slot.getClass()).invoke(slot));
            }
            return blocks;
        }
        return null;
    }

    // The accessible accessor of a slot record's block component.
    private static Method blockAccessor(Class<?> slot) throws NoSuchMethodException {
        for (RecordComponent component : slot.getRecordComponents()) {
            if (component.getName().equals("block")) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                return accessor;
            }
        }
        throw new NoSuchMethodException(slot.getName() + ".block()");
    }

    // Whether the mod owning a block id is loaded, so its block will claim its slot.
    private static boolean isLoaded(String blockId) {
        return FabricLoader.getInstance().isModLoaded(Identifier.parse(blockId).getNamespace());
    }

    // Looks up a static PolymerBlockResourceUtils method, or null when this Polymer lacks it.
    private static @Nullable MethodHandle find(String name, Class<?> returns,
            Class<?>... parameters) {
        try {
            return MethodHandles.publicLookup().findStatic(PolymerBlockResourceUtils.class, name,
                    MethodType.methodType(returns, parameters));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * A stable slot this mod took.
     *
     * @param slot the slot index
     * @param state the note block state the slot hands out
     */
    record Claimed(int slot, BlockState state) {
    }
}
