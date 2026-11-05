# Cellmate

Turns photos into pixel art: borders, rounded, jitter, solid, cross-stitch, Perler beads and
melted beads, in greyscale (2 to 8 tones, with an optional accent colour) or in colour.
Runs on Windows and Android from the same code.

## Download

Every change is built automatically. Open **Releases → Cellmate (latest build)**:

- **Cellmate.apk**: Android. Open it on the device to install (allow installs from your browser or file manager once).
- **Cellmate-Windows.zip**: Windows. Unzip anywhere and run `Cellmate.exe`. Nothing else to install.

## Using it

- **Input folder** or **Input image**: what to convert. Folders include all subfolders.
- **Output folder**: where the results go.
- Turn on any number of styles. Each style gets its own folder, named after the input plus the
  style: converting `summer 96` with Rounded and Melted makes `summer 96 rounded` and
  `summer 96 melted`, each with the same subfolders and the same file names (as .png).
  A single image goes into a folder named after the style (`melted/beach.png`).
- **Mode**: Greyscale (choose 2 to 8 grey tones) or Color (choose how many colours).
- **Accent color** (greyscale): one colour from the photo stays in colour, the rest goes grey.
  Pick a preset, or tap/click the preview to take the colour from the photo.
- **Detail**: how many pixels across the short side of the picture.
- **Pixel size**: how big each pixel is in the saved image.
- **Existing files**: Skip (re-running on a growing album only does the new photos) or Replace.
- The preview shows the highlighted style on the chosen preview image. On a device with a second
  screen (AYN Thor) the preview moves to the other screen.

Controls: arrows / D-pad to move, left/right to change, Enter / A to select, Esc / B to go back.
Mouse and touch: tap a row to highlight it, tap again to use or change it.

## Layout

- `core/`: the filters (`cellmate.core`) and the whole interface (`cellmate.ui`), plain Java.
- `desktop/`: the Windows/desktop window.
- `android/`: the Android app.

Greyscale with 2 or 3 tones matches the original `pixel_crt.py` tool pixel for pixel (Border,
Rounded, Solid, Stitch and Beads); Jitter and Melted use the same shapes with a different random
pattern.

The pixel font is GamePocket (SIL Open Font License, see FONT-LICENSE-GamePocket.txt).
