package club.rainbowkitty.polybackwards.sound;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.block.SoundType;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.client.ClientVersions;
import club.rainbowkitty.polybackwards.client.Release;

/**
 * Sends a client with the server pack, in place of each sound event its release lacks, the older
 * vanilla event {@code older_client_sounds.json} names. The swap happens as a sound holder is
 * encoded for that client, so sound packets, explosions and item components all carry it.
 */
public final class OlderClientSounds {
    // The shipped table, written by hand: each pick has its reason beside it.
    private static final String PATH = "/polybackwards/older_client_sounds.json";

    // Per new event's id, its release and replacement; set once by register().
    private static Map<Identifier, Entry> table = Map.of();

    private OlderClientSounds() {}

    /** Reads the table; a missing or bad table leaves every sound as it is. */
    public static void register() {
        table = load();
        Polybackwards.LOGGER.info("Older-client sounds: {} events mapped", table.size());
    }

    /**
     * The holder a client in {@code context} is sent for {@code holder}: the table's older event
     * when the client's release lacks the event and it holds the server pack, else the holder.
     */
    public static Holder<?> forClient(Holder<?> holder, @Nullable PacketContext context) {
        if (context == null || table.isEmpty() || !(holder.value() instanceof SoundEvent event)) {
            return holder;
        }
        Entry entry = table.get(event.location());
        if (entry == null || ClientVersions.of(context).compareTo(entry.release()) >= 0
                || !PolymerResourcePackUtils.hasMainPack(context)) {
            return holder;
        }
        return entry.replacement();
    }

    /** Whether a client hears both sound types alike: same events, volume and pitch. */
    public static boolean sameSounds(SoundType a, SoundType b) {
        return a == b || a.getVolume() == b.getVolume() && a.getPitch() == b.getPitch()
                && a.getBreakSound() == b.getBreakSound() && a.getStepSound() == b.getStepSound()
                && a.getPlaceSound() == b.getPlaceSound() && a.getHitSound() == b.getHitSound()
                && a.getFallSound() == b.getFallSound();
    }

    // Reads the table from this jar, skipping entries this server cannot resolve.
    private static Map<Identifier, Entry> load() {
        Map<Identifier, Entry> loaded = new HashMap<>();
        try (InputStream in = OlderClientSounds.class.getResourceAsStream(PATH)) {
            if (in == null) {
                Polybackwards.LOGGER.warn("Older-client sounds: no {} in this jar", PATH);
                return loaded;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject sounds = GsonHelper.getAsJsonObject(
                        JsonParser.parseReader(reader).getAsJsonObject(), "sounds");
                for (Map.Entry<String, JsonElement> json : sounds.entrySet()) {
                    Entry entry = entry(json.getKey(), json.getValue().getAsJsonObject());
                    if (entry != null) {
                        loaded.put(Identifier.parse(json.getKey()), entry);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Polybackwards.LOGGER.error("Older-client sounds: cannot read {}", PATH, e);
        }
        // A replacement that is itself new would reach the client as nothing.
        loaded.values().removeIf(entry -> {
            boolean chained = loaded.containsKey(entry.replacement().value().location());
            if (chained) {
                Polybackwards.LOGGER.warn("Older-client sounds: {} is mapped itself",
                        entry.replacement().value().location());
            }
            return chained;
        });
        return Map.copyOf(loaded);
    }

    // One table entry, or null with a warning when either event is missing from this server.
    private static @Nullable Entry entry(String id, JsonObject json) {
        Release release = Release.valueOf(GsonHelper.getAsString(json, "release"));
        String sound = GsonHelper.getAsString(json, "sound");
        if (BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(id)).isEmpty()) {
            Polybackwards.LOGGER.warn("Older-client sounds: no sound event {} on this server", id);
            return null;
        }
        Holder.Reference<SoundEvent> replacement = BuiltInRegistries.SOUND_EVENT
                .get(Identifier.parse(sound)).orElse(null);
        if (replacement == null) {
            Polybackwards.LOGGER.warn("Older-client sounds: no sound event {} for {}", sound, id);
            return null;
        }
        return new Entry(release, replacement);
    }

    // A new event's release and the older event sent in its place.
    private record Entry(Release release, Holder.Reference<SoundEvent> replacement) {
    }
}
