package club.rainbowkitty.polybackwards.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/** A boat's synced bubble-column time, private to it. */
@Mixin(AbstractBoat.class)
public interface AbstractBoatAccessor {
    /** The bubble time's data id, which rocks the boat over a bubble column. */
    @Accessor("DATA_ID_BUBBLE_TIME")
    static EntityDataAccessor<Integer> polybackwards$bubbleTime() {
        throw new AssertionError();
    }
}
