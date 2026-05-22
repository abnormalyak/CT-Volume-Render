import java.io.FileInputStream;
import java.io.FileNotFoundException;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
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

    // Precomputed gradient volume for lighting — computed once in ReadData()
    float[][][] gradX, gradY, gradZ;

    // Single background thread for all renders; keeps UI responsive
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ct-render");
        t.setDaemon(true);
        return t;
    });

    private static final String C_BG     = "#0f172a";
    private static final String C_PANEL  = "#1e293b";
    private static final String C_BORDER = "#334155";
    private static final String C_ACCENT = "#3b82f6";
    private static final String C_TEXT   = "#f1f5f9";
    private static final String C_MUTED  = "#94a3b8";

    @Override
    public void start(Stage stage) throws FileNotFoundException, IOException {
        stage.setTitle("CT Scan Visualisation Tool");

        ReadData();

        int Top_width   = CT_x_axis, Top_height   = CT_y_axis;
        int Front_width = CT_x_axis, Front_height = CT_z_axis;
        int Side_width  = CT_y_axis, Side_height  = CT_z_axis;

        WritableImage top_image   = new WritableImage(Top_width,   Top_height);
        WritableImage front_image = new WritableImage(Front_width, Front_height);
        WritableImage side_image  = new WritableImage(Side_width,  Side_height);

        ImageView TopView   = new ImageView(top_image);
        ImageView FrontView = new ImageView(front_image);
        ImageView SideView  = new ImageView(side_image);

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

        // ── Value labels ─────────────────────────────────────────────────────
        Label topVal      = valueLabel("Slice: 0");
        Label frontVal    = valueLabel("Slice: 0");
        Label sideVal     = valueLabel("Slice: 0");
        Label skinVal     = valueLabel("Opacity: 0.00");
        Label lightingVal = valueLabel("Position: 0");

        // ── Listeners ────────────────────────────────────────────────────────
        // Slice renders are fast — submit directly with no debounce
        Top_slider.valueProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            topVal.setText("Slice: " + i);
            renderExecutor.submit(() -> drawTopImage(top_image, i));
        });

        Front_slider.valueProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            frontVal.setText("Slice: " + i);
            renderExecutor.submit(() -> drawFrontImage(front_image, i));
        });

        Side_slider.valueProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            sideVal.setText("Slice: " + i);
            renderExecutor.submit(() -> drawSideImage(side_image, i));
        });

        // Volume render and lighting are expensive — debounce 150ms so the
        // executor queue doesn't fill up while the slider is being dragged
        PauseTransition skinDebounce = new PauseTransition(Duration.millis(150));
        Skin_slider.valueProperty().addListener((obs, o, n) -> {
            double v = n.doubleValue();
            skinVal.setText(String.format("Opacity: %.2f", v));
            skinDebounce.setOnFinished(e -> renderExecutor.submit(() -> volumeRenderAll(images, v)));
            skinDebounce.playFromStart();
        });

        PauseTransition lightingDebounce = new PauseTransition(Duration.millis(150));
        lighting_slider.valueProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            lightingVal.setText("Position: " + i);
            lightingDebounce.setOnFinished(e -> renderExecutor.submit(() -> lightingAll(images, new CustomTriple(i, 0.0, 0.0))));
            lightingDebounce.playFromStart();
        });

        // ── Initial render ───────────────────────────────────────────────────
        renderExecutor.submit(() -> {
            drawTopImage(top_image, 0);
            drawFrontImage(front_image, 0);
            drawSideImage(side_image, 0);
        });

        // ── Image panels ─────────────────────────────────────────────────────
        HBox imageRow = new HBox(12,
            imageCard("Axial View",    "Top-down (Z-axis)",    TopView),
            imageCard("Coronal View",  "Front-back (Y-axis)",  FrontView),
            imageCard("Sagittal View", "Left-right (X-axis)",  SideView)
        );
        imageRow.setAlignment(Pos.CENTER);
        imageRow.setPadding(new Insets(16));

        // ── Buttons ──────────────────────────────────────────────────────────
        Button volRend_button = styledButton("Volume Render");
        volRend_button.setOnAction(e -> renderExecutor.submit(() -> volumeRenderAll(images, Skin_slider.getValue())));

        Button lightingButton = styledButton("Apply Lighting");
        lightingButton.setOnAction(e -> renderExecutor.submit(() -> lightingAll(images, new CustomTriple(lighting_slider.getValue(), 0, 0))));

        // ── Controls panel ───────────────────────────────────────────────────
        Label controlsTitle = new Label("Controls");
        controlsTitle.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:15px;-fx-font-weight:bold;");

        VBox controlsPanel = new VBox(10,
            controlsTitle,
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

        // ── Root layout ──────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(imageRow);
        root.setRight(controlsScroll);
        root.setStyle("-fx-background-color:" + C_BG + ";");

        Scene scene = new Scene(root, 1100, 780);
        stage.setScene(scene);
        stage.show();
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

    // Computes central-difference gradients for the whole volume once at startup.
    // Lighting renders do a single array lookup instead of recomputing on every ray.
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

    public void TopDownSlice76(WritableImage image) {
        drawTopImage(image, 76);
    }

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

        writePixels(image, pixels);
    }

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

        writePixels(image, pixels);
    }

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

        writePixels(image, pixels);
    }

    // ── Volume rendering ──────────────────────────────────────────────────────

    public void volumeRenderAll(ArrayList<WritableImage> images, double skinOpacity) {
        volumeRenderTopDown(images.get(0), skinOpacity);
        volumeRenderFrontBack(images.get(1), skinOpacity);
        volumeRenderSide(images.get(2), skinOpacity);
    }

    public void volumeRenderTopDown(WritableImage image, double skinOpacity) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];

        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0, transparency = 1.0;

                for (int z = 0; z < CT_z_axis; z++) {
                    short datum = cthead[z][y][x];
                    double er, eg, eb, ea;

                    if      (datum < -300)               continue;
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

        writePixels(image, pixels);
    }

    public void volumeRenderFrontBack(WritableImage image, double skinOpacity) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];

        IntStream.range(0, h).parallel().forEach(z -> {
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0, transparency = 1.0;

                for (int y = 0; y < CT_y_axis - 1; y++) {
                    short datum = cthead[z][y][x];
                    double er, eg, eb, ea;

                    if      (datum < -300)               continue;
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

        writePixels(image, pixels);
    }

    public void volumeRenderSide(WritableImage image, double skinOpacity) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];

        IntStream.range(0, h).parallel().forEach(z -> {
            for (int y = 0; y < w; y++) {
                double r = 0, g = 0, b = 0, transparency = 1.0;

                for (int x = 0; x < CT_x_axis - 1; x++) {
                    short datum = cthead[z][y][x];
                    double er, eg, eb, ea;

                    if      (datum < -300)               continue;
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

        writePixels(image, pixels);
    }

    // ── Lighting ──────────────────────────────────────────────────────────────

    public void lightingAll(ArrayList<WritableImage> images, CustomTriple pointLight) {
        CustomTriple pointLightSide  = new CustomTriple(pointLight.getY(), pointLight.getX(), pointLight.getZ());
        CustomTriple pointLightFront = new CustomTriple(pointLight.getZ(), pointLight.getY(), pointLight.getX());

        lightingTopDown(images.get(0), pointLight);
        lightingFrontBack(images.get(1), pointLightFront);
        lightingSide(images.get(2), pointLightSide);
    }

    public void lightingTopDown(WritableImage image, CustomTriple pointLight) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        double lx = pointLight.getX(), ly = pointLight.getY(), lz = pointLight.getZ();

        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                double cr = 0, cg = 0, cb = 0;

                for (int z = 0; z < CT_z_axis; z++) {
                    if (cthead[z][y][x] > 300) {
                        double dx = lx - x, dy = ly - y, dz = lz - z;
                        double dLen = Math.sqrt(dx*dx + dy*dy + dz*dz);
                        if (dLen > 0) { dx /= dLen; dy /= dLen; dz /= dLen; }

                        double nx = gradX[z][y][x], ny = gradY[z][y][x], nz = gradZ[z][y][x];
                        double nLen = Math.sqrt(nx*nx + ny*ny + nz*nz);
                        if (nLen > 0) { nx /= nLen; ny /= nLen; nz /= nLen; }

                        double cosTheta = Math.max(0, dx*nx + dy*ny + dz*nz);
                        cr = cosTheta; cg = cosTheta; cb = cosTheta;
                    }
                }

                pixels[y * w + x] = toArgb(cr, cg, cb);
            }
        });

        writePixels(image, pixels);
    }

    public void lightingFrontBack(WritableImage image, CustomTriple pointLight) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        double lx = pointLight.getX(), ly = pointLight.getY(), lz = pointLight.getZ();

        IntStream.range(0, h).parallel().forEach(z -> {
            for (int x = 0; x < w; x++) {
                double cr = 0, cg = 0, cb = 0;

                for (int y = 0; y < CT_y_axis - 1; y++) {
                    if (cthead[z][y][x] > 300) {
                        double dx = lx - x, dy = ly - y, dz = lz - z;
                        double dLen = Math.sqrt(dx*dx + dy*dy + dz*dz);
                        if (dLen > 0) { dx /= dLen; dy /= dLen; dz /= dLen; }

                        double nx = gradX[z][y][x], ny = gradY[z][y][x], nz = gradZ[z][y][x];
                        double nLen = Math.sqrt(nx*nx + ny*ny + nz*nz);
                        if (nLen > 0) { nx /= nLen; ny /= nLen; nz /= nLen; }

                        double cosTheta = Math.max(0, dx*nx + dy*ny + dz*nz);
                        cr = cosTheta; cg = cosTheta; cb = cosTheta;
                    }
                }

                pixels[z * w + x] = toArgb(cr, cg, cb);
            }
        });

        writePixels(image, pixels);
    }

    public void lightingSide(WritableImage image, CustomTriple pointLight) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        double lx = pointLight.getX(), ly = pointLight.getY(), lz = pointLight.getZ();

        IntStream.range(0, h).parallel().forEach(z -> {
            for (int y = 0; y < w; y++) {
                double cr = 0, cg = 0, cb = 0;

                for (int x = 0; x < CT_x_axis - 1; x++) {
                    if (cthead[z][y][x] > 300) {
                        double dx = lx - x, dy = ly - y, dz = lz - z;
                        double dLen = Math.sqrt(dx*dx + dy*dy + dz*dz);
                        if (dLen > 0) { dx /= dLen; dy /= dLen; dz /= dLen; }

                        double nx = gradX[z][y][x], ny = gradY[z][y][x], nz = gradZ[z][y][x];
                        double nLen = Math.sqrt(nx*nx + ny*ny + nz*nz);
                        if (nLen > 0) { nx /= nLen; ny /= nLen; nz /= nLen; }

                        double cosTheta = Math.max(0, dx*nx + dy*ny + dz*nz);
                        cr = cosTheta; cg = cosTheta; cb = cosTheta;
                    }
                }

                pixels[z * w + y] = toArgb(cr, cg, cb);
            }
        });

        writePixels(image, pixels);
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
        if (datum < -300)                         return new ColourHolder(0.0, 0.0, 0.0, 0.0);
        else if (-300 <= datum && datum <= 49)    return new ColourHolder(1.0, 0.79, 0.6, skinOpacity);
        else if (50 <= datum && datum <= 299)     return new ColourHolder(0.0, 0.0, 0.0, 0.0);
        else                                      return new ColourHolder(1.0, 1.0,  1.0, 0.8);
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
            "-fx-background-color:#0f172a;" +
            "-fx-background-radius:8;" +
            "-fx-border-color:" + C_BORDER + ";" +
            "-fx-border-radius:8;" +
            "-fx-border-width:1;" +
            "-fx-padding:10;"
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
            "-fx-background-color:" + C_PANEL + ";" +
            "-fx-background-radius:10;" +
            "-fx-border-color:" + C_BORDER + ";" +
            "-fx-border-radius:10;" +
            "-fx-border-width:1;" +
            "-fx-padding:12;"
        );
        return card;
    }

    private Button styledButton(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle(
            "-fx-background-color:" + C_ACCENT + ";" +
            "-fx-text-fill:#ffffff;" +
            "-fx-font-weight:bold;" +
            "-fx-font-size:13px;" +
            "-fx-background-radius:7;" +
            "-fx-padding:8 16;" +
            "-fx-cursor:hand;"
        );
        b.setOnMouseEntered(e -> b.setStyle(
            "-fx-background-color:#2563eb;" +
            "-fx-text-fill:#ffffff;" +
            "-fx-font-weight:bold;" +
            "-fx-font-size:13px;" +
            "-fx-background-radius:7;" +
            "-fx-padding:8 16;" +
            "-fx-cursor:hand;"
        ));
        b.setOnMouseExited(e -> b.setStyle(
            "-fx-background-color:" + C_ACCENT + ";" +
            "-fx-text-fill:#ffffff;" +
            "-fx-font-weight:bold;" +
            "-fx-font-size:13px;" +
            "-fx-background-radius:7;" +
            "-fx-padding:8 16;" +
            "-fx-cursor:hand;"
        ));
        return b;
    }

    private Separator separator() {
        Separator sep = new Separator();
        sep.setStyle("-fx-background-color:" + C_BORDER + ";");
        return sep;
    }

    public static void main(String[] args) {
        launch();
    }
}
