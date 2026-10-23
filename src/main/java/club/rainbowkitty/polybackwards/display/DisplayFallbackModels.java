package club.rainbowkitty.polybackwards.display;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.OctahedralGroup;
import com.mojang.math.Quadrant;
import com.mojang.math.Transformation;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.resourcepack.api.AssetPaths;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.extras.api.ResourcePackExtras;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockMath;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.polybackwards.Polybackwards;
import club.rainbowkitty.polybackwards.standin.OlderClientStandIns;

/**
 * The models older clients' displays draw over display-fallback stand-ins: a copy of each model
 * the real state draws, grown 0.08 px a side so it covers a same-shape stand-in, and bridged as an
 * item model. A sign's copy grows half that through its board, which keeps the stand-in's text in
 * front. A uvlock variant that its blockstate turns gets the turn baked into its copy, as a client
 * bakes it, since a turned display turns the texture with the model.
 */
public final class DisplayFallbackModels {
    private static final String FOLDER = "display";
    private static final float GROWTH = 0.08f;
    private static final Vector3fc UNIFORM_GROWTH = new Vector3f(GROWTH);
    // Sign text floats 0.08 px off the board, whose faces point along z in every sign model.
    private static final Vector3fc SIGN_GROWTH = new Vector3f(GROWTH, GROWTH, GROWTH / 2);
    // Each face's corners as a client bakes them, by Direction ordinal: 1 picks the max per axis.
    private static final int[][][] CORNERS = {
        {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
        {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
        {{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},
        {{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},
        {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},
        {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}}
    };
    private static final Quadrant[] QUADRANTS = Quadrant.values();
    // Each display's item model with the copy holding its elements, from the last pack build.
    private static volatile Map<Identifier, Identifier> geometry = Map.of();

    private DisplayFallbackModels() {}

    /** Bridges the copies as item models and writes them into every main-pack build. */
    public static void register() {
        ResourcePackExtras.forDefault().addBridgedModelsFolder(Polybackwards.id(FOLDER));
        PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT.register(
                DisplayFallbackModels::write);
    }

    /** The item model a display shows for one blockstate model. */
    public static Identifier itemModel(PolymerBlockModel model) {
        return ResourcePackExtras.bridgeModel(copyId(model.model(), model));
    }

    /**
     * The item model of every display with the model holding its copy's elements, for crack
     * models shaped like the copies; filled by each main-pack build, empty before the first.
     */
    public static Map<Identifier, Identifier> geometry() {
        return geometry;
    }

    /** Whether the model's copy has its blockstate turn baked in, so displays show it unturned. */
    public static boolean bakesTurn(PolymerBlockModel model) {
        return model.uvLock() && (model.x() % 360 != 0 || model.y() % 360 != 0);
    }

    // Writes the copy of every model a display-fallback state draws.
    private static void write(ResourcePackBuilder builder) {
        Map<String, Wanted> wanted = new TreeMap<>();
        for (BlockState real : OlderClientStandIns.displayStandIns().keySet()) {
            Vector3fc growth = real.getBlock() instanceof SignBlock ? SIGN_GROWTH : UNIFORM_GROWTH;
            for (PolymerBlockModel[] choices : OlderClientStandIns.models(real)) {
                for (PolymerBlockModel model : choices) {
                    wanted.putIfAbsent(copyId(model.model(), model).toString(),
                            new Wanted(model, growth));
                }
            }
        }
        Set<Identifier> written = new HashSet<>();
        Map<Identifier, Identifier> elements = new HashMap<>();
        int failed = 0;
        for (Wanted copy : wanted.values()) {
            try {
                Identifier template = writeCopy(builder, copy.model(), copy.growth(), written);
                if (template == null) {
                    failed++;
                } else {
                    elements.put(itemModel(copy.model()), template);
                }
            } catch (RuntimeException e) {
                Polybackwards.LOGGER.warn("Display fallback: cannot copy {}", copy.model().model(),
                        e);
                failed++;
            }
        }
        geometry = Map.copyOf(elements);
        if (!wanted.isEmpty()) {
            Polybackwards.LOGGER.info("Display fallback: {} model copies in {} files, {} failed",
                    wanted.size() - failed, written.size(), failed);
        }
    }

    // Writes one model's copy: its element-holding template grown and maybe turned, which models
    // of a family share, and a child naming it with the textures above it. Returns the template.
    private static @Nullable Identifier writeCopy(ResourcePackBuilder builder,
            PolymerBlockModel model, Vector3fc growth, Set<Identifier> written) {
        List<Identifier> ids = new ArrayList<>();
        List<JsonObject> chain = new ArrayList<>();
        Identifier next = model.model();
        while (next != null && !next.getPath().startsWith("builtin/")) {
            byte[] data = builder.getDataOrSource(AssetPaths.model(next) + ".json");
            if (data == null) {
                Polybackwards.LOGGER.warn("Display fallback: no model {}", next);
                return null;
            }
            JsonObject json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            ids.add(next);
            chain.add(json);
            next = json.has("parent") ? Identifier.parse(json.get("parent").getAsString()) : null;
        }
        int template = -1;
        for (int i = 0; i < chain.size() && template < 0; i++) {
            if (chain.get(i).has("elements")) {
                template = i;
            }
        }
        if (template < 0) {
            Polybackwards.LOGGER.warn("Display fallback: {} draws no elements", model.model());
            return null;
        }
        Identifier templateCopy = copyId(ids.get(template), model);
        if (written.add(templateCopy)) {
            JsonObject copy = new JsonObject();
            copy.add("textures", textures(chain.subList(template, chain.size())));
            copy.add("elements",
                    elements(chain.get(template).getAsJsonArray("elements"), model, growth));
            builder.addData(AssetPaths.model(templateCopy) + ".json",
                    copy.toString().getBytes(StandardCharsets.UTF_8));
        }
        Identifier childCopy = copyId(model.model(), model);
        if (template > 0 && written.add(childCopy)) {
            JsonObject child = new JsonObject();
            child.addProperty("parent", templateCopy.toString());
            child.add("textures", textures(chain.subList(0, template)));
            builder.addData(AssetPaths.model(childCopy) + ".json",
                    child.toString().getBytes(StandardCharsets.UTF_8));
        }
        return templateCopy;
    }

    // polybackwards:display/<namespace>/<path>, with the turn when the copy bakes one.
    private static Identifier copyId(Identifier source, PolymerBlockModel model) {
        String turn = bakesTurn(model) ? "_uvlock_" + model.x() + "_" + model.y() : "";
        return Polybackwards.id(FOLDER + "/" + source.getNamespace() + "/" + source.getPath()
                + turn);
    }

    // A chain's texture variables, the model's own over its parents'.
    private static JsonObject textures(List<JsonObject> chain) {
        Map<String, JsonElement> merged = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (chain.get(i).get("textures") instanceof JsonObject textures) {
                textures.entrySet().forEach(entry -> merged.put(entry.getKey(), entry.getValue()));
            }
        }
        JsonObject out = new JsonObject();
        merged.forEach(out::add);
        return out;
    }

    // The template's elements, each face with its UV written out, turned if baked, then grown.
    private static JsonArray elements(JsonArray source, PolymerBlockModel model,
            Vector3fc growth) {
        Matrix4fc turn = null;
        Transformation transformation = null;
        if (bakesTurn(model)) {
            OctahedralGroup group =
                    Quadrant.fromXYAngles(quadrant(model.x()), quadrant(model.y()));
            turn = new Matrix4f(group.transformation());
            transformation = new Transformation(turn);
        }
        JsonArray out = new JsonArray();
        for (JsonElement entry : source) {
            JsonObject element = entry.getAsJsonObject().deepCopy();
            Vector3f from = vector(element.getAsJsonArray("from"));
            Vector3f to = vector(element.getAsJsonArray("to"));
            JsonObject faces = element.getAsJsonObject("faces");
            if (turn != null) {
                if (element.has("rotation")) {
                    Polybackwards.LOGGER.warn("Display fallback: {} keeps an element unturned",
                            model.model());
                } else {
                    faces = turnFaces(faces, from, to, turn, transformation);
                    Vector3f a = turned(turn, new Vector3f(from));
                    Vector3f b = turned(turn, new Vector3f(to));
                    from = new Vector3f(a).min(b);
                    to = a.max(b);
                }
            } else {
                for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                    JsonObject faceJson = face.getValue().getAsJsonObject();
                    if (!faceJson.has("uv")) {
                        faceJson.add("uv", array(defaultUv(from, to, direction(face.getKey()))));
                    }
                }
            }
            element.add("faces", faces);
            element.add("from", array(new float[] {from.x - growth.x(), from.y - growth.y(),
                from.z - growth.z()}));
            element.add("to", array(new float[] {to.x + growth.x(), to.y + growth.y(),
                to.z + growth.z()}));
            out.add(element);
        }
        return out;
    }

    // Each face moved to the side the turn puts it on, with the UVs a uvlock bake gives it there.
    private static JsonObject turnFaces(JsonObject faces, Vector3f from, Vector3f to,
            Matrix4fc turn, Transformation transformation) {
        Vector3f a = turned(turn, new Vector3f(from));
        Vector3f b = turned(turn, new Vector3f(to));
        Vector3f turnedFrom = new Vector3f(a).min(b);
        Vector3f turnedTo = a.max(b);
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
            Direction side = direction(entry.getKey());
            JsonObject face = entry.getValue().getAsJsonObject().deepCopy();
            float[] uv = face.has("uv") ? floats(face.getAsJsonArray("uv"))
                    : defaultUv(from, to, side);
            Quadrant uvTurn = face.has("rotation")
                    ? quadrant(face.get("rotation").getAsInt()) : Quadrant.R0;
            Matrix4fc uvLock = BlockMath.getFaceTransformation(transformation, side).getMatrix()
                    .invertAffine(new Matrix4f());
            Vector3f[] corners = new Vector3f[4];
            float[][] uvs = new float[4][];
            for (int i = 0; i < 4; i++) {
                corners[i] = turned(turn, corner(side, i, from, to));
                int k = uvTurn.rotateVertexIndex(i);
                float u = (k == 0 || k == 1 ? uv[0] : uv[2]) / 16f;
                float v = (k == 0 || k == 3 ? uv[1] : uv[3]) / 16f;
                Vector3f locked = uvLock.transformPosition(new Vector3f(u - 0.5f, v - 0.5f, 0f));
                uvs[i] = new float[] {(locked.x + 0.5f) * 16f, (locked.y + 0.5f) * 16f};
            }
            Direction turnedSide = Direction.rotate(turn, side);
            if (!fitFace(face, turnedSide, turnedFrom, turnedTo, corners, uvs)) {
                Polybackwards.LOGGER.warn("Display fallback: no uvlock fit for a {} face", side);
                face.add("uv", array(defaultUv(turnedFrom, turnedTo, turnedSide)));
                face.remove("rotation");
            }
            if (face.has("cullface")) {
                face.addProperty("cullface", Direction.rotate(turn,
                        direction(face.get("cullface").getAsString())).getSerializedName());
            }
            out.add(turnedSide.getSerializedName(), face);
        }
        return out;
    }

