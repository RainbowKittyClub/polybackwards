package club.rainbowkitty.polybackwards.entity;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

// BoatModel's hull cuboids (client-only, so copied; no paddles, no chest), baked into item-model
// elements with the entity texture's mapping, as ppb's boat shells bake them.
final class BoatShellGeometry {
    // Every box grows by this (px) on each side, so the shell encloses the stand-in's hull.
    private static final double INFLATE = 0.25;
    private static final double EPSILON = 1e-4;
    private static final double TEXTURE_WIDTH = 128;
    // BoatModel.addCommonParts: bottom, back, front, right, left.
    private static final List<Part> HULL = List.of(
        new Part(0, 3, 1, 1, 0, new Cube(0, 0, -14, -9, -3, 28, 16, 3)),
        new Part(-15, 4, 4, 0, 3, new Cube(0, 19, -13, -7, -1, 18, 6, 2)),
        new Part(15, 4, 0, 0, 1, new Cube(0, 27, -8, -7, -1, 16, 6, 2)),
        new Part(0, 4, -9, 0, 2, new Cube(0, 35, -14, -7, -1, 28, 6, 2)),
        new Part(0, 4, 9, 0, 0, new Cube(0, 43, -14, -7, -1, 28, 6, 2)));
    // FaceInfo's directions per axis, low side first.
    private static final String[][] DIRECTIONS = {
        {"west", "east"}, {"down", "up"}, {"north", "south"}};
    // CuboidFace.getU/getV: which uv slot each corner reads, minU at 0 and 1, minV at 0 and 3.
    private static final int[] U_SLOT = {0, 0, 2, 2};
    private static final int[] V_SLOT = {1, 3, 3, 1};

    private BoatShellGeometry() {}

    // The hull's elements; a chest boat's texture is 128 x 128, a boat's 128 x 64.
    static JsonArray elements(boolean chest) {
        double textureHeight = chest ? 128 : 64;
        JsonArray elements = new JsonArray();
        for (Part part : HULL) {
            elements.add(bake(part, textureHeight));
        }
        return elements;
    }

