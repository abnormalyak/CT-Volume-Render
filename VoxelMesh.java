import javafx.scene.shape.TriangleMesh;

import java.util.Arrays;

/**
 * Builds a JavaFX TriangleMesh by walking the CT volume and emitting one
 * outward-facing quad for every bone voxel face whose neighbour is empty
 * (or outside the volume). Internal faces are skipped, which keeps the
 * triangle count manageable for JavaFX 3D.
 *
 * The result is voxelated rather than smooth — marching cubes would give
 * a smoother surface but needs ~4 KB of lookup tables. This produces a
 * recognisable skull in well under a second for the CThead dataset.
 */
public class VoxelMesh {

    /**
     * @param data       cthead[z][y][x] volume
     * @param threshold  voxel value above which a voxel is considered solid (bone ≈ 300)
     * @param step       downsampling factor (1 = full res, 2 = half res in each axis, …)
     * @return           a TriangleMesh ready to drop into a MeshView
     */
    public static TriangleMesh generate(short[][][] data, int threshold, int step) {
        int zs = data.length;
        int ys = data[0].length;
        int xs = data[0][0].length;

        FloatList points = new FloatList();
        IntList   faces  = new IntList();

        int s = step;
        for (int z = 0; z < zs; z += s) {
            for (int y = 0; y < ys; y += s) {
                for (int x = 0; x < xs; x += s) {
                    if (data[z][y][x] < threshold) continue;

                    // A neighbour is "empty" if it's outside the volume or below threshold.
                    boolean px = x + s >= xs || data[z][y][x + s] < threshold;
                    boolean nx = x - s <  0  || data[z][y][x - s] < threshold;
                    boolean py = y + s >= ys || data[z][y + s][x] < threshold;
                    boolean ny = y - s <  0  || data[z][y - s][x] < threshold;
                    boolean pz = z + s >= zs || data[z + s][y][x] < threshold;
                    boolean nz = z - s <  0  || data[z - s][y][x] < threshold;

                    if (px) addQuad(points, faces, x+s, y,   z,   x+s, y+s, z,   x+s, y+s, z+s, x+s, y,   z+s);
                    if (nx) addQuad(points, faces, x,   y,   z+s, x,   y+s, z+s, x,   y+s, z,   x,   y,   z  );
                    if (py) addQuad(points, faces, x,   y+s, z,   x+s, y+s, z,   x+s, y+s, z+s, x,   y+s, z+s);
                    if (ny) addQuad(points, faces, x,   y,   z+s, x+s, y,   z+s, x+s, y,   z,   x,   y,   z  );
                    if (pz) addQuad(points, faces, x,   y,   z+s, x,   y+s, z+s, x+s, y+s, z+s, x+s, y,   z+s);
                    if (nz) addQuad(points, faces, x,   y,   z,   x+s, y,   z,   x+s, y+s, z,   x,   y+s, z  );
                }
            }
        }

        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(points.toArray());
        mesh.getTexCoords().setAll(0f, 0f);
        mesh.getFaces().setAll(faces.toArray());
        return mesh;
    }

    private static void addQuad(FloatList p, IntList f,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3,
                                float x4, float y4, float z4) {
        int base = p.size() / 3;
        p.add(x1); p.add(y1); p.add(z1);
        p.add(x2); p.add(y2); p.add(z2);
        p.add(x3); p.add(y3); p.add(z3);
        p.add(x4); p.add(y4); p.add(z4);
        // Two triangles: (0,1,2) and (0,2,3). JavaFX face format is
        // (vertexIdx, texCoordIdx) per corner — we only have one tex coord.
        f.add(base);     f.add(0);
        f.add(base + 1); f.add(0);
        f.add(base + 2); f.add(0);
        f.add(base);     f.add(0);
        f.add(base + 2); f.add(0);
        f.add(base + 3); f.add(0);
    }

    // Primitive growable arrays — ArrayList<Float>/<Integer> would box each value
    // hundreds of thousands of times, which is both slow and memory-hungry.

    private static class FloatList {
        private float[] data = new float[4096];
        private int size = 0;
        void add(float v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }
        int   size()    { return size; }
        float[] toArray() { return Arrays.copyOf(data, size); }
    }

    private static class IntList {
        private int[] data = new int[4096];
        private int size = 0;
        void add(int v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }
        int[] toArray() { return Arrays.copyOf(data, size); }
    }
}