    // Sets uv and rotation so an unturned bake of the face gives each corner its turned UV.
    private static boolean fitFace(JsonObject face, Direction side, Vector3f from, Vector3f to,
            Vector3f[] corners, float[][] uvs) {
        float[][] wanted = new float[4][];
        for (int j = 0; j < 4; j++) {
            Vector3f at = corner(side, j, from, to);
            for (int i = 0; i < 4 && wanted[j] == null; i++) {
                if (corners[i].distance(at) < 1e-3f) {
                    wanted[j] = uvs[i];
                }
            }
            if (wanted[j] == null) {
                return false;
            }
        }
        for (Quadrant quadrant : QUADRANTS) {
            float[] uv = new float[4];
            boolean[] set = new boolean[4];
            boolean fits = true;
            for (int j = 0; j < 4 && fits; j++) {
                int k = quadrant.rotateVertexIndex(j);
                fits = take(uv, set, k == 0 || k == 1 ? 0 : 2, wanted[j][0])
                        && take(uv, set, k == 0 || k == 3 ? 1 : 3, wanted[j][1]);
            }
            if (fits) {
                face.add("uv", array(uv));
                if (quadrant == Quadrant.R0) {
                    face.remove("rotation");
                } else {
                    face.addProperty("rotation", quadrant.ordinal() * 90);
                }
                return true;
            }
        }
        return false;
    }

