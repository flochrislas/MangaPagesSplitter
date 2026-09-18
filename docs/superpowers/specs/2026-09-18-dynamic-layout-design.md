# Dynamic window layout

Date: 2026-09-18. Scope: `MangaPagesSplitterUI.java` layout only; no behaviour change.

## Problem
Every section, spinner and pane has a hard-coded pixel size (`createSectionPanel(title, 280, h)`,
`createFixedHeightPanel(h)`, `new Dimension(65, 24)`, `setSize(900, 975)`). The options column
is a BoxLayout with no scroll pane, so the last section is clipped at the default window size,
and the cropping redesign had to compromise ("px" labels and the "Sensitivity" caption dropped).

## Design
- Components report their own preferred size. Layouts fill available space. The only hard
  values are minimums, derived from content (font metrics, preferred sizes), never typed.
- Window: `pack()`, then cap to `GraphicsEnvironment.getMaximumWindowBounds()`, centre.
  Minimum size = options column preferred width + right-pane minimum width, and 60 % of the
  packed content height. Small screens scroll the options column.
- Options column: content-sized titled sections that stretch horizontally only, inside a
  vertical-only `JScrollPane`. Rows use `GridBagLayout`. `createSectionPanel(title)` has no
  size parameters. `createFixedHeightPanel` is deleted.
- Horizontal `JSplitPane` (options | right panes), divider at column preferred width,
  resize weight 0, continuous layout, one-touch expandable off.
- Spinners: `setColumns(4)` on the editor text field, no pixel size. Sensitivity value label
  reserves the width of "10" via font metrics.
- Cropping: restore per-field `px` labels (radio text back to "Manual margins") and the
  "Sensitivity" caption line.
- Location and Title text fields stretch to their row.
- Right side: Input Files / Process vertical split keeps resize weight 0.3; each pane's
  minimum height = 4 text lines of its font plus insets.

## Testing
Screenshot harness at default, minimum and large sizes in the three cropping modes.
`mvn test` stays green.
