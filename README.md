# CT Scan Visualisation Tool

A JavaFX application for visualising CT scan volume data. Supports interactive slice browsing across all three anatomical planes, volume rendering with adjustable skin opacity, and diffuse lighting on bone surfaces.

## Features

- **Axial, coronal and sagittal slice views** — scroll through cross-sections in all three axes
- **Volume rendering** — composites skin and bone with adjustable skin opacity
- **Diffuse lighting** — Phong-style shading on bone surfaces with a movable point light
- Dark modern UI with labelled, described controls

## Dataset

The included `CThead` binary dataset is a classic publicly available CT scan of a human head, widely distributed for research and educational use in volume visualisation. It is commonly attributed to the **University of North Carolina at Chapel Hill** Volume Visualization Group. The data is 256 × 256 × 113 voxels, stored as 16-bit signed integers in little-endian byte order.

## Requirements

- **Java 17+** (Java 21 recommended)
- **JavaFX SDK 21** — not bundled with the JDK; must be downloaded separately

## Installing JavaFX

1. Download the JavaFX SDK from [gluonhq.com/products/javafx](https://gluonhq.com/products/javafx/)
2. Extract it somewhere stable, e.g. `~/javafx-sdk-21.0.11`

## Running

### From IntelliJ IDEA (recommended, works on all platforms)

1. Open the project
2. Go to **File → Project Structure → Libraries** and add the JavaFX SDK `lib` folder as a library
3. Go to **Run → Edit Configurations**, add the following to **VM options**:

   - **macOS / Linux:**
     ```
     --module-path /path/to/javafx-sdk-21/lib --add-modules javafx.controls,javafx.graphics
     ```
   - **Windows:**
     ```
     --module-path C:\path\to\javafx-sdk-21\lib --add-modules javafx.controls,javafx.graphics
     ```

4. Run `Example`

### From the command line

**macOS / Linux:**
```bash
javac --module-path /path/to/javafx-sdk-21/lib \
      --add-modules javafx.controls,javafx.graphics \
      Example.java ColourHolder.java CustomTriple.java

java --module-path /path/to/javafx-sdk-21/lib \
     --add-modules javafx.controls,javafx.graphics \
     Example
```

**Windows (Command Prompt):**
```cmd
javac --module-path C:\path\to\javafx-sdk-21\lib --add-modules javafx.controls,javafx.graphics Example.java ColourHolder.java CustomTriple.java

java --module-path C:\path\to\javafx-sdk-21\lib --add-modules javafx.controls,javafx.graphics Example
```

**Windows (PowerShell):**
```powershell
javac --module-path "C:\path\to\javafx-sdk-21\lib" `
      --add-modules javafx.controls,javafx.graphics `
      Example.java ColourHolder.java CustomTriple.java

java --module-path "C:\path\to\javafx-sdk-21\lib" `
     --add-modules javafx.controls,javafx.graphics `
     Example
```

> **Note:** the `CThead` data file must be in the working directory when the program is run. In IntelliJ this is the project root by default.
