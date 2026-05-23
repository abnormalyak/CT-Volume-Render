import java.io.FileInputStream;
import java.io.FileNotFoundException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.PixelWriter;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import java.io.*;
import java.util.ArrayList;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

public class Example extends Application {
    short cthead[][][];
    short min, max;
    int CT_x_axis = 256;
    int CT_y_axis = 256;
    int CT_z_axis = 113;

    float[][][] gradX, gradY, gradZ;

    private final ExecutorService sliceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ct-slice");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService vrExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ct-vr");
        t.setDaemon(true);
        return t;
    });

    private volatile int vrGeneration = 0;

    // Separate executor + generation for the rotatable 3D raycaster window
    private final ExecutorService vr3DExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ct-3d");
        t.setDaemon(true);
        return t;
    });
    private volatile int    vr3DGeneration = 0;
    private volatile double rot3DPitch = -Math.PI / 2; // start facing the front of the head
    private volatile double rot3DYaw   = 0;

    // Crosshair state — tracks the current 3-D cursor position in CT space.
    // crosshairX = sagittal (Side) slider value
    // crosshairY = coronal  (Front) slider value
    // crosshairZ = axial    (Top)   slider value
    private volatile int     crosshairX = 0, crosshairY = 0, crosshairZ = 0;
    private volatile boolean crosshairEnabled = false;

    private static final int CROSSHAIR_COLOR = 0xFF00E5FF; // bright cyan
    private static final int CROSSHAIR_GAP   = 10;         // pixel gap around centre intersection

    private static final String C_BG     = "#0f172a";
    private static final String C_PANEL  = "#1e293b";
    private static final String C_BORDER = "#334155";
    private static final String C_ACCENT = "#3b82f6";
    private static final String C_TEXT   = "#f1f5f9";
    private static final String C_MUTED  = "#94a3b8";
    private static final String C_GREEN  = "#22c55e";

    @Override
    public void start(Stage stage) throws FileNotFoundException, IOException {
        stage.setTitle("CT Scan Visualisation Tool");

        ReadData();

        WritableImage top_image   = new WritableImage(CT_x_axis, CT_y_axis);
        WritableImage front_image = new WritableImage(CT_x_axis, CT_z_axis);
        WritableImage side_image  = new WritableImage(CT_y_axis, CT_z_axis);

        ImageView TopView   = new ImageView(top_image);
        ImageView FrontView = new ImageView(front_image);
        ImageView SideView  = new ImageView(side_image);

        // Scale each image up to fill more space; preserve aspect ratio
        for (ImageView v : new ImageView[]{TopView, FrontView, SideView}) {
            v.setFitWidth(300);
            v.setPreserveRatio(true);
        }

        ArrayList<WritableImage> images = new ArrayList<>();
        images.add(top_image);
        images.add(front_image);
        images.add(side_image);

        // ── Sliders ──────────────────────────────────────────────────────────
        Slider Top_slider      = makeSlider(0, CT_z_axis - 1, 20);
        Slider Front_slider    = makeSlider(0, CT_y_axis - 1, 50);
        Slider Side_slider     = makeSlider(0, CT_x_axis - 1, 50);
        Slider Skin_slider     = makeSlider(0, 1, 0.25);
        Slider lighting_slider = makeSlider(0, 255, 50);

        Label topVal      = valueLabel("Slice: 0");
        Label frontVal    = valueLabel("Slice: 0");
        Label sideVal     = valueLabel("Slice: 0");
        Label skinVal     = valueLabel("Opacity: 0.00");
        Label lightingVal = valueLabel("Position: 0");

        // ── Slice listeners ───────────────────────────────────────────────────
        // Each updates its crosshair coordinate. When crosshair is on, all three
        // views redraw so every view's crosshair lines stay in sync.
        Top_slider.valueProperty().addListener((obs, o, n) -> {
            int z = n.intValue();
            topVal.setText("Slice: " + z);
            crosshairZ = z;
            sliceExecutor.submit(() -> drawTopImage(top_image, z));
            if (crosshairEnabled) {
                int y = crosshairY, x = crosshairX;
                sliceExecutor.submit(() -> drawFrontImage(front_image, y));
                sliceExecutor.submit(() -> drawSideImage(side_image, x));
            }
        });

        Front_slider.valueProperty().addListener((obs, o, n) -> {
            int y = n.intValue();
            frontVal.setText("Slice: " + y);
            crosshairY = y;
            sliceExecutor.submit(() -> drawFrontImage(front_image, y));
            if (crosshairEnabled) {
                int z = crosshairZ, x = crosshairX;
                sliceExecutor.submit(() -> drawTopImage(top_image, z));
                sliceExecutor.submit(() -> drawSideImage(side_image, x));
            }
        });

        Side_slider.valueProperty().addListener((obs, o, n) -> {
            int x = n.intValue();
            sideVal.setText("Slice: " + x);
            crosshairX = x;
            sliceExecutor.submit(() -> drawSideImage(side_image, x));
            if (crosshairEnabled) {
                int z = crosshairZ, y = crosshairY;
                sliceExecutor.submit(() -> drawTopImage(top_image, z));
                sliceExecutor.submit(() -> drawFrontImage(front_image, y));
            }
        });

        // ── VR / lighting listeners ───────────────────────────────────────────
        Skin_slider.valueProperty().addListener((obs, o, n) -> {
            double v = n.doubleValue();
            skinVal.setText(String.format("Opacity: %.2f", v));
            int gen = ++vrGeneration;
            vrExecutor.submit(() -> volumeRenderAll(images, v, gen));
        });

        lighting_slider.valueProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            lightingVal.setText("Position: " + i);
            int gen = ++vrGeneration;
            vrExecutor.submit(() -> lightingAll(images, new CustomTriple(i, 0.0, 0.0), gen));
        });

        // ── Initial render ───────────────────────────────────────────────────
        sliceExecutor.submit(() -> {
            drawTopImage(top_image, 0);
            drawFrontImage(front_image, 0);
            drawSideImage(side_image, 0);
        });

        // ── Crosshair mouse handlers ──────────────────────────────────────────
        // Axial view: click/drag updates X (→ Side_slider) and Y (→ Front_slider)
        javafx.event.EventHandler<MouseEvent> topCrosshair = e -> {
            if (!crosshairEnabled) return;
            int ix = toImageCoord(e.getX(), TopView.getBoundsInLocal().getWidth(),  CT_x_axis);
            int iy = toImageCoord(e.getY(), TopView.getBoundsInLocal().getHeight(), CT_y_axis);
            crosshairX = ix; crosshairY = iy;
            Side_slider.setValue(ix);
            Front_slider.setValue(iy);
            int z = crosshairZ;
            sliceExecutor.submit(() -> drawTopImage(top_image, z));
        };
        TopView.setOnMousePressed(topCrosshair);
        TopView.setOnMouseDragged(topCrosshair);

        // Coronal view: click/drag updates X (→ Side_slider) and Z (→ Top_slider)
        javafx.event.EventHandler<MouseEvent> frontCrosshair = e -> {
            if (!crosshairEnabled) return;
            int ix = toImageCoord(e.getX(), FrontView.getBoundsInLocal().getWidth(),  CT_x_axis);
            int iz = toImageCoord(e.getY(), FrontView.getBoundsInLocal().getHeight(), CT_z_axis);
            crosshairX = ix; crosshairZ = iz;
            Side_slider.setValue(ix);
            Top_slider.setValue(iz);
            int y = crosshairY;
            sliceExecutor.submit(() -> drawFrontImage(front_image, y));
        };
        FrontView.setOnMousePressed(frontCrosshair);
        FrontView.setOnMouseDragged(frontCrosshair);

        // Sagittal view: click/drag updates Y (→ Front_slider) and Z (→ Top_slider)
        javafx.event.EventHandler<MouseEvent> sideCrosshair = e -> {
            if (!crosshairEnabled) return;
            int iy = toImageCoord(e.getX(), SideView.getBoundsInLocal().getWidth(),  CT_y_axis);
            int iz = toImageCoord(e.getY(), SideView.getBoundsInLocal().getHeight(), CT_z_axis);
            crosshairY = iy; crosshairZ = iz;
            Front_slider.setValue(iy);
            Top_slider.setValue(iz);
            int x = crosshairX;
            sliceExecutor.submit(() -> drawSideImage(side_image, x));
        };
        SideView.setOnMousePressed(sideCrosshair);
        SideView.setOnMouseDragged(sideCrosshair);

        // ── Image panels ─────────────────────────────────────────────────────
        HBox imageRow = new HBox(12,
            imageCard("Axial View",    "Top-down (Z-axis)",    TopView),
            imageCard("Coronal View",  "Front-back (Y-axis)",  FrontView),
            imageCard("Sagittal View", "Left-right (X-axis)",  SideView)
        );
        imageRow.setAlignment(Pos.CENTER);
        imageRow.setPadding(new Insets(16));

        // ── Buttons ──────────────────────────────────────────────────────────
        Button volRend_button = styledButton("Volume Render", C_ACCENT);
        volRend_button.setOnAction(e -> {
            double v = Skin_slider.getValue();
            int gen = ++vrGeneration;
            vrExecutor.submit(() -> volumeRenderAll(images, v, gen));
        });

        Button lightingButton = styledButton("Apply Lighting", C_ACCENT);
        lightingButton.setOnAction(e -> {
            CustomTriple light = new CustomTriple(lighting_slider.getValue(), 0, 0);
            int gen = ++vrGeneration;
            vrExecutor.submit(() -> lightingAll(images, light, gen));
        });

        Button crosshairButton = styledButton("Crosshair: OFF", C_ACCENT);
        crosshairButton.setOnAction(e -> {
            crosshairEnabled = !crosshairEnabled;
            if (crosshairEnabled) {
                crosshairButton.setText("Crosshair: ON");
                setButtonColor(crosshairButton, C_GREEN);
            } else {
                crosshairButton.setText("Crosshair: OFF");
                setButtonColor(crosshairButton, C_ACCENT);
            }
            int z = crosshairZ, y = crosshairY, x = crosshairX;
            sliceExecutor.submit(() -> {
                drawTopImage(top_image, z);
                drawFrontImage(front_image, y);
                drawSideImage(side_image, x);
            });
        });

        Button skull3DButton = styledButton("Open 3D Skull View", "#8b5cf6");
        skull3DButton.setOnAction(e -> show3DView());

        // ── Controls panel ───────────────────────────────────────────────────
        Label controlsTitle = new Label("Controls");
        controlsTitle.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:15px;-fx-font-weight:bold;");

        VBox controlsPanel = new VBox(10,
            controlsTitle,
            crosshairButton,
            skull3DButton,
            separator(),
            sliderCard("Axial Slice (Z)",   Top_slider,      topVal,
                "Scroll through horizontal cross-sections from the top of the skull down to the neck (0 – 112 slices)."),
            sliderCard("Coronal Slice (Y)",  Front_slider,    frontVal,
                "Scroll through front-to-back cross-sections of the head — from the nose to the back of the skull (0 – 255)."),
            sliderCard("Sagittal Slice (X)", Side_slider,     sideVal,
                "Scroll through left-to-right cross-sections of the head — from one ear to the other (0 – 255)."),
            separator(),
            volRend_button,
            sliderCard("Skin Opacity",       Skin_slider,     skinVal,
                "Controls how opaque skin tissue appears in volume rendering. 0 = fully transparent (bone only), 1 = fully visible skin."),
            separator(),
            lightingButton,
            sliderCard("Light Position",     lighting_slider, lightingVal,
                "Moves the point light source along the X-axis. Adjust to change the direction of diffuse shading on bone surfaces.")
        );
        controlsPanel.setPadding(new Insets(16));
        controlsPanel.setPrefWidth(270);
        controlsPanel.setStyle("-fx-background-color:" + C_PANEL + ";");

        ScrollPane controlsScroll = new ScrollPane(controlsPanel);
        controlsScroll.setFitToWidth(true);
        controlsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        controlsScroll.setStyle("-fx-background-color:" + C_PANEL + ";-fx-border-color:transparent;");

        // ── Header ───────────────────────────────────────────────────────────
        Label appTitle = new Label("CT Scan Visualisation Tool");
        appTitle.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:18px;-fx-font-weight:bold;");
        Label subtitle = new Label("Volume Rendering & Slice Viewer");
        subtitle.setStyle("-fx-text-fill:" + C_MUTED + ";-fx-font-size:12px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, appTitle, subtitle, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 20, 14, 20));
        header.setStyle("-fx-background-color:#1e293b;-fx-border-color:" + C_BORDER + ";-fx-border-width:0 0 1 0;");

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(imageRow);
        root.setRight(controlsScroll);
        root.setStyle("-fx-background-color:" + C_BG + ";");

        Scene scene = new Scene(root, 1300, 800);
        stage.setScene(scene);
        stage.show();
    }

    // ── 3D skull viewer (raycasting with rotation) ───────────────────────────

    private void show3DView() {
        Stage stage3D = new Stage();
        stage3D.setTitle("3D Skull View — Raycaster");

        int W = 520, H = 520;
        WritableImage image = new WritableImage(W, H);
        ImageView view = new ImageView(image);

        Label hint = new Label("Drag to rotate");
        hint.setStyle(
            "-fx-text-fill:" + C_MUTED + ";-fx-font-size:11px;" +
            "-fx-background-color:rgba(15,23,42,0.75);" +
            "-fx-padding:6 12;-fx-background-radius:6;"
        );
        StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
        StackPane.setMargin(hint, new Insets(0, 0, 14, 0));

        StackPane root = new StackPane(view, hint);
        root.setStyle("-fx-background-color:" + C_BG + ";");

        // Mouse drag → rotate (pitch/yaw)
        final double[] anchor = new double[2];
        final double[] startRot = new double[2];
        view.setOnMousePressed(e -> {
            anchor[0]   = e.getSceneX();
            anchor[1]   = e.getSceneY();
            startRot[0] = rot3DPitch;
            startRot[1] = rot3DYaw;
        });
        view.setOnMouseDragged(e -> {
            rot3DPitch = startRot[0] - (e.getSceneY() - anchor[1]) * 0.01;
            rot3DYaw   = startRot[1] + (e.getSceneX() - anchor[0]) * 0.01;
            int gen = ++vr3DGeneration;
            double p = rot3DPitch, y = rot3DYaw;
            // Render at half resolution during drag — pixels are filled into 2x2
            // blocks, so each render does ~1/4 of the work and feels fluid.
            vr3DExecutor.submit(() -> render3D(image, p, y, gen, 2));
        });
        view.setOnMouseReleased(e -> {
            // Full-resolution render once dragging stops
            int gen = ++vr3DGeneration;
            double p = rot3DPitch, y = rot3DYaw;
            vr3DExecutor.submit(() -> render3D(image, p, y, gen, 1));
        });

        stage3D.setScene(new Scene(root, W, H));
        stage3D.show();

        int gen = ++vr3DGeneration;
        double p = rot3DPitch, y = rot3DYaw;
        vr3DExecutor.submit(() -> render3D(image, p, y, gen, 1));
    }

    // Per-pixel raycaster with rotation. Same diffuse-lighting model as the 2-D
    // lightingTopDown etc., but the ray direction is rotated by (pitch, yaw)
    // before walking the volume, giving an arbitrary 3-D view that the user
    // can drag to spin.
    //
    // Performance tricks:
    //   - downsample > 1 computes 1 ray per (ds × ds) block and fills the block
    //     with that colour, so dragging renders ~1/(ds²) of the pixels.
    //   - Ray vs volume-AABB intersection (slab method) finds the t-range that
    //     actually hits the volume, so we don't walk through empty space.
    //   - Ray step size is 1.5 voxels rather than 1.0 — minor quality loss,
    //     ~33% fewer samples per ray.
    private void render3D(WritableImage image, double pitch, double yaw, int gen, int downsample) {
        if (vr3DGeneration != gen) return; // bail before allocating
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];

        double cosP = Math.cos(pitch), sinP = Math.sin(pitch);
        double cosY = Math.cos(yaw),   sinY = Math.sin(yaw);

        final double vcx = CT_x_axis / 2.0;
        final double vcy = CT_y_axis / 2.0;
        final double vcz = CT_z_axis / 2.0;

        // Ray direction in volume space — constant for all rays.
        // Nudged away from exact zero so the slab method never hits 0×∞ = NaN.
        double rx = -sinY * cosP;
        double ry =  sinP;
        double rz =  cosY * cosP;
        if (Math.abs(rx) < 1e-9) rx = rx >= 0 ? 1e-9 : -1e-9;
        if (Math.abs(ry) < 1e-9) ry = ry >= 0 ? 1e-9 : -1e-9;
        if (Math.abs(rz) < 1e-9) rz = rz >= 0 ? 1e-9 : -1e-9;
        final double rdx = rx, rdy = ry, rdz = rz;
        final double invRdx = 1.0 / rdx, invRdy = 1.0 / rdy, invRdz = 1.0 / rdz;

        // Light from camera (=-ray) so the visible side of the skull is always lit
        final double lx = -rdx, ly = -rdy, lz = -rdz;

        final double STEP = 1.5;
        final int ds = downsample;
        final double cosPf = cosP, sinPf = sinP, cosYf = cosY, sinYf = sinY;
        final int wF = w, hF = h;

        int yBlocks = (hF + ds - 1) / ds;
        IntStream.range(0, yBlocks).parallel().forEach(yBlock -> {
            if (vr3DGeneration != gen) return;
            int py0 = yBlock * ds;
            double camY = py0 - hF / 2.0;
            double ypCamY =  cosPf * camY;
            double zpCamY = -sinPf * camY;

            for (int px0 = 0; px0 < wF; px0 += ds) {
                double camX = px0 - wF / 2.0;

                // Ray origin in volume space (camera's view plane at z=0).
                double ox = cosYf * camX - sinYf * zpCamY + vcx;
                double oy = ypCamY + vcy;
                double oz = sinYf * camX + cosYf * zpCamY + vcz;

                // Slab method: ray vs AABB [0,CT_x_axis]×[0,CT_y_axis]×[0,CT_z_axis]
                double tx1 = (0          - ox) * invRdx;
                double tx2 = (CT_x_axis - ox) * invRdx;
                if (tx1 > tx2) { double t = tx1; tx1 = tx2; tx2 = t; }
                double ty1 = (0          - oy) * invRdy;
                double ty2 = (CT_y_axis - oy) * invRdy;
                if (ty1 > ty2) { double t = ty1; ty1 = ty2; ty2 = t; }
                double tz1 = (0          - oz) * invRdz;
                double tz2 = (CT_z_axis - oz) * invRdz;
                if (tz1 > tz2) { double t = tz1; tz1 = tz2; tz2 = t; }

                double tEnter = Math.max(Math.max(tx1, ty1), tz1);
                double tExit  = Math.min(Math.min(tx2, ty2), tz2);

                double color = 0;
                if (tEnter <= tExit && tExit >= 0) {
                    double t = Math.max(tEnter, 0);
                    while (t <= tExit) {
                        int sx = (int) (ox + rdx * t);
                        int sy = (int) (oy + rdy * t);
                        int sz = (int) (oz + rdz * t);
                        if (sx >= 0 && sx < CT_x_axis &&
                            sy >= 0 && sy < CT_y_axis &&
                            sz >= 0 && sz < CT_z_axis &&
                            cthead[sz][sy][sx] > 300) {
                            double nx = -gradX[sz][sy][sx];
                            double ny = -gradY[sz][sy][sx];
                            double nz = -gradZ[sz][sy][sx];
                            double nLen = Math.sqrt(nx*nx + ny*ny + nz*nz);
                            if (nLen > 0) { nx /= nLen; ny /= nLen; nz /= nLen; }
                            color = Math.max(0, lx*nx + ly*ny + lz*nz);
                            break;
                        }
                        t += STEP;
                    }
                }

                int ci = (int) (color * 255);
                int argb = (0xFF << 24) | (ci << 16) | (ci << 8) | ci;
                // Fill the ds × ds block with this colour
                for (int dy = 0; dy < ds && py0 + dy < hF; dy++) {
                    int rowOff = (py0 + dy) * wF;
                    for (int dx = 0; dx < ds && px0 + dx < wF; dx++) {
                        pixels[rowOff + px0 + dx] = argb;
                    }
                }
            }
        });

        if (vr3DGeneration == gen) {
            Platform.runLater(() ->
                image.getPixelWriter().setPixels(0, 0, w, h,
                    PixelFormat.getIntArgbInstance(), pixels, 0, w));
        }
    }

    // ── Crosshair helpers ─────────────────────────────────────────────────────

    // Maps a screen-space coordinate to a CT array index, clamped to valid range
    private int toImageCoord(double screenPos, double viewSize, int ctSize) {
        return Math.max(0, Math.min(ctSize - 1, (int)(screenPos * ctSize / viewSize)));
    }

    // Draws a cyan crosshair into a pixel buffer with a small gap at the intersection
    private void drawCrosshair(int[] pixels, int w, int h, int cx, int cy) {
        cx = Math.max(0, Math.min(w - 1, cx));
        cy = Math.max(0, Math.min(h - 1, cy));
        for (int y = 0; y < h; y++)
            if (Math.abs(y - cy) > CROSSHAIR_GAP) pixels[y * w + cx] = CROSSHAIR_COLOR;
        for (int x = 0; x < w; x++)
            if (Math.abs(x - cx) > CROSSHAIR_GAP) pixels[cy * w + x] = CROSSHAIR_COLOR;
    }

    // ── Pixel helpers ─────────────────────────────────────────────────────────

    private static int toArgb(double r, double g, double b) {
        int ri = Math.min(255, (int)(r * 255));
        int gi = Math.min(255, (int)(g * 255));
        int bi = Math.min(255, (int)(b * 255));
        return (0xFF << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static void writePixels(WritableImage image, int[] pixels) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        Platform.runLater(() ->
            image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w)
        );
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    public void ReadData() throws IOException {
        File file = new File("CThead");
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
        int i, j, k;
        min = Short.MAX_VALUE; max = Short.MIN_VALUE;
        short read;
        int b1, b2;
        cthead = new short[CT_z_axis][CT_y_axis][CT_x_axis];
        for (k = 0; k < CT_z_axis; k++) {
            for (j = 0; j < CT_y_axis; j++) {
                for (i = 0; i < CT_x_axis; i++) {
                    b1 = ((int) in.readByte()) & 0xff;
                    b2 = ((int) in.readByte()) & 0xff;
                    read = (short) ((b2 << 8) | b1);
                    if (read < min) min = read;
                    if (read > max) max = read;
                    cthead[k][j][i] = read;
                }
            }
        }
        System.out.println(min + " " + max);
        precomputeGradients();
    }

    private void precomputeGradients() {
        gradX = new float[CT_z_axis][CT_y_axis][CT_x_axis];
        gradY = new float[CT_z_axis][CT_y_axis][CT_x_axis];
        gradZ = new float[CT_z_axis][CT_y_axis][CT_x_axis];
        for (int z = 0; z < CT_z_axis; z++) {
            for (int y = 0; y < CT_y_axis; y++) {
                for (int x = 0; x < CT_x_axis; x++) {
                    gradX[z][y][x] = x == 0            ? cthead[z][y][x+1] - cthead[z][y][x]
                                   : x < CT_x_axis - 1 ? cthead[z][y][x+1] - cthead[z][y][x-1]
                                   :                     cthead[z][y][x]   - cthead[z][y][x-1];
                    gradY[z][y][x] = y == 0            ? cthead[z][y+1][x] - cthead[z][y][x]
                                   : y < CT_y_axis - 1 ? cthead[z][y+1][x] - cthead[z][y-1][x]
                                   :                     cthead[z][y][x]   - cthead[z][y-1][x];
                    gradZ[z][y][x] = z == 0            ? cthead[z+1][y][x] - cthead[z][y][x]
                                   : z < CT_z_axis - 1 ? cthead[z+1][y][x] - cthead[z-1][y][x]
                                   :                     cthead[z][y][x]   - cthead[z-1][y][x];
                }
            }
        }
    }

    // ── Slice rendering ───────────────────────────────────────────────────────

    public void TopDownSlice76(WritableImage image) { drawTopImage(image, 76); }

    // Axial view: image_x = CT_x, image_y = CT_y  →  crosshair at (crosshairX, crosshairY)
    public void drawTopImage(WritableImage image, int slice) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        float range = max - min;
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int ci = (int)((cthead[slice][y][x] - min) / range * 255);
                pixels[y * w + x] = (0xFF << 24) | (ci << 16) | (ci << 8) | ci;
            }
        });
        if (crosshairEnabled) drawCrosshair(pixels, w, h, crosshairX, crosshairY);
        writePixels(image, pixels);
    }

    // Coronal view: image_x = CT_x, image_y = CT_z  →  crosshair at (crosshairX, crosshairZ)
    public void drawFrontImage(WritableImage image, int slice) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        float range = max - min;
        IntStream.range(0, h).parallel().forEach(z -> {
            for (int x = 0; x < w; x++) {
                int ci = (int)((cthead[z][slice][x] - min) / range * 255);
                pixels[z * w + x] = (0xFF << 24) | (ci << 16) | (ci << 8) | ci;
            }
        });
        if (crosshairEnabled) drawCrosshair(pixels, w, h, crosshairX, crosshairZ);
        writePixels(image, pixels);
    }

    // Sagittal view: image_x = CT_y, image_y = CT_z  →  crosshair at (crosshairY, crosshairZ)
    public void drawSideImage(WritableImage image, int slice) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        float range = max - min;
        IntStream.range(0, h).parallel().forEach(z -> {
            for (int y = 0; y < w; y++) {
                int ci = (int)((cthead[z][y][slice] - min) / range * 255);
                pixels[z * w + y] = (0xFF << 24) | (ci << 16) | (ci << 8) | ci;
            }
        });
        if (crosshairEnabled) drawCrosshair(pixels, w, h, crosshairY, crosshairZ);
        writePixels(image, pixels);
    }

    // ── Volume rendering ──────────────────────────────────────────────────────

    public void volumeRenderAll(ArrayList<WritableImage> images, double skinOpacity, int gen) {
        volumeRenderTopDown(images.get(0), skinOpacity, gen);
        volumeRenderFrontBack(images.get(1), skinOpacity, gen);
        volumeRenderSide(images.get(2), skinOpacity, gen);
    }

    public void volumeRenderTopDown(WritableImage image, double skinOpacity, int gen) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        IntStream.range(0, h).parallel().forEach(y -> {
            if (vrGeneration != gen) return;
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0, transparency = 1.0;
                for (int z = 0; z < CT_z_axis; z++) {
                    short datum = cthead[z][y][x];
                    double er, eg, eb, ea;
                    if      (datum < -300)                continue;
                    else if (datum >= 50 && datum <= 299) continue;
                    else if (datum <= 49) { er = 1.0; eg = 0.79; eb = 0.6; ea = skinOpacity; }
                    else                  { er = 1.0; eg = 1.0;  eb = 1.0; ea = 0.8; }
                    r += er * ea * transparency;
                    g += eg * ea * transparency;
                    b += eb * ea * transparency;
                    transparency *= (1.0 - ea);
                    if (transparency < 0.01) break;
                }
                pixels[y * w + x] = toArgb(Math.min(r, 1), Math.min(g, 1), Math.min(b, 1));
            }
        });
        if (vrGeneration == gen) writePixels(image, pixels);
    }

    public void volumeRenderFrontBack(WritableImage image, double skinOpacity, int gen) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        IntStream.range(0, h).parallel().forEach(z -> {
            if (vrGeneration != gen) return;
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0, transparency = 1.0;
                for (int y = 0; y < CT_y_axis - 1; y++) {
                    short datum = cthead[z][y][x];
                    double er, eg, eb, ea;
                    if      (datum < -300)                continue;
                    else if (datum >= 50 && datum <= 299) continue;
                    else if (datum <= 49) { er = 1.0; eg = 0.79; eb = 0.6; ea = skinOpacity; }
                    else                  { er = 1.0; eg = 1.0;  eb = 1.0; ea = 0.8; }
                    r += er * ea * transparency;
                    g += eg * ea * transparency;
                    b += eb * ea * transparency;
                    transparency *= (1.0 - ea);
                    if (transparency < 0.01) break;
                }
                pixels[z * w + x] = toArgb(Math.min(r, 1), Math.min(g, 1), Math.min(b, 1));
            }
        });
        if (vrGeneration == gen) writePixels(image, pixels);
    }

    public void volumeRenderSide(WritableImage image, double skinOpacity, int gen) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        IntStream.range(0, h).parallel().forEach(z -> {
            if (vrGeneration != gen) return;
            for (int y = 0; y < w; y++) {
                double r = 0, g = 0, b = 0, transparency = 1.0;
                for (int x = 0; x < CT_x_axis - 1; x++) {
                    short datum = cthead[z][y][x];
                    double er, eg, eb, ea;
                    if      (datum < -300)                continue;
                    else if (datum >= 50 && datum <= 299) continue;
                    else if (datum <= 49) { er = 1.0; eg = 0.79; eb = 0.6; ea = skinOpacity; }
                    else                  { er = 1.0; eg = 1.0;  eb = 1.0; ea = 0.8; }
                    r += er * ea * transparency;
                    g += eg * ea * transparency;
                    b += eb * ea * transparency;
                    transparency *= (1.0 - ea);
                    if (transparency < 0.01) break;
                }
                pixels[z * w + y] = toArgb(Math.min(r, 1), Math.min(g, 1), Math.min(b, 1));
            }
        });
        if (vrGeneration == gen) writePixels(image, pixels);
    }

    // ── Lighting ──────────────────────────────────────────────────────────────

    public void lightingAll(ArrayList<WritableImage> images, CustomTriple pointLight, int gen) {
        CustomTriple pointLightSide  = new CustomTriple(pointLight.getY(), pointLight.getX(), pointLight.getZ());
        CustomTriple pointLightFront = new CustomTriple(pointLight.getZ(), pointLight.getY(), pointLight.getX());
        lightingTopDown(images.get(0), pointLight, gen);
        lightingFrontBack(images.get(1), pointLightFront, gen);
        lightingSide(images.get(2), pointLightSide, gen);
    }

    public void lightingTopDown(WritableImage image, CustomTriple pointLight, int gen) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        double lx = pointLight.getX(), ly = pointLight.getY(), lz = pointLight.getZ();
        IntStream.range(0, h).parallel().forEach(y -> {
            if (vrGeneration != gen) return;
            for (int x = 0; x < w; x++) {
                double cr = 0, cg = 0, cb = 0;
                for (int z = 0; z < CT_z_axis; z++) {
                    if (cthead[z][y][x] > 300) {
                        double dx = lx-x, dy = ly-y, dz = lz-z;
                        double dLen = Math.sqrt(dx*dx+dy*dy+dz*dz);
                        if (dLen > 0) { dx/=dLen; dy/=dLen; dz/=dLen; }
                        double nx = gradX[z][y][x], ny = gradY[z][y][x], nz = gradZ[z][y][x];
                        double nLen = Math.sqrt(nx*nx+ny*ny+nz*nz);
                        if (nLen > 0) { nx/=nLen; ny/=nLen; nz/=nLen; }
                        double cosTheta = Math.max(0, dx*nx+dy*ny+dz*nz);
                        cr = cosTheta; cg = cosTheta; cb = cosTheta;
                    }
                }
                pixels[y * w + x] = toArgb(cr, cg, cb);
            }
        });
        if (vrGeneration == gen) writePixels(image, pixels);
    }

    public void lightingFrontBack(WritableImage image, CustomTriple pointLight, int gen) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        double lx = pointLight.getX(), ly = pointLight.getY(), lz = pointLight.getZ();
        IntStream.range(0, h).parallel().forEach(z -> {
            if (vrGeneration != gen) return;
            for (int x = 0; x < w; x++) {
                double cr = 0, cg = 0, cb = 0;
                for (int y = 0; y < CT_y_axis - 1; y++) {
                    if (cthead[z][y][x] > 300) {
                        double dx = lx-x, dy = ly-y, dz = lz-z;
                        double dLen = Math.sqrt(dx*dx+dy*dy+dz*dz);
                        if (dLen > 0) { dx/=dLen; dy/=dLen; dz/=dLen; }
                        double nx = gradX[z][y][x], ny = gradY[z][y][x], nz = gradZ[z][y][x];
                        double nLen = Math.sqrt(nx*nx+ny*ny+nz*nz);
                        if (nLen > 0) { nx/=nLen; ny/=nLen; nz/=nLen; }
                        double cosTheta = Math.max(0, dx*nx+dy*ny+dz*nz);
                        cr = cosTheta; cg = cosTheta; cb = cosTheta;
                    }
                }
                pixels[z * w + x] = toArgb(cr, cg, cb);
            }
        });
        if (vrGeneration == gen) writePixels(image, pixels);
    }

    public void lightingSide(WritableImage image, CustomTriple pointLight, int gen) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        double lx = pointLight.getX(), ly = pointLight.getY(), lz = pointLight.getZ();
        IntStream.range(0, h).parallel().forEach(z -> {
            if (vrGeneration != gen) return;
            for (int y = 0; y < w; y++) {
                double cr = 0, cg = 0, cb = 0;
                for (int x = 0; x < CT_x_axis - 1; x++) {
                    if (cthead[z][y][x] > 300) {
                        double dx = lx-x, dy = ly-y, dz = lz-z;
                        double dLen = Math.sqrt(dx*dx+dy*dy+dz*dz);
                        if (dLen > 0) { dx/=dLen; dy/=dLen; dz/=dLen; }
                        double nx = gradX[z][y][x], ny = gradY[z][y][x], nz = gradZ[z][y][x];
                        double nLen = Math.sqrt(nx*nx+ny*ny+nz*nz);
                        if (nLen > 0) { nx/=nLen; ny/=nLen; nz/=nLen; }
                        double cosTheta = Math.max(0, dx*nx+dy*ny+dz*nz);
                        cr = cosTheta; cg = cosTheta; cb = cosTheta;
                    }
                }
                pixels[z * w + y] = toArgb(cr, cg, cb);
            }
        });
        if (vrGeneration == gen) writePixels(image, pixels);
    }

    // ── Volume render helpers (retained for reference) ────────────────────────

    private ColourHolder addColor(ColourHolder rgba, ColourHolder unmodifiedColor, double transparency) {
        double opacity = unmodifiedColor.getA();
        rgba.setR(rgba.getR() + unmodifiedColor.getR() * opacity * transparency);
        rgba.setG(rgba.getG() + unmodifiedColor.getG() * opacity * transparency);
        rgba.setB(rgba.getB() + unmodifiedColor.getB() * opacity * transparency);
        return rgba;
    }

    private double modifyTransparency(double transparency, ColourHolder color) {
        return transparency * (1 - color.getA());
    }

    public ColourHolder evaluateColor(short datum, double skinOpacity) {
        if (datum < -300)                      return new ColourHolder(0.0, 0.0, 0.0, 0.0);
        else if (-300 <= datum && datum <= 49) return new ColourHolder(1.0, 0.79, 0.6, skinOpacity);
        else if (50 <= datum && datum <= 299)  return new ColourHolder(0.0, 0.0, 0.0, 0.0);
        else                                   return new ColourHolder(1.0, 1.0,  1.0, 0.8);
    }

    private ColourHolder adjustValues(ColourHolder rgb) {
        rgb.setR(Math.min(1.0, rgb.getR()));
        rgb.setG(Math.min(1.0, rgb.getG()));
        rgb.setB(Math.min(1.0, rgb.getB()));
        return rgb;
    }

    // ── Lighting helpers (retained for reference) ─────────────────────────────

    private void testVectorValues(Vector<Double> rgba) {
        System.out.println("R: " + rgba.get(0));
        System.out.println("G: " + rgba.get(1));
        System.out.println("B: " + rgba.get(2));
        System.out.println("A: " + rgba.get(3));
    }

    private CustomTriple adjustCustomTripleValues(CustomTriple colour) {
        if (colour.getX() > 1) colour.setX(1);
        if (colour.getY() > 1) colour.setY(1);
        if (colour.getZ() > 1) colour.setZ(1);
        return colour;
    }

    private CustomTriple generateCoefficient(CustomTriple colour) {
        return new CustomTriple(1 - colour.getX(), 1 - colour.getY(), 1 - colour.getZ());
    }

    public CustomTriple evaluateColorAlternative(short datum) {
        if (300 <= datum && datum <= 4096) return new CustomTriple(1.0, 1.0, 1.0);
        return new CustomTriple(0.0, 0.0, 0.0);
    }

    public CustomTriple calculateSurfaceNormal(int x, int y, int z) {
        double nx = x == 0            ? cthead[z][y][x+1] - cthead[z][y][x]
                  : x < CT_x_axis - 1 ? cthead[z][y][x+1] - cthead[z][y][x-1]
                  :                     cthead[z][y][x]   - cthead[z][y][x-1];
        double ny = y == 0            ? cthead[z][y+1][x] - cthead[z][y][x]
                  : y < CT_y_axis - 1 ? cthead[z][y+1][x] - cthead[z][y-1][x]
                  :                     cthead[z][y][x]   - cthead[z][y-1][x];
        double nz = z == 0            ? cthead[z+1][y][x] - cthead[z][y][x]
                  : z < CT_z_axis - 1 ? cthead[z+1][y][x] - cthead[z-1][y][x]
                  :                     cthead[z][y][x]   - cthead[z-1][y][x];
        return new CustomTriple(nx, ny, nz);
    }

    // ── Math utilities ────────────────────────────────────────────────────────

    public CustomTriple normaliseTriple(CustomTriple triple) {
        double x = triple.getX(), y = triple.getY(), z = triple.getZ();
        double length = Math.sqrt(x*x + y*y + z*z);
        return new CustomTriple(x/length, y/length, z/length);
    }

    public double dotProduct(CustomTriple a, CustomTriple b) {
        return a.getX()*b.getX() + a.getY()*b.getY() + a.getZ()*b.getZ();
    }

    public CustomTriple subtractTriple(CustomTriple a, CustomTriple b) {
        return new CustomTriple(a.getX()-b.getX(), a.getY()-b.getY(), a.getZ()-b.getZ());
    }

    public CustomTriple multiplyTriple(CustomTriple a, CustomTriple b) {
        return new CustomTriple(a.getX()*b.getX(), a.getY()*b.getY(), a.getZ()*b.getZ());
    }

    // ── UI builder helpers ────────────────────────────────────────────────────

    private Slider makeSlider(double min, double max, double tickUnit) {
        Slider s = new Slider(min, max, min);
        s.setShowTickLabels(true);
        s.setShowTickMarks(true);
        s.setMajorTickUnit(tickUnit);
        s.setMaxWidth(Double.MAX_VALUE);
        s.setStyle("-fx-accent:" + C_ACCENT + ";");
        return s;
    }

    private Label valueLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + C_ACCENT + ";-fx-font-size:12px;");
        return l;
    }

    private VBox sliderCard(String title, Slider slider, Label valueLabel, String description) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-weight:bold;-fx-font-size:13px;");
        HBox titleRow = new HBox(8, titleLabel, valueLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-text-fill:" + C_MUTED + ";-fx-font-size:11px;");
        descLabel.setWrapText(true);
        VBox card = new VBox(5, titleRow, slider, descLabel);
        card.setStyle(
            "-fx-background-color:#0f172a;-fx-background-radius:8;" +
            "-fx-border-color:" + C_BORDER + ";-fx-border-radius:8;-fx-border-width:1;-fx-padding:10;"
        );
        return card;
    }

    private VBox imageCard(String title, String subtitle, ImageView view) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-weight:bold;-fx-font-size:13px;");
        Label subLabel = new Label(subtitle);
        subLabel.setStyle("-fx-text-fill:" + C_MUTED + ";-fx-font-size:11px;");
        VBox card = new VBox(6, titleLabel, subLabel, view);
        card.setAlignment(Pos.CENTER);
        card.setStyle(
            "-fx-background-color:" + C_PANEL + ";-fx-background-radius:10;" +
            "-fx-border-color:" + C_BORDER + ";-fx-border-radius:10;-fx-border-width:1;-fx-padding:12;"
        );
        return card;
    }

    private static final String BTN_BASE =
        "-fx-text-fill:#ffffff;-fx-font-weight:bold;-fx-font-size:13px;" +
        "-fx-background-radius:7;-fx-padding:8 16;-fx-cursor:hand;";

    private Button styledButton(String text, String color) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle("-fx-background-color:" + color + ";" + BTN_BASE);
        b.setOnMouseEntered(e -> b.setStyle("-fx-background-color:" + darken(color) + ";" + BTN_BASE));
        b.setOnMouseExited(e  -> b.setStyle("-fx-background-color:" + color + ";" + BTN_BASE));
        return b;
    }

    private void setButtonColor(Button b, String color) {
        b.setStyle("-fx-background-color:" + color + ";" + BTN_BASE);
        b.setOnMouseEntered(e -> b.setStyle("-fx-background-color:" + darken(color) + ";" + BTN_BASE));
        b.setOnMouseExited(e  -> b.setStyle("-fx-background-color:" + color + ";" + BTN_BASE));
    }

    // Very simple hex colour darkener — drops each channel by ~15%
    private String darken(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        int r = Math.max(0, ((rgb >> 16) & 0xFF) - 38);
        int g = Math.max(0, ((rgb >>  8) & 0xFF) - 38);
        int bv = Math.max(0,  (rgb        & 0xFF) - 38);
        return String.format("#%02x%02x%02x", r, g, bv);
    }

    private Separator separator() {
        Separator sep = new Separator();
        sep.setStyle("-fx-background-color:" + C_BORDER + ";");
        return sep;
    }

    public static void main(String[] args) { launch(); }
}
