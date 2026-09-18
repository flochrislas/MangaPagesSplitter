# Image Cropping Section Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the checkbox-plus-spinners cropping section with three radio modes (none / smart autocrop with a sensitivity slider / manual margins laid out as a cross) so the mutual exclusivity is visible.

**Architecture:** All changes are in `src/main/java/mangapagessplitter/ui/MangaPagesSplitterUI.java`. The section is built by a new `createCropSection()` method using `GridBagLayout`; the mode radios drive the existing `smartAutoCrop` flag and `setManualCropEnabled`, so `BatchProcessor`/`BatchOptions` are untouched.

**Tech Stack:** Java 17, Swing, FlatLaf, Maven.

## Global Constraints

- Width of the section stays 280 px (other sections use `createSectionPanel(title, 280, h)`).
- Do not commit: the user commits themselves (global rule). Leave changes in the working tree.
- `mvn -q test` must stay green; `mvn -q -Xlint` warnings must not increase (compiler runs `-Xlint:all`).
- Spec: `docs/superpowers/specs/2026-09-18-cropping-section-redesign-design.md`.

---

### Task 1: Fields and component creation

**Files:**
- Modify: `src/main/java/mangapagessplitter/ui/MangaPagesSplitterUI.java:41-49` (fields) and `:215-237` (`initializeComponents`)

**Produces:** fields `noCropRadio`, `smartAutoCropRadio`, `manualCropRadio` (`JRadioButton`), `smartAutoCropSensitivitySlider` (`JSlider`), `sensitivityValueLabel` (`JLabel`). Removes `smartAutoCropCheckbox` and `smartAutoCropSensitivitySpinner`.

- [ ] **Step 1: Replace the smart autocrop fields**

```java
    // Cropping mode: exactly one of the three radios is selected
    private JRadioButton noCropRadio, smartAutoCropRadio, manualCropRadio;
    private JSlider smartAutoCropSensitivitySlider;
    private JLabel sensitivityValueLabel;
    private boolean smartAutoCrop = false;
    private int smartAutoCropSensitivity = 5;
```

- [ ] **Step 2: Replace the "Smart autocrop" block in `initializeComponents`**

```java
        // Cropping mode
        ButtonGroup cropModeGroup = new ButtonGroup();
        noCropRadio = new JRadioButton("No cropping", true);
        smartAutoCropRadio = new JRadioButton("Smart autocrop");
        smartAutoCropRadio.setToolTipText(
            "<html>Detects uniform white/black scan borders around a page and trims them.<br>"
            + "For landscape double-page spreads it also detects the spine so the split<br>"
            + "cut lands exactly on the seam instead of at width / 2.</html>");
        manualCropRadio = new JRadioButton("Manual margins");
        manualCropRadio.setToolTipText("Remove a fixed number of pixels from each edge of every image.");
        cropModeGroup.add(noCropRadio);
        cropModeGroup.add(smartAutoCropRadio);
        cropModeGroup.add(manualCropRadio);

        smartAutoCropSensitivitySlider = new JSlider(1, 10, 5);
        smartAutoCropSensitivitySlider.setSnapToTicks(true);
        smartAutoCropSensitivitySlider.setMajorTickSpacing(1);
        smartAutoCropSensitivitySlider.setPaintTicks(false);
        smartAutoCropSensitivitySlider.setEnabled(false);
        smartAutoCropSensitivitySlider.setToolTipText(
            "1 = conservative (only trims very clean margins), 10 = aggressive.");
        sensitivityValueLabel = new JLabel("5");
        sensitivityValueLabel.setEnabled(false);
```

Also set the four crop spinners `setEnabled(false)` right after they are created (No cropping is the default).

- [ ] **Step 3: Compile**

Run: `cd ~/code/MangaPagesSplitter && mvn -q compile`
Expected: errors only about the removed `smartAutoCropCheckbox` / `smartAutoCropSensitivitySpinner` (fixed in Tasks 2 and 3).

### Task 2: Layout with GridBagLayout

**Files:**
- Modify: `MangaPagesSplitterUI.java:298-338` (crop panel construction in `layoutComponents`), add `createCropSection()` next to `createSectionPanel`.

- [ ] **Step 1: Replace lines 298-336 (from `// Add crop options panel first` up to and including `cropPanel.add(infoPanel);`) with**

```java
        JPanel cropPanel = createCropSection();
```

- [ ] **Step 2: Add the builder after `createSectionPanel`**

