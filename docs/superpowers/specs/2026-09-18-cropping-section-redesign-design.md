# Image Cropping section redesign

Date: 2026-09-18
Scope: the "Image Cropping" section of the left options column in
`src/main/java/mangapagessplitter/ui/MangaPagesSplitterUI.java`. No other
section, no engine code.

## Problem

The section presents two mutually exclusive features (smart autocrop and manual
margins) as if they stacked: a checkbox followed by four spinners. The rule is
only discoverable by clicking, because it is expressed solely through disabled
states. The footnote "Values in pixels. 0 means no cropping." sits under the
whole section although it only applies to manual margins. Rows are FlowLayout
panels with hard-coded heights, so labels and spinners do not align.

## Design

### Structure

Title: `Image Cropping`. The former suffix "(applied before splitting)" moves to
the section's tooltip; the processing preview already lists the order of steps.

A `ButtonGroup` with three `JRadioButton`s, top to bottom:

1. `No cropping` (selected by default)
2. `Smart autocrop`
   - indented block: label `Sensitivity`, a `JSlider` (min 1, max 10,
     initial 5, snap to ticks, no tick marks painted), end labels
     `Conservative` (left) and `Aggressive` (right), and a value label to the
     right of the slider showing the current integer.
   - The existing tooltips stay: the feature description on the radio, the
     "1 = conservative, 10 = aggressive" text on the slider.
3. `Manual margins`
   - indented block: the four existing spinners laid out as a cross so each
     field sits on the edge it crops:
     ```
                Top  [ 0 ] px
     Left [ 0 ] px      Right [ 0 ] px
             Bottom  [ 0 ] px
     ```
   - A `px` label after each spinner replaces the italic footnote.

The section uses `GridBagLayout` and is sized by its content: no
`setPreferredSize`/`setMaximumSize` with fixed heights for this section, no
`createFixedHeightPanel`. The section keeps `createSectionPanel`'s titled etched
border and left alignment so it matches its neighbours; width stays 280 so the
column does not shift.

### Behaviour

- Selecting a radio enables only that mode's controls. The other mode's controls
  are disabled but keep their values, so switching back restores what the user
  entered.
- `smartAutoCrop` is true iff the Smart autocrop radio is selected.
  `setManualCropEnabled` is driven by the Manual margins radio. Both radio
  changes and slider changes call `updatePreview()`.
- `setProcessingState` disables all three radios, the slider and the spinners
  while processing, and restores them according to the selected mode
  afterwards (same rule as today's `manualCropAllowed`).
- Start Processing keeps the existing rule: manual values are passed to the
  engine only when Manual margins is selected, otherwise 0. `BatchProcessor`,
  `AutoCrop` and `ProcessingOptions` are unchanged.
- Processing preview and log wording:
  - `Cropping: none`
  - `Cropping: smart autocrop (sensitivity N)`
  - `Cropping: manual, Left=Lpx, Right=Rpx, Top=Tpx, Bottom=Bpx`
  When Manual margins is selected but all four values are 0 the preview says
  `Cropping: manual, all margins 0 (no effect)`.

### Fields removed / added

- Removed: `smartAutoCropCheckbox`, `smartAutoCropSensitivitySpinner`.
- Added: `noCropRadio`, `smartAutoCropRadio`, `manualCropRadio`,
  `smartAutoCropSensitivitySlider`, `sensitivityValueLabel`.

## Testing

The UI class has no automated tests and none are added. Verification is
visual: build the fat JAR, launch the app, screenshot the section in each of
the three modes and compare with the current screenshot. `mvn test` must still
pass (it does not touch the UI).

## Out of scope

Other left-column sections, persistence of settings, engine behaviour.
