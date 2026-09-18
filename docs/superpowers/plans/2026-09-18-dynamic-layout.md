# Dynamic Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Remove every hard-coded pixel size from the main window, size everything from content, add a scrollable options column behind a horizontal split pane, and fit the default window to the screen.

**Architecture:** All in `src/main/java/mangapagessplitter/ui/MangaPagesSplitterUI.java`. `layoutComponents()` builds north/options/right/south; section builders return content-sized panels; the constructor packs and caps the frame.

**Tech Stack:** Java 17, Swing, FlatLaf.

## Global Constraints
- No commits (user rule). `JAVA_HOME=/usr/lib/jvm/jdk-25 mvn -q test` green, no new `-Xlint` warnings.
- Spec: `docs/superpowers/specs/2026-09-18-dynamic-layout-design.md`.

### Task 1: Section helpers
- [ ] Replace `createSectionPanel(String, int, int)` with `createSectionPanel(String title)` returning a `JPanel` with `GridBagLayout`, titled etched border, and a `getMaximumSize()` override returning `(Integer.MAX_VALUE, preferredHeight)`.
- [ ] Add `addRow(JPanel section, Component... parts)` helper: adds parts on one `GridBagLayout` row; first part anchored WEST, last text field/slider fills horizontally.
- [ ] Delete `createFixedHeightPanel`.

### Task 2: Options column
- [ ] Rebuild each of the 8 sections with `createSectionPanel(title)` and `addRow`; spinners use `((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(4)`; remove `setPreferredSize(spinnerSize)`.
- [ ] Cropping: radio text "Manual margins", `px` labels back (tracked in `marginUnitLabels`, toggled in `setManualCropEnabled`), "Sensitivity" caption row back, value label width from `getFontMetrics(getFont()).stringWidth("10")`.
- [ ] Column = `JPanel` with `GridBagLayout`, sections stacked with `weightx=1, fill=HORIZONTAL`, trailing glue row `weighty=1`; wrapped in `JScrollPane(VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER)`, border-less, unit increment 16.

### Task 3: Split panes and frame
- [ ] Horizontal `JSplitPane(options scroll pane, right panel)`, `setResizeWeight(0)`, `setContinuousLayout(true)`, divider at options preferred width; options scroll pane minimum width = its preferred width.
- [ ] Right side: drop fixed sizes; `inputFilesPanel`/`logPanel` via `createSectionPanel` with `BorderLayout`; scroll panes get `setMinimumSize` = 4 × line height.
- [ ] North: `GridBagLayout`, Location field `weightx=1, fill=HORIZONTAL`.
- [ ] Constructor: remove `setSize/setMinimumSize`; after `layoutComponents()` call `pack()`, cap to `GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()`, `setMinimumSize(new Dimension(optionsWidth + rightMinWidth, packedHeight * 3 / 5))`, `setLocationRelativeTo(null)`.

### Task 4: Verify
- [ ] `mvn -q test`; run `target/shot/Shot.java` extended to capture default, minimum and 1400×1000 sizes; inspect.
- [ ] CHANGELOG `[Unreleased]` "Changed" entry. Do not commit.