```java
    /**
     * Builds the "Image Cropping" section: three exclusive modes, each with its
     * own controls indented underneath. Sized by content, no fixed height.
     */
    private JPanel createCropSection() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "Image Cropping"));
        panel.setToolTipText("Cropping is applied to every image before splitting.");
        panel.setAlignmentX(LEFT_ALIGNMENT);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 1;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 4, 0, 4);

        int row = 0;
        c.gridy = row++;
        panel.add(noCropRadio, c);

        c.gridy = row++;
        panel.add(smartAutoCropRadio, c);

        c.gridy = row++;
        c.insets = new Insets(0, 24, 4, 4);
        panel.add(createSensitivityRow(), c);

        c.gridy = row++;
        c.insets = new Insets(0, 4, 0, 4);
        panel.add(manualCropRadio, c);

        c.gridy = row++;
        c.insets = new Insets(0, 24, 6, 4);
        c.fill = GridBagConstraints.NONE;
        panel.add(createMarginsCross(), c);

        Dimension pref = panel.getPreferredSize();
        Dimension size = new Dimension(280, pref.height);
        panel.setPreferredSize(size);
        panel.setMinimumSize(size);
        panel.setMaximumSize(size);
        return panel;
    }

    /** "Sensitivity" label, then Conservative — slider — Aggressive, then the value. */
    private JPanel createSensitivityRow() {
        JPanel row = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 0, 4);

        c.gridx = 0; c.gridwidth = 4;
        row.add(new JLabel("Sensitivity"), c);

        Font small = UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 10f);
        JLabel lo = new JLabel("Conservative");
        lo.setFont(small);
        JLabel hi = new JLabel("Aggressive");
        hi.setFont(small);

        c.gridy = 1; c.gridwidth = 1;
        c.gridx = 0;
        row.add(lo, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        row.add(smartAutoCropSensitivitySlider, c);
        c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        row.add(hi, c);
        c.gridx = 3;
        sensitivityValueLabel.setPreferredSize(new Dimension(18, sensitivityValueLabel.getPreferredSize().height));
        sensitivityValueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(sensitivityValueLabel, c);
        return row;
    }

    /** Top / Left Right / Bottom spinners arranged like the edges they crop. */
    private JPanel createMarginsCross() {
        JPanel cross = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(1, 2, 1, 2);
        c.anchor = GridBagConstraints.WEST;

        addMargin(cross, c, "Top", cropTopSpinner, 2, 0);
        addMargin(cross, c, "Left", cropLeftSpinner, 0, 1);
        addMargin(cross, c, "Right", cropRightSpinner, 4, 1);
        addMargin(cross, c, "Bottom", cropBottomSpinner, 2, 2);
        return cross;
    }

    private void addMargin(JPanel target, GridBagConstraints c, String name, JSpinner spinner,
                           int gridx, int gridy) {
        JLabel label = new JLabel(name + ":");
        label.setLabelFor(spinner);
        JLabel unit = new JLabel("px");
        unit.setEnabled(spinner.isEnabled());
        c.gridy = gridy;
        c.gridx = gridx;     c.anchor = GridBagConstraints.EAST; target.add(label, c);
        c.gridx = gridx + 1; c.anchor = GridBagConstraints.WEST; target.add(spinner, c);
        c.gridx = gridx + 2; target.add(unit, c);
    }
```

Note: `addMargin` uses three grid columns per field (label, spinner, unit). Top and Bottom start at column 2 so they sit between Left (columns 0-2) and Right (columns 4-6); with `gridwidth` 1 the label of Top lands over Left's unit column, which is acceptable visually because Left/Right occupy the middle row only. If Top does not look centred, change Left to `gridx 0` and Right to `gridx 6`, Top/Bottom to `gridx 3`.

Enabling of the `px` unit labels: they are simple labels whose enabled state must follow the spinners. Store them in a `java.util.List<JLabel> marginUnitLabels` field, add each in `addMargin`, and toggle them in `setManualCropEnabled` (Task 3).

- [ ] **Step 3: Compile**

Run: `mvn -q compile`. Expected: remaining errors only in the listener / processing-state code (Task 3).

### Task 3: Behaviour, processing state, preview wording

**Files:**
- Modify: `MangaPagesSplitterUI.java` `setupEventHandlers` (~700-713), `setManualCropEnabled` (~715), `updatePreview` (~977-985, ~1015-1023), `startProcessing` (~1077-1087, ~1141-1148), `setProcessingState` (~1240-1248).

- [ ] **Step 1: Replace the smart autocrop listeners**

