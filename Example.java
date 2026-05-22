import java.io.FileInputStream;
import java.io.FileNotFoundException;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.image.PixelWriter;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import java.io.*;
import java.util.ArrayList;
import java.util.Vector;

// OK this is not best practice - maybe you'd like to create
// a volume data class?
// I won't give extra marks for that though.

public class Example extends Application {
	short cthead[][][]; //store the 3D volume data set
	short min, max; //min/max value in the 3D volume data set
	int CT_x_axis = 256;
    int CT_y_axis = 256;
	int CT_z_axis = 113;
	
    // Dark theme colour palette
    private static final String C_BG       = "#0f172a";
    private static final String C_PANEL    = "#1e293b";
    private static final String C_BORDER   = "#334155";
    private static final String C_ACCENT   = "#3b82f6";
    private static final String C_TEXT     = "#f1f5f9";
    private static final String C_MUTED    = "#94a3b8";

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
        Slider Top_slider     = makeSlider(0, CT_z_axis - 1, 20);
        Slider Front_slider   = makeSlider(0, CT_y_axis - 1, 50);
        Slider Side_slider    = makeSlider(0, CT_x_axis - 1, 50);
        Slider Skin_slider    = makeSlider(0, 1, 0.25);
        Slider lighting_slider = makeSlider(0, 255, 50);

        // ── Value labels ─────────────────────────────────────────────────────
        Label topVal      = valueLabel("Slice: 0");
        Label frontVal    = valueLabel("Slice: 0");
        Label sideVal     = valueLabel("Slice: 0");
        Label skinVal     = valueLabel("Opacity: 0.00");
        Label lightingVal = valueLabel("Position: 0");