    // A JSON array of the values, rounded to six places.
    static JsonArray vector(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(Math.round(value * 1000000) / 1000000.0);
        }
        return array;
    }

    // One ModelPart.Cube: its six polygons with their vertex UVs, moved into item space.
    private static JsonObject bake(Part part, double textureHeight) {
        Cube c = part.cube();
        double x0 = c.x() - INFLATE;
        double y0 = c.y() - INFLATE;
        double z0 = c.z() - INFLATE;
        double x1 = c.x() + c.w() + INFLATE;
        double y1 = c.y() + c.h() + INFLATE;
        double z1 = c.z() + c.d() + INFLATE;
        double[] t0 = item(part, x0, y0, z0);
        double[] t1 = item(part, x1, y0, z0);
        double[] t2 = item(part, x1, y1, z0);
        double[] t3 = item(part, x0, y1, z0);
        double[] l0 = item(part, x0, y0, z1);
        double[] l1 = item(part, x1, y0, z1);
        double[] l2 = item(part, x1, y1, z1);
        double[] l3 = item(part, x0, y1, z1);
        double u0 = c.u();
        double u1 = u0 + c.d();
        double u2 = u1 + c.w();
        double u22 = u2 + c.w();
        double u3 = u2 + c.d();
        double u4 = u3 + c.w();
        double v0 = c.v();
        double v1 = v0 + c.d();
        double v2 = v1 + c.h();
        // Vertices, then the polygon's u0 v0 u1 v1, as in ModelPart.Cube.
        double[][] polygons = {
            flat(l1, l0, t0, t1, u1, v0, u2, v1),
            flat(t2, t3, l3, l2, u2, v1, u22, v0),
            flat(t0, l0, l3, t3, u0, v1, u1, v2),
            flat(t1, t0, t3, t2, u1, v1, u2, v2),
            flat(l1, t1, t2, l2, u2, v1, u3, v2),
            flat(l0, l1, l2, l3, u3, v1, u4, v2)};
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] corner : new double[][] {t0, t1, t2, t3, l0, l1, l2, l3}) {
            for (int a = 0; a < 3; a++) {
                min[a] = Math.min(min[a], corner[a]);
                max[a] = Math.max(max[a], corner[a]);
            }
        }
        JsonObject faces = new JsonObject();
        for (double[] polygon : polygons) {
            face(polygon, min, max, textureHeight, faces);
        }
        JsonObject element = new JsonObject();
        element.add("from", vector(min[0], min[1], min[2]));
        element.add("to", vector(max[0], max[1], max[2]));
        element.add("faces", faces);
        return element;
    }

    // Part space to item space: the part's pose, then (z + 8, y + 8, 8 - x).
    private static double[] item(Part part, double x, double y, double z) {
        double px = x;
        double py = y;
        double pz = z;
        for (int i = 0; i < Math.floorMod(part.xTurns(), 4); i++) {
            double ny = -pz;
            pz = py;
            py = ny;
        }
        for (int i = 0; i < Math.floorMod(part.yTurns(), 4); i++) {
            double nx = pz;
            pz = -px;
            px = nx;
        }
        px += part.x();
        py += part.y();
        pz += part.z();
        return new double[] {pz + 8, py + 8, 8 - px};
    }

    // Four corners and the polygon's UV rectangle, flattened.
    private static double[] flat(double[] a, double[] b, double[] c, double[] d, double u0,
            double v0, double u1, double v1) {
        return new double[] {a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1],
            d[2], u0, v0, u1, v1};
    }

    // Adds the element face the polygon lies on, with the uv and rotation matching its corners.
    private static void face(double[] polygon, double[] min, double[] max, double textureHeight,
            JsonObject faces) {
        // ModelPart.Polygon: vertex 0 gets (u1, v0), 1 (u0, v0), 2 (u0, v1), 3 (u1, v1).
        double[][] vertexUv = {{polygon[14], polygon[13]}, {polygon[12], polygon[13]},
            {polygon[12], polygon[15]}, {polygon[14], polygon[15]}};
        String direction = null;
        double[][] corners = null;
        for (int a = 0; a < 3 && direction == null; a++) {
            for (boolean high : new boolean[] {false, true}) {
                double plane = high ? max[a] : min[a];
                boolean on = true;
                for (int i = 0; i < 4; i++) {
                    on &= Math.abs(polygon[i * 3 + a] - plane) < EPSILON;
                }
                if (on) {
                    direction = DIRECTIONS[a][high ? 1 : 0];
                    corners = corners(direction, min, max);
                    break;
                }
            }
        }
        if (direction == null) {
            throw new IllegalStateException("Boat shell polygon off its box");
        }
        // FaceInfo corner i takes the UV of the polygon vertex at the same spot.
        double[][] uv = new double[4][];
        for (int i = 0; i < 4; i++) {
            for (int v = 0; v < 4; v++) {
                boolean same = true;
                for (int a = 0; a < 3; a++) {
                    same &= Math.abs(polygon[v * 3 + a] - corners[i][a]) < EPSILON;
                }
                if (same) {
                    uv[i] = new double[] {vertexUv[v][0] * 16 / TEXTURE_WIDTH,
                        vertexUv[v][1] * 16 / textureHeight};
                }
            }
        }
        for (int rotation = 0; rotation < 4; rotation++) {
            boolean fits = true;
            double[] set = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
            for (int i = 0; i < 4 && fits; i++) {
                int j = (i + rotation) % 4;
                fits = take(set, U_SLOT[j], uv[i][0]) && take(set, V_SLOT[j], uv[i][1]);
            }
            if (!fits) {
                continue;
            }
            JsonObject face = new JsonObject();
            face.add("uv", vector(set));
            if (rotation != 0) {
                face.addProperty("rotation", rotation * 90);
            }
            face.addProperty("texture", "#hull");
            faces.add(direction, face);
            return;
        }
        throw new IllegalStateException("Boat shell face UVs fit no rotation");
    }

    // Fills uv slot (0 minU, 1 minV, 2 maxU, 3 maxV) or checks it; false on a clash.
    private static boolean take(double[] set, int slot, double value) {
        if (Double.isNaN(set[slot])) {
            set[slot] = value;
            return true;
        }
        return Math.abs(set[slot] - value) < EPSILON;
    }

    // FaceInfo's corner order for each direction.
    private static double[][] corners(String direction, double[] n, double[] x) {
        return switch (direction) {
            case "down" -> new double[][] {{n[0], n[1], x[2]}, {n[0], n[1], n[2]},
                {x[0], n[1], n[2]}, {x[0], n[1], x[2]}};
            case "up" -> new double[][] {{n[0], x[1], n[2]}, {n[0], x[1], x[2]},
                {x[0], x[1], x[2]}, {x[0], x[1], n[2]}};
            case "north" -> new double[][] {{x[0], x[1], n[2]}, {x[0], n[1], n[2]},
                {n[0], n[1], n[2]}, {n[0], x[1], n[2]}};
            case "south" -> new double[][] {{n[0], x[1], x[2]}, {n[0], n[1], x[2]},
                {x[0], n[1], x[2]}, {x[0], x[1], x[2]}};
            case "west" -> new double[][] {{n[0], x[1], n[2]}, {n[0], n[1], n[2]},
                {n[0], n[1], x[2]}, {n[0], x[1], x[2]}};
            default -> new double[][] {{x[0], x[1], x[2]}, {x[0], n[1], x[2]},
                {x[0], n[1], n[2]}, {x[0], x[1], n[2]}};
        };
    }

    // One ModelPart.Cube: texture offset, origin and size, in px.
    private record Cube(int u, int v, double x, double y, double z, double w, double h,
            double d) {
    }

    // A ModelPart: offset, quarter turns about X then Y (hull parts are axis-aligned), one cube.
    private record Part(double x, double y, double z, int xTurns, int yTurns, Cube cube) {
    }
}
