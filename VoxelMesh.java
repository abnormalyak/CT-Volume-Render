import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Builds a JavaFX TriangleMesh by emitting one outward-facing quad per bone
 * voxel face whose neighbour is empty. Shared vertices between adjacent quads
 * are deduplicated and each unique vertex gets a normal sampled from the
 * (negated) volume gradient, so JavaFX renders the result with proper
 * Gouraud shading instead of facets.
 */
public class VoxelMesh {

    public static TriangleMesh generate(short[][][] data,
                                        float[][][] gradX, float[][][] gradY, float[][][] gradZ,
                                        int threshold, int step) {
        int zs = data.length;
        int ys = data[0].length;
        int xs = data[0][0].length;

        FloatList points  = new FloatList();
        FloatList normals = new FloatList();
        IntList   faces   = new IntList();
        HashMap<Long, Integer> vertexIndex = new HashMap<>();

        int s = step;
        for (int z = 0; z < zs; z += s) {
            for (int y = 0; y < ys; y += s) {
                for (int x = 0; x < xs; x += s) {
                    if (data[z][y][x] < threshold) continue;

                    boolean px = x + s >= xs || data[z][y][x + s] < threshold;
                    boolean nx = x - s <  0  || data[z][y][x - s] < threshold;
                    boolean py = y + s >= ys || data[z][y + s][x] < threshold;
                    boolean ny = y - s <  0  || data[z][y - s][x] < threshold;
                    boolean pz = z + s >= zs || data[z + s][y][x] < threshold;
                    boolean nz = z - s <  0  || data[z - s][y][x] < threshold;

                    Ctx ctx = new Ctx(points, normals, faces, vertexIndex, gradX, gradY, gradZ, xs, ys, zs);

                    if (px) addQuad(ctx, x+s, y,   z,   x+s, y+s, z,   x+s, y+s, z+s, x+s, y,   z+s);
                    if (nx) addQuad(ctx, x,   y,   z+s, x,   y+s, z+s, x,   y+s, z,   x,   y,   z  );
                    if (py) addQuad(ctx, x,   y+s, z,   x+s, y+s, z,   x+s, y+s, z+s, x,   y+s, z+s);
                    if (ny) addQuad(ctx, x,   y,   z+s, x+s, y,   z+s, x+s, y,   z,   x,   y,   z  );
                    if (pz) addQuad(ctx, x,   y,   z+s, x,   y+s, z+s, x+s, y+s, z+s, x+s, y,   z+s);
                    if (nz) addQuad(ctx, x,   y,   z,   x+s, y,   z,   x+s, y+s, z,   x,   y+s, z  );
                }
            }
        }

        TriangleMesh mesh = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD);
        mesh.getPoints().setAll(points.toArray());
        mesh.getNormals().setAll(normals.toArray());
        mesh.getTexCoords().setAll(0f, 0f);
        mesh.getFaces().setAll(faces.toArray());
        return mesh;
    }

    // Bundle of references threaded through quad/vertex helpers so the
    // call-sites stay readable. Internal only.
    private static class Ctx {
        final FloatList points, normals;
        final IntList   faces;
        final HashMap<Long, Integer> vertexIndex;
        final float[][][] gradX, gradY, gradZ;
        final int xs, ys, zs;
        Ctx(FloatList p, FloatList n, IntList f, HashMap<Long, Integer> vi,
            float[][][] gx, float[][][] gy, float[][][] gz, int xs, int ys, int zs) {
            this.points=p; this.normals=n; this.faces=f; this.vertexIndex=vi;
            this.gradX=gx; this.gradY=gy; this.gradZ=gz;
            this.xs=xs; this.ys=ys; this.zs=zs;
        }
    }

    private static void addQuad(Ctx c,
                                int x1, int y1, int z1,
                                int x2, int y2, int z2,
                                int x3, int y3, int z3,
                                int x4, int y4, int z4) {
        int v1 = getOrCreate(c, x1, y1, z1);
        int v2 = getOrCreate(c, x2, y2, z2);
        int v3 = getOrCreate(c, x3, y3, z3);
        int v4 = getOrCreate(c, x4, y4, z4);

        // POINT_NORMAL_TEXCOORD face entries: vertexIdx, normalIdx, texCoordIdx
        // per triangle corner. We use one normal per vertex (smooth shading).
        c.faces.add(v1); c.faces.add(v1); c.faces.add(0);
        c.faces.add(v2); c.faces.add(v2); c.faces.add(0);
        c.faces.add(v3); c.faces.add(v3); c.faces.add(0);
        c.faces.add(v1); c.faces.add(v1); c.faces.add(0);
        c.faces.add(v3); c.faces.add(v3); c.faces.add(0);
        c.faces.add(v4); c.faces.add(v4); c.faces.add(0);
    }

    private static int getOrCreate(Ctx c, int x, int y, int z) {
        long key = ((long)x & 0xFFFL) | (((long)y & 0xFFFL) << 12) | (((long)z & 0xFFFL) << 24);
        Integer cached = c.vertexIndex.get(key);
        if (cached != null) return cached;

        int idx = c.points.size() / 3;
        c.points.add(x); c.points.add(y); c.points.add(z);

        // Normal = -gradient (gradient points into bone, outward normal points away).
        // Sample at the nearest in-bounds voxel for boundary vertices.
        int cx = Math.min(c.xs - 1, Math.max(0, x));
        int cy = Math.min(c.ys - 1, Math.max(0, y));
        int cz = Math.min(c.zs - 1, Math.max(0, z));
        float nx = -c.gradX[cz][cy][cx];
        float ny = -c.gradY[cz][cy][cx];
        float nz = -c.gradZ[cz][cy][cx];
        float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (len > 0) { nx /= len; ny /= len; nz /= len; }
        c.normals.add(nx); c.normals.add(ny); c.normals.add(nz);

        c.vertexIndex.put(key, idx);
        return idx;
    }

    private static class FloatList {
        private float[] data = new float[4096];
        private int size = 0;
        void add(float v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }
        int   size()      { return size; }
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
