# Cellmate

Turns photos into pixel art: borders, rounded, jitter, solid, cross-stitch, Perler beads and
melted beads, in greyscale (2 to 8 tones, with an optional accent colour) or in colour.
Runs on Windows and Android from the same code.

## Download

Every change is built automatically. Open **Releases → Cellmate (latest build)**:

- **Cellmate.apk**: Android. Open it on the device to install (allow installs from your browser or file manager once).
- **Cellmate-Windows.zip**: Windows. Unzip anywhere and run `Cellmate.exe`. Nothing else to install.

## Using it

- **Input**: a folder (subfolders included) or one image. **Output**: where the results go.
- **Styles**: highlight one and press A to choose it. A chosen style shows a white box: that's its
  slider. It fills like a progress bar, split into a section per setting (L/R moves between
  sections, left/right changes the value). Melted also has two dials: rotation and meltiness.
  The small patch next to each style shows its pixels at the real size. X saves a style's
  settings for next time, Y resets them to the defaults.
- Each chosen style gets its own output folder, named after the input plus the style
  (`summer 96 rounded`, `summer 96 melted`), with the same subfolders and file names (as .png).
  A single image goes into a folder named after the style (`melted/beach.png`).
- **Greyscale** (2 to 8 tones) or **Color** (4 to 32 colours): choose one. **Accent** (with
  Greyscale): one of 8 colours stays in colour, the rest goes grey.
- **Preview**: one style at a time; the arrows at the bottom (L2/R2, or [ and ]) page through the
  chosen styles. The original is the small picture in the top-right corner.
  **Preview image** picks which photo the preview uses; **Preview zoom** is Fit (whole picture)
  or 1:1 (the middle at the real saved size). On the AYN Thor the preview is on the second screen.
- **Detail**: pixels across the short side. **Pixel size**: how big each one is when saved.
- **Existing files**: Skip (re-running on a growing album only does the new photos) or Replace.

Keyboard: arrows, Enter = A, Esc = B, Tab or Q/E = L/R, [ ] = L2/R2, X = save, Y = reset.
Mouse and touch: drag a slider or dial; tap a row to highlight it, tap again to choose it.

## Layout

- `core/`: the filters (`cellmate.core`) and the whole interface (`cellmate.ui`), plain Java.
- `desktop/`: the Windows/desktop window.
- `android/`: the Android app.

Greyscale with 2 or 3 tones matches the original `pixel_crt.py` tool pixel for pixel (Border,
Rounded, Solid, Stitch and Beads); Jitter and Melted use the same shapes with a different random
pattern.

The pixel font is GamePocket (SIL Open Font License, see FONT-LICENSE-GamePocket.txt).