        // ── Listeners ────────────────────────────────────────────────────────
        Top_slider.valueProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            topVal.setText("Slice: " + i);
            drawTopImage(top_image, i);
        });

        Front_slider.valueProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            frontVal.setText("Slice: " + i);
            drawFrontImage(front_image, i);
        });

        Side_slider.valueProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            sideVal.setText("Slice: " + i);
            drawSideImage(side_image, i);
        });

        Skin_slider.valueProperty().addListener((obs, o, n) -> {
            double v = n.doubleValue();
            skinVal.setText(String.format("Opacity: %.2f", v));
            volumeRenderAll(images, v);
        });

        lighting_slider.valueProperty().addListener((obs, o, n) -> {
            int i = n.intValue();
            lightingVal.setText("Position: " + i);
            lightingAll(images, new CustomTriple(i, 0.0, 0.0));
        });

        // ── Initial render ───────────────────────────────────────────────────
        drawTopImage(top_image, 0);
        drawFrontImage(front_image, 0);
        drawSideImage(side_image, 0);

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
        volRend_button.setOnAction(e -> volumeRenderAll(images, Skin_slider.getValue()));

        Button lightingButton = styledButton("Apply Lighting");
        lightingButton.setOnAction(e -> lightingAll(images, new CustomTriple(lighting_slider.getValue(), 0, 0)));

        // ── Controls panel ───────────────────────────────────────────────────
        Label controlsTitle = new Label("Controls");
        controlsTitle.setStyle("-fx-text-fill:" + C_TEXT + ";-fx-font-size:15px;-fx-font-weight:bold;");

        Separator sep1 = separator();
        Separator sep2 = separator();

        VBox controlsPanel = new VBox(10,
            controlsTitle,
            sliderCard("Axial Slice (Z)",   Top_slider,      topVal,
                "Scroll through horizontal cross-sections from the top of the skull down to the neck (0 – 112 slices)."),
            sliderCard("Coronal Slice (Y)",  Front_slider,    frontVal,
                "Scroll through front-to-back cross-sections of the head — from the nose to the back of the skull (0 – 255)."),
            sliderCard("Sagittal Slice (X)", Side_slider,     sideVal,
                "Scroll through left-to-right cross-sections of the head — from one ear to the other (0 – 255)."),
            sep1,
            volRend_button,
            sliderCard("Skin Opacity",       Skin_slider,     skinVal,
                "Controls how opaque skin tissue appears in volume rendering. 0 = fully transparent (bone only), 1 = fully visible skin."),
            sep2,
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
	
	//Function to read in the cthead data set
	public void ReadData() throws IOException {
		//File name is hardcoded here - much nicer to have a dialog to select it and capture the size from the user
		File file = new File("CThead");
		//Read the data quickly via a buffer (in C++ you can just do a single fread - I couldn't find if there is an equivalent in Java)
		DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
		
		int i, j, k; //loop through the 3D data set
		
		min=Short.MAX_VALUE; max=Short.MIN_VALUE; //set to extreme values
		short read; //value read in
		int b1, b2; //data is wrong Endian (check wikipedia) for Java so we need to swap the bytes around
		
		cthead = new short[CT_z_axis][CT_y_axis][CT_x_axis]; //allocate the memory - note this is fixed for this data set
		//loop through the data reading it in
		for (k=0; k<CT_z_axis; k++) {
			for (j=0; j<CT_y_axis; j++) {
				for (i=0; i<CT_x_axis; i++) {
					//because the Endianess is wrong, it needs to be read byte at a time and swapped
					b1=((int)in.readByte()) & 0xff; //the 0xff is because Java does not have unsigned types
					b2=((int)in.readByte()) & 0xff; //the 0xff is because Java does not have unsigned types
					read=(short)((b2<<8) | b1); //and swizzle the bytes around
					if (read<min) min=read; //update the minimum
					if (read>max) max=read; //update the maximum
					cthead[k][j][i]=read; //put the short into memory (in C++ you can replace all this code with one fread)
				}
			}
		}
		System.out.println(min+" "+max); //diagnostic - for CThead this should be -1117, 2248
		//(i.e. there are 3366 levels of grey (we are trying to display on 256 levels of grey)
		//therefore histogram equalization would be a good thing
		//maybe put your histogram equalization code here to set up the mapping array
	}

	
	 /*
        This function shows how to carry out an operation on an image.
        It obtains the dimensions of the image, and then loops through
        the image carrying out the copying of a slice of data into the
		image.
    */
	    public void TopDownSlice76(WritableImage image) {
			//Get image dimensions, and declare loop variables
			int w = (int) image.getWidth(), h = (int) image.getHeight();
			PixelWriter image_writer = image.getPixelWriter();

			double col;
			short datum;
			//Shows how to loop through each pixel and colour
			//Try to always use j for loops in y, and i for loops in x
			//as this makes the code more readable
			for (int j = 0; j < h; j++) {
				for (int i = 0; i < w; i++) {
					//at this point (i,j) is a single pixel in the image
					//here you would need to do something to (i,j) if the image size
					//does not match the slice size (e.g. during an image resizing operation
					//If you don't do this, your j,i could be outside the array bounds
					//In the framework, the image is 256x256 and the data set slices are 256x256
					//so I don't do anything - this also leaves you something to do for the assignment
					datum = cthead[76][j][i]; //get values from slice 76 (change this in your assignment)
					//calculate the colour by performing a mapping from [min,max] -> 0 to 1 (float)
					//Java setColor uses float values from 0 to 1 rather than 0-255 bytes for colour
					col = (((float) datum - (float) min) / ((float) (max - min)));
					image_writer.setColor(i, j, Color.color(col, col, col, 1.0));
				} // column loop
			} // row loop
		}

		public void drawTopImage(WritableImage image, int slice) {
			int w = (int) image.getWidth();
			int h = (int) image.getHeight();

			PixelWriter imageWriter = image.getPixelWriter();

			double color;
			short datum;

			// slice is the values of 'z'
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					datum = cthead[slice][y][x];
					color = (((float) datum - (float) min) / ((float) (max - min)));
					imageWriter.setColor(x, y, Color.color(color, color, color, 1.0));
				}
			}
		}

	public void drawFrontImage(WritableImage image, int slice) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();

		PixelWriter imageWriter = image.getPixelWriter();

		double color;
		short datum;

		// slice is the values of 'y'
		for (int z = 0; z < h; z++) {
			for (int x = 0; x < w; x++) {
				datum = cthead[z][slice][x];
				color = (((float) datum - (float) min) / ((float) (max - min)));
				imageWriter.setColor(x, z, Color.color(color, color, color, 1.0));
			}
		}
	}

	public void drawSideImage(WritableImage image, int slice) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();

		PixelWriter imageWriter = image.getPixelWriter();

		double color;
		short datum;

		// slice is the values of 'x'
		for (int z = 0; z < h; z++) {
			for (int y = 0; y < w; y++) {
				datum = cthead[z][y][slice];
				color = (((float) datum - (float) min) / ((float) (max - min)));
				imageWriter.setColor(y, z, Color.color(color, color, color, 1.0));
			}
		}
	}

	public void volumeRenderAll(ArrayList<WritableImage> images, double skinOpacity) {
	    	volumeRenderTopDown(images.get(0), skinOpacity);
	    	volumeRenderFrontBack(images.get(1), skinOpacity);
	    	volumeRenderSide(images.get(2), skinOpacity);
	    }

	public void volumeRenderTopDown(WritableImage image, double skinOpacity) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();

		PixelWriter imageWriter = image.getPixelWriter();

		short datum;

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				ColourHolder rgb = new ColourHolder(0.0, 0.0, 0.0, 0.0);

				double transparency = 1;

				ColourHolder unmodifiedColor;

				for (int z = 0; z < 113; z++) {
					datum = cthead[z][y][x];

					unmodifiedColor = evaluateColor(datum, skinOpacity);

					rgb = addColor(rgb, unmodifiedColor, transparency);

					// Update transparency in rgba
					transparency = modifyTransparency(transparency, unmodifiedColor);

					// Break if low transparency (insignificant effect)
					if (transparency < 0.01) {
						break;
					}
				}

				rgb = adjustValues(rgb);

				double r = rgb.getR();
				double g = rgb.getG();
				double b = rgb.getB();

				imageWriter.setColor(x, y, Color.color(r, g, b, 1));
			}
		}
	}

	public void volumeRenderFrontBack(WritableImage image, double skinOpacity) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();

		PixelWriter imageWriter = image.getPixelWriter();

		short datum;

		for (int z = 0; z < h; z++) {
			for (int x = 0; x < w; x++) {
				ColourHolder rgb = new ColourHolder(0.0, 0.0, 0.0, 0.0);

				double transparency = 1;

				ColourHolder unmodifiedColor;

				for (int y = 0; y < 255; y++) {
					datum = cthead[z][y][x];
					unmodifiedColor = evaluateColor(datum, skinOpacity);

					rgb = addColor(rgb, unmodifiedColor, transparency);

					// Update transparency in rgba
					transparency = modifyTransparency(transparency, unmodifiedColor);

					// Break if low transparency (insignificant effect)
					if (transparency < 0.01) {
						break;
					}
				}

				rgb = adjustValues(rgb);

				double r = rgb.getR();
				double g = rgb.getG();
				double b = rgb.getB();

				imageWriter.setColor(x, z, Color.color(r, g, b, 1));
			}
		}
	}

	public void volumeRenderSide(WritableImage image, double skinOpacity) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();

		PixelWriter imageWriter = image.getPixelWriter();

		short datum;

		for (int z = 0; z < h; z++) {
			for (int y = 0; y < w; y++) {
				ColourHolder rgb = new ColourHolder(0.0, 0.0, 0.0, 0.0);

				double transparency = 1;

				ColourHolder unmodifiedColor;

				for (int x = 0; x < 255; x++) {
					datum = cthead[z][y][x];
					unmodifiedColor = evaluateColor(datum, skinOpacity);

					rgb = addColor(rgb, unmodifiedColor, transparency);

					// Update transparency in rgba
					transparency = modifyTransparency(transparency, unmodifiedColor);

					// Break if low transparency (insignificant effect)
					if (transparency < 0.01) {
						break;
					}
				}

				rgb = adjustValues(rgb);

				double r = rgb.getR();
				double g = rgb.getG();
				double b = rgb.getB();

				imageWriter.setColor(y, z, Color.color(r, g, b, 1));
			}
		}
	}

	private ColourHolder addColor(ColourHolder rgba, ColourHolder unmodifiedColor, double transparency) {
	    	double r = rgba.getR();
			double g = rgba.getG();
			double b = rgba.getB();

			double opacity = unmodifiedColor.getA();

			r += unmodifiedColor.getR() * opacity * transparency;
			g += unmodifiedColor.getG() * opacity * transparency;
			b += unmodifiedColor.getB() * opacity * transparency;

			rgba.setR(r);
			rgba.setG(g);
			rgba.setB(b);

			return rgba;
	}

	private double modifyTransparency(double transparency, ColourHolder color) {
	    	return transparency * (1 - color.getA());
	}

	public ColourHolder evaluateColor(short datum, double skinOpacity) {
	    	ColourHolder result = new ColourHolder(0.0, 0.0, 0.0, 0.0);

			if (datum < -300) { // completely transparent
				result = new ColourHolder(0.0, 0.0, 0.0, 0.0);
			} else if (-300 <= datum && datum <= 49) { // RGB 1, 0.79, 0.6, Opacity = 0.12
				result = new ColourHolder(1.0, 0.79, 0.6, skinOpacity);
			} else if (50 <= datum && datum <= 299) { // completely transparent
				result = new ColourHolder(0.0, 0.0, 0.0, 0.0);
			} else if (300 <= datum && datum <= 4096) { // RGB 1, 1, 1, Opacity = 0.8
				result = new ColourHolder(1.0, 1.0, 1.0, 0.8);
			}

			return result;
	}

	private ColourHolder adjustValues(ColourHolder rgb) {
			double r = rgb.getR();
			double g = rgb.getG();
			double b = rgb.getB();

			if (r > 1) {
				r = 1.0;
			}
			if (g > 1) {
				g = 1.0;
			}
			if (b > 1) {
				b = 1.0;
			}

			rgb.setR(r);
			rgb.setG(g);
			rgb.setB(b);

			return rgb;
	}

	private void testVectorValues(Vector<Double> rgba) {
		System.out.println("R: " + rgba.get(0));
		System.out.println("G: " + rgba.get(1));
		System.out.println("B: " + rgba.get(2));
		System.out.println("A: " + rgba.get(3));
	}

	public void lightingAll(ArrayList<WritableImage> images, CustomTriple pointLight) {
		// Required different pointLight for side to fix artifacting
		CustomTriple pointLightSide = new CustomTriple(pointLight.getY(), pointLight.getX(), pointLight.getZ());

		// More interesting pointLight for front (well, back) of head
		CustomTriple pointLightFront = new CustomTriple(pointLight.getZ(), pointLight.getY(), pointLight.getX());

		// Calls appropriate method on each image
		lightingTopDown(images.get(0), pointLight);
		lightingFrontBack(images.get(1), pointLightFront);
		lightingSide(images.get(2), pointLightSide);
	}

	public void lightingTopDown(WritableImage image, CustomTriple pointLight) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();

		PixelWriter imageWriter = image.getPixelWriter();

		short datum;

		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				CustomTriple colour = new CustomTriple(0.0, 0.0, 0.0);
				for (int z = 0; z < 113; z++) {
					datum = cthead[z][y][x];

					if (datum > 300) {
						colour = new CustomTriple(1.0, 1.0, 1.0);
						CustomTriple intersectionPoint = new CustomTriple(x, y, z);
						CustomTriple dirToLight = normaliseTriple(subtractTriple(pointLight, intersectionPoint));
						CustomTriple surfaceNormal = calculateSurfaceNormal(x, y, z);
						surfaceNormal = normaliseTriple(surfaceNormal);
						double cosTheta = Math.max(0, dotProduct(dirToLight, surfaceNormal));

						double r = colour.getX() * cosTheta;
						double g = colour.getY() * cosTheta;
						double b = colour.getZ() * cosTheta;

						colour.setX(r);
						colour.setY(g);
						colour.setZ(b);

						colour = adjustCustomTripleValues(colour);
					}

//					CustomTriple colour = evaluateColorAlternative(datum);

					// Diffuse lighting


//					// Specular reflection
//					CustomTriple reflectionCoefficient = generateCoefficient(colour);
////					reflectionCoefficient.getX() *
//					CustomTriple lightVector = subtractTriple(pointLight, intersectionPoint); // Variable I don't understand; "light vector" on 'rough slide'
//					/* Likely incorrect; ask for clarification;
//					"where the pixel is in 3D space - intersection";
//					isn't that just intersection - intersection? */
//					CustomTriple eyeDirection = subtractTriple(lightVector, intersectionPoint);
//					CustomTriple reflectedLight = subtractTriple(dirToLight, new CustomTriple(2 * cosTheta, 2 * cosTheta, 2 * cosTheta));
//
//					double finalR = r + reflectionCoefficient.getX() * dotProduct(eyeDirection, reflectedLight)


				}
				imageWriter.setColor(x, y, Color.color(colour.getX(), colour.getY(), colour.getZ(), 1.0));
			}
		}
	}

	public void lightingFrontBack(WritableImage image, CustomTriple pointLight) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();

		PixelWriter imageWriter = image.getPixelWriter();

		short datum;

		for (int z = 0; z < h; z++) {
			for (int x = 0; x < w; x++) {
				CustomTriple colour = new CustomTriple(0.0, 0.0, 0.0); // Defaults to black before ray is cast
				for (int y = 0; y < 255; y++) {
					datum = cthead[z][y][x];

					// If bone, cast ray 'properly'
					if (datum > 300) {
						colour = new CustomTriple(1.0, 1.0, 1.0);
						CustomTriple intersectionPoint = new CustomTriple(x, y, z);
						CustomTriple dirToLight = normaliseTriple(subtractTriple(pointLight, intersectionPoint));
						CustomTriple surfaceNormal = calculateSurfaceNormal(x, y, z);
						surfaceNormal = normaliseTriple(surfaceNormal);
						double cosTheta = Math.max(0, dotProduct(dirToLight, surfaceNormal));

						double r = colour.getX() * cosTheta;
						double g = colour.getY() * cosTheta;
						double b = colour.getZ() * cosTheta;

						colour.setX(r);
						colour.setY(g);
						colour.setZ(b);

						colour = adjustCustomTripleValues(colour);
					}
				}
				imageWriter.setColor(x, z, Color.color(colour.getX(), colour.getY(), colour.getZ(), 1.0));
			}
		}
	}

	public void lightingSide(WritableImage image, CustomTriple pointLight) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();

		PixelWriter imageWriter = image.getPixelWriter();

		short datum;

		for (int z = 0; z < h; z++) {
			for (int y = 0; y < w; y++) {
				CustomTriple colour = new CustomTriple(0.0, 0.0, 0.0); // Defaults to black before ray is cast
				for (int x = 0; x < 255; x++) {
					datum = cthead[z][y][x];

					// If bone, cast ray 'properly'
					if (datum > 300) {
						colour = new CustomTriple(1.0, 1.0, 1.0);
						CustomTriple intersectionPoint = new CustomTriple(x, y, z);
						CustomTriple dirToLight = normaliseTriple(subtractTriple(pointLight, intersectionPoint));
						CustomTriple surfaceNormal = calculateSurfaceNormal(x, y, z);
						surfaceNormal = normaliseTriple(surfaceNormal);
						double cosTheta = Math.max(0, dotProduct(dirToLight, surfaceNormal));

						double r = colour.getX() * cosTheta;
						double g = colour.getY() * cosTheta;
						double b = colour.getZ() * cosTheta;

						colour.setX(r);
						colour.setY(g);
						colour.setZ(b);

						colour = adjustCustomTripleValues(colour);
					}
				}
				imageWriter.setColor(y, z, Color.color(colour.getX(), colour.getY(), colour.getZ(), 1.0));
			}
		}
	}

	private CustomTriple adjustCustomTripleValues(CustomTriple colour) {
	    	if (colour.getX() > 1) {
	    		colour.setX(1);
			}
	    	if (colour.getY() > 1) {
	    		colour.setY(1);
			}
	    	if (colour.getZ() > 1) {
	    		colour.setZ(1);
			}
	    	return colour;
	}

	private CustomTriple generateCoefficient(CustomTriple colour) {
	    	double newX = 1 - colour.getX();
			double newY = 1 - colour.getY();
			double newZ = 1 - colour.getZ();

			return new CustomTriple(newX, newY, newZ);
	}

	public CustomTriple evaluateColorAlternative(short datum) {
		CustomTriple result = new CustomTriple(0.0, 0.0, 0.0);

		if (300 <= datum && datum <= 4096) { // bone
			result = new CustomTriple(1.0, 1.0, 1.0);
		}

		return result;
	}

	public CustomTriple calculateSurfaceNormal(int x, int y, int z) {
	    	double nx;
	    	double ny;
	    	double nz;

	    	if (x == 0) {
	    		nx = cthead[z][y][x + 1] - cthead[z][y][x];
			} else if (x < 255) {
				nx = cthead[z][y][x + 1] - cthead[z][y][x - 1];
			} else {
				nx = cthead[z][y][x] - cthead[z][y][x - 1];
			}
			if (y == 0) {
				ny = cthead[z][y + 1][x] - cthead[z][y][x];
			} else if (y < 255) {
				ny = cthead[z][y + 1][x] - cthead[z][y - 1][x];
			} else {
				ny = cthead[z][y][x] - cthead[z][y - 1][x];
			}
			if (z == 0) {
				nz = cthead[z + 1][y][x] - cthead[z][y][x];
			} else if (z < 112) {
				nz = cthead[z + 1][y][x] - cthead[z - 1][y][x];
			} else {
				nz = cthead[z][y][x] - cthead[z - 1][y][x];
			}

	    	return new CustomTriple(nx, ny, nz);
	}

	public CustomTriple normaliseTriple(CustomTriple triple) {
	    	double x = triple.getX();
	    	double y = triple.getY();
	    	double z = triple.getZ();

			// Find the length
			double length = Math.sqrt((x*x) + (y*y) + (z*z)); // Done this way because ^2 not applicable for double

			// Calculate new values and add to triple
			CustomTriple result = new CustomTriple(x/length, y/length, z/length);
			return result;
	}

	public double dotProduct(CustomTriple a, CustomTriple b) {
	    	double newX = a.getX() * b.getX();
	    	double newY = a.getY() * b.getY();
	    	double newZ = a.getZ() * b.getZ();

	    	return newX + newY + newZ;
	}

	public CustomTriple subtractTriple(CustomTriple a, CustomTriple b) {
	    	double newX = a.getX() - b.getX();
			double newY = a.getY() - b.getY();
			double newZ = a.getZ() - b.getZ();

			return new CustomTriple(newX, newY, newZ);
	}

	public CustomTriple multiplyTriple(CustomTriple a, CustomTriple b) {
		double newX = a.getX() * b.getX();
		double newY = a.getY() * b.getY();
		double newZ = a.getZ() * b.getZ();

		return new CustomTriple(newX, newY, newZ);
	}

    public static void main(String[] args) {
        launch();
    }

}

/*
Dump
 */

//	public Vector<Double> evaluateColor(short datum, Vector<Double> rgba, int z) {
//		Double transparency = rgba.get(3);
//		Double r = rgba.get(0);
//		Double g = rgba.get(1);
//		Double b = rgba.get(2);
//
//
//		if (datum < -300) { // completely transparent
//			return rgba;
//		} else if (-300 <= datum && datum >= 49) { // RGB 1, 0.79, 0.6, Opacity = 0.12
//			r += (1 * 0.12 * transparency);
//			g += (0.79 * 0.12 * transparency);
//			b += (0.6 * 0.12 * transparency);
//			transparency = transparency * (1 - 0.12);
//		} else if (50 <= datum && datum >= 299) { // completely transparent
//			return rgba;
//		} else if (300 <= datum && datum >= 4096) { // RGB 1, 1, 1, Opacity = 0.8
//			r += 1 * 0.8 * transparency;
//			g += 1 * 0.8 * transparency;
//			b += 1 * 0.8 * transparency;
//			transparency = transparency * (1 - 0.8);
//		}
//
//		rgba.set(0, r);
//		rgba.set(1, g);
//		rgba.set(2, b);
//		rgba.set(3, transparency);
//
//		return rgba;