```java
        // Cropping mode
        ActionListener cropModeListener = e -> applyCropMode();
        noCropRadio.addActionListener(cropModeListener);
        smartAutoCropRadio.addActionListener(cropModeListener);
        manualCropRadio.addActionListener(cropModeListener);
        smartAutoCropSensitivitySlider.addChangeListener(e -> {
            smartAutoCropSensitivity = smartAutoCropSensitivitySlider.getValue();
            sensitivityValueLabel.setText(Integer.toString(smartAutoCropSensitivity));
            updatePreview();
        });
```

and replace `setManualCropEnabled` with:

```java
    /** Enables the controls of the selected cropping mode only; keeps values of the others. */
    private void applyCropMode() {
        smartAutoCrop = smartAutoCropRadio.isSelected();
        boolean manual = manualCropRadio.isSelected();
        smartAutoCropSensitivitySlider.setEnabled(smartAutoCrop);
        sensitivityValueLabel.setEnabled(smartAutoCrop);
        setManualCropEnabled(manual);
        updatePreview();
    }

    private void setManualCropEnabled(boolean enabled) {
        cropLeftSpinner.setEnabled(enabled);
        cropRightSpinner.setEnabled(enabled);
        cropTopSpinner.setEnabled(enabled);
        cropBottomSpinner.setEnabled(enabled);
        for (JLabel unit : marginUnitLabels) {
            unit.setEnabled(enabled);
        }
    }
```

- [ ] **Step 2: Preview wording in `updatePreview`** (first block, the summary)

```java
        // Crop information
        if (smartAutoCrop) {
            text.append("Cropping: smart autocrop (sensitivity ").append(smartAutoCropSensitivity).append(")\n");
        } else if (manualCropRadio.isSelected()) {
            if (cropLeft > 0 || cropRight > 0 || cropTop > 0 || cropBottom > 0) {
                text.append("Cropping: manual, Left=" + cropLeft + "px, Right=" + cropRight
                    + "px, Top=" + cropTop + "px, Bottom=" + cropBottom + "px\n");
            } else {
                text.append("Cropping: manual, all margins 0 (no effect)\n");
            }
        } else {
            text.append("Cropping: none\n");
        }
```

The second block ("THE PROGRAM WILL") keeps its logic: smart line when `smartAutoCrop`, crop line when manual and any value > 0.

- [ ] **Step 3: `startProcessing`**

Replace `smartAutoCrop = smartAutoCropCheckbox.isSelected();` and the spinner read with

```java
        smartAutoCrop = smartAutoCropRadio.isSelected();
        smartAutoCropSensitivity = smartAutoCropSensitivitySlider.getValue();
        boolean manualCrop = manualCropRadio.isSelected();
        int effectiveCropLeft   = manualCrop ? cropLeft : 0;
        int effectiveCropRight  = manualCrop ? cropRight : 0;
        int effectiveCropTop    = manualCrop ? cropTop : 0;
        int effectiveCropBottom = manualCrop ? cropBottom : 0;
```

and the log line `publish("Smart autocrop: enabled (sensitivity=" ...` becomes `publish("Cropping: smart autocrop (sensitivity " + smartAutoCropSensitivity + ")");`, the manual one becomes `publish("Cropping: manual, Left=..." )` with the same values as before.

- [ ] **Step 4: `setProcessingState`**

```java
        noCropRadio.setEnabled(!processing);
        smartAutoCropRadio.setEnabled(!processing);
        manualCropRadio.setEnabled(!processing);
        smartAutoCropSensitivitySlider.setEnabled(!processing && smartAutoCrop);
        sensitivityValueLabel.setEnabled(!processing && smartAutoCrop);
        setManualCropEnabled(!processing && manualCropRadio.isSelected());
```

- [ ] **Step 5: Compile and test**

Run: `mvn -q test`. Expected: BUILD SUCCESS, no new lint warnings.

### Task 4: Visual verification

- [ ] **Step 1: Build the JAR**: `mvn -q package -DskipTests` (jpackage may fail on Linux; the fat JAR under `target/` is what matters, or use `mvn -q compile exec:java` if simpler: `java -cp target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) mangapagessplitter.Main`).
- [ ] **Step 2: Launch, screenshot the left column with each radio selected** (use `gnome-screenshot -w` or the screen-recording-wayland skill's frame grab). Check: labels aligned, slider readable, cross layout centred, disabled controls visibly dimmed, section height not truncated, no overlap with the "Image Splitting Options" section.
- [ ] **Step 3: Fix spacing issues found, rebuild, re-screenshot.**
- [ ] **Step 4: Add a CHANGELOG `[Unreleased]` line**: "Cropping section reworked: explicit No cropping / Smart autocrop / Manual margins modes, sensitivity slider, margins laid out by edge."
- [ ] **Step 5: Do not commit.** Report the changed files.