    // Puts a value in one uv slot, or checks it against the value already there.
    private static boolean take(float[] uv, boolean[] set, int slot, float value) {
        if (set[slot]) {
            return Math.abs(uv[slot] - value) < 1e-3f;
        }
        uv[slot] = value;
        set[slot] = true;
        return true;
    }

    // One corner of an element's face, in model pixels.
    private static Vector3f corner(Direction side, int index, Vector3f from, Vector3f to) {
        int[] pick = CORNERS[side.ordinal()][index];
        return new Vector3f(pick[0] == 1 ? to.x : from.x, pick[1] == 1 ? to.y : from.y,
                pick[2] == 1 ? to.z : from.z);
    }

    // A point turned about the block's centre.
    private static Vector3f turned(Matrix4fc turn, Vector3f point) {
        return turn.transformPosition(point.sub(8f, 8f, 8f)).add(8f, 8f, 8f);
    }

    // The UV a face without one takes from the element's box, as a client computes it.
    private static float[] defaultUv(Vector3f from, Vector3f to, Direction side) {
        return switch (side) {
            case DOWN -> new float[] {from.x, 16f - to.z, to.x, 16f - from.z};
            case UP -> new float[] {from.x, from.z, to.x, to.z};
            case NORTH -> new float[] {16f - to.x, 16f - to.y, 16f - from.x, 16f - from.y};
            case SOUTH -> new float[] {from.x, 16f - to.y, to.x, 16f - from.y};
            case WEST -> new float[] {from.z, 16f - to.y, to.z, 16f - from.y};
            case EAST -> new float[] {16f - to.z, 16f - to.y, 16f - from.z, 16f - from.y};
        };
    }

    // A quarter turn in degrees as its quadrant.
    private static Quadrant quadrant(int degrees) {
        if (degrees % 90 != 0) {
            throw new IllegalArgumentException("Not a quarter turn: " + degrees);
        }
        return QUADRANTS[Math.floorMod(degrees, 360) / 90];
    }

    // A face key such as "north" as its direction.
    private static Direction direction(String name) {
        Direction side = Direction.byName(name);
        if (side == null) {
            throw new IllegalArgumentException("Not a face: " + name);
        }
        return side;
    }

    // A JSON number triple as a vector.
    private static Vector3f vector(JsonArray array) {
        float[] values = floats(array);
        return new Vector3f(values[0], values[1], values[2]);
    }

    // A JSON number array as floats.
    private static float[] floats(JsonArray array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsFloat();
        }
        return values;
    }

    // Floats as a JSON array, rounded so float noise does not reach the file.
    private static JsonArray array(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(Math.round(value * 10000.0) / 10000.0);
        }
        return array;
    }

    // One model to copy, and how far its copy grows along each axis.
    private record Wanted(PolymerBlockModel model, Vector3fc growth) {
    }
}
