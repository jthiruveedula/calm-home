package com.jay.calmhome;

/**
 * Remote-control focus for three zones: the top buttons, the favourites dock and the app grid.
 * Pure Java on purpose: every rule here is exercised and fuzzed by test/Tests.java on a desktop JVM.
 *
 * Rules: Left/Right never wrap to another row. Up/Down move exactly one row and keep the
 * remembered grid column, landing on the last tile when the next row is shorter.
 */
public final class NavModel {
    public static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;
    public static final int TOP = 0, DOCK = 1, GRID = 2;

    private final int cols;
    private int topCount, dockCount, gridCount;
    private int zone = DOCK;
    private int topIdx, dockIdx, gridIdx;
    private int gridCol;

    public NavModel(int cols) {
        this.cols = Math.max(1, cols);
    }

    public NavModel copy() {
        NavModel m = new NavModel(cols);
        m.topCount = topCount;
        m.dockCount = dockCount;
        m.gridCount = gridCount;
        m.zone = zone;
        m.topIdx = topIdx;
        m.dockIdx = dockIdx;
        m.gridIdx = gridIdx;
        m.gridCol = gridCol;
        return m;
    }

    public void setCounts(int top, int dock, int grid) {
        topCount = Math.max(0, top);
        dockCount = Math.max(0, dock);
        gridCount = Math.max(0, grid);
        topIdx = clamp(topIdx, topCount);
        dockIdx = clamp(dockIdx, dockCount);
        gridIdx = clamp(gridIdx, gridCount);
        gridCol = Math.min(gridCol, cols - 1);
        if (count(zone) == 0) zone = fallbackZone();
    }

    /** Put focus on a specific item, clamped. Ignored when the zone is empty. */
    public void focus(int z, int idx) {
        if (z < TOP || z > GRID || count(z) == 0) return;
        zone = z;
        int i = clamp(idx, count(z));
        if (z == TOP) topIdx = i;
        else if (z == DOCK) dockIdx = i;
        else {
            gridIdx = i;
            gridCol = i % cols;
        }
    }

    /** Move to a zone, keeping that zone's remembered position. */
    public void enter(int z) {
        if (z < TOP || z > GRID || count(z) == 0) return;
        zone = z;
    }

    public int zone() { return zone; }

    public int index() { return zone == TOP ? topIdx : zone == DOCK ? dockIdx : gridIdx; }

    public boolean hasFocus() { return count(zone) > 0; }

    public int cols() { return cols; }

    public int row() { return zone == GRID ? gridIdx / cols : 0; }

    public int count(int z) { return z == TOP ? topCount : z == DOCK ? dockCount : z == GRID ? gridCount : 0; }

    public String state() { return zone + ":" + index() + ":" + gridCol; }

    /** @return true when focus changed. */
    public boolean move(int dir) {
        if (!hasFocus()) return false;
        int z0 = zone, i0 = index();
        if (zone == TOP) moveTop(dir);
        else if (zone == DOCK) moveDock(dir);
        else moveGrid(dir);
        return zone != z0 || index() != i0;
    }

    /** Extra rows to scroll so the focused grid row stays inside a window of visibleRows. */
    public static int scrollRows(int row, int visibleRows) {
        return Math.max(0, row - (Math.max(1, visibleRows) - 1));
    }

    private void moveTop(int dir) {
        if (dir == LEFT) topIdx = clamp(topIdx - 1, topCount);
        else if (dir == RIGHT) topIdx = clamp(topIdx + 1, topCount);
        else if (dir == DOWN) {
            if (dockCount > 0) zone = DOCK;
            else enterGrid();
        }
    }

    private void moveDock(int dir) {
        if (dir == LEFT) dockIdx = clamp(dockIdx - 1, dockCount);
        else if (dir == RIGHT) dockIdx = clamp(dockIdx + 1, dockCount);
        else if (dir == UP) {
            if (topCount > 0) zone = TOP;
        } else if (dir == DOWN) enterGrid();
    }

    private void moveGrid(int dir) {
        int row = gridIdx / cols, col = gridIdx % cols, lastRow = (gridCount - 1) / cols;
        if (dir == LEFT) {
            if (col > 0) {
                gridIdx--;
                gridCol = gridIdx % cols;
            }
        } else if (dir == RIGHT) {
            if (col < cols - 1 && gridIdx + 1 < gridCount) {
                gridIdx++;
                gridCol = gridIdx % cols;
            }
        } else if (dir == DOWN) {
            if (row < lastRow) gridIdx = Math.min((row + 1) * cols + gridCol, gridCount - 1);
        } else if (dir == UP) {
            if (row > 0) gridIdx = (row - 1) * cols + gridCol;
            else if (dockCount > 0) zone = DOCK;
            else if (topCount > 0) zone = TOP;
        }
    }

    private void enterGrid() {
        if (gridCount == 0) return;
        zone = GRID;
        gridIdx = Math.min(gridCol, gridCount - 1);
    }

    private int fallbackZone() {
        if (dockCount > 0) return DOCK;
        if (gridCount > 0) return GRID;
        if (topCount > 0) return TOP;
        return DOCK;
    }

    private static int clamp(int i, int n) {
        return n <= 0 ? 0 : Math.max(0, Math.min(i, n - 1));
    }
}
