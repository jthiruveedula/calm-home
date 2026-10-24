import com.jay.calmhome.AppEntry;
import com.jay.calmhome.AppOrder;
import com.jay.calmhome.Ambient;
import com.jay.calmhome.NavModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/** Dependency-free test runner for the pure-Java core. Exit code 1 on any failure. */
public final class Tests {
    private static int run, failed;

    public static void main(String[] args) {
        navBasics();
        navEdges();
        navEmptyAndRecount();
        navReachability();
        navFuzz();
        navScroll();
        orderSplit();
        orderPins();
        orderToggle();
        ambientSky();
        ambientQuiet();
        ambientGreeting();
        ambientGlow();
        System.out.println(run + " checks, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(boolean ok, String what) {
        run++;
        if (!ok) {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(Object got, Object want, String what) {
        check(Objects.equals(got, want), what + " (want " + want + ", got " + got + ")");
    }

    // ---- NavModel -------------------------------------------------------------------------

    static NavModel nav(int cols, int top, int dock, int grid) {
        NavModel m = new NavModel(cols);
        m.setCounts(top, dock, grid);
        return m;
    }

    static void navBasics() {
        NavModel m = nav(6, 1, 4, 14);
        eq(m.zone(), NavModel.DOCK, "starts in dock");
        eq(m.index(), 0, "starts at first favourite");
        check(m.move(NavModel.RIGHT) && m.move(NavModel.RIGHT) && m.move(NavModel.RIGHT), "dock right x3");
        eq(m.index(), 3, "dock index after right x3");
        check(!m.move(NavModel.RIGHT), "dock right at end does not move");
        check(m.move(NavModel.UP), "dock up to top");
        eq(m.zone(), NavModel.TOP, "top zone");
        check(m.move(NavModel.DOWN), "top down");
        eq(m.state(), NavModel.DOCK + ":3:0", "dock remembers its position");
        check(m.move(NavModel.DOWN), "dock down to grid");
        eq(m.zone(), NavModel.GRID, "grid zone");
        eq(m.index(), 0, "grid enters at remembered column 0");
        m.move(NavModel.RIGHT);
        m.move(NavModel.RIGHT);
        eq(m.index(), 2, "grid right x2");
        m.move(NavModel.DOWN);
        eq(m.index(), 8, "grid down one row");
        m.move(NavModel.DOWN);
        eq(m.index(), 13, "grid down onto short last row lands on last tile");
        m.move(NavModel.UP);
        eq(m.index(), 8, "grid up restores remembered column");
        m.move(NavModel.UP);
        eq(m.index(), 2, "grid up to row 0");
        m.move(NavModel.UP);
        eq(m.zone(), NavModel.DOCK, "grid row 0 up goes to dock");
        m.move(NavModel.DOWN);
        eq(m.index(), 2, "grid re-entry keeps the column");
    }

    static void navEdges() {
        NavModel m = nav(6, 1, 2, 14);
        m.focus(NavModel.GRID, 6);
        check(!m.move(NavModel.LEFT), "no wrap left from column 0");
        m.focus(NavModel.GRID, 5);
        check(!m.move(NavModel.RIGHT), "no wrap right from last column");
        m.focus(NavModel.GRID, 13);
        check(!m.move(NavModel.RIGHT), "no right past the last tile");
        check(!m.move(NavModel.DOWN), "no down from the last row");
        m.focus(NavModel.GRID, 99);
        eq(m.index(), 13, "focus clamps high");
        m.focus(NavModel.GRID, -5);
        eq(m.index(), 0, "focus clamps low");
        m.focus(7, 0);
        eq(m.zone(), NavModel.GRID, "invalid zone ignored");
        NavModel one = nav(1, 0, 0, 3);
        one.move(NavModel.DOWN);
        one.move(NavModel.DOWN);
        eq(one.index(), 2, "single column walks down");
        check(!one.move(NavModel.RIGHT), "single column no right");
        NavModel t = nav(6, 3, 1, 0);
        t.move(NavModel.UP);
        t.move(NavModel.RIGHT);
        t.move(NavModel.RIGHT);
        t.move(NavModel.RIGHT);
        eq(t.index(), 2, "top buttons clamp right");
        NavModel back = nav(6, 1, 3, 20);
        back.focus(NavModel.DOCK, 2);
        back.focus(NavModel.GRID, 15);
        back.enter(NavModel.DOCK);
        eq(back.state(), NavModel.DOCK + ":2:3", "enter() keeps dock position (Back from grid)");
    }

    static void navEmptyAndRecount() {
        NavModel none = nav(6, 0, 0, 0);
        check(!none.hasFocus(), "empty has no focus");
        for (int d = 0; d < 4; d++) check(!none.move(d), "empty never moves");
        NavModel onlyTop = nav(6, 1, 0, 0);
        eq(onlyTop.zone(), NavModel.TOP, "only settings: focus on top");
        check(!onlyTop.move(NavModel.DOWN), "top down with nothing below");
        NavModel noDock = nav(6, 1, 0, 5);
        eq(noDock.zone(), NavModel.GRID, "no favourites: starts in grid");
        noDock.move(NavModel.UP);
        eq(noDock.zone(), NavModel.TOP, "grid up skips empty dock");
        noDock.move(NavModel.DOWN);
        eq(noDock.zone(), NavModel.GRID, "top down skips empty dock");
        NavModel m = nav(6, 1, 4, 14);
        m.focus(NavModel.GRID, 13);
        m.setCounts(1, 4, 3);
        eq(m.state(), NavModel.GRID + ":2:1", "grid shrink clamps index");
        m.setCounts(1, 4, 0);
        eq(m.zone(), NavModel.DOCK, "grid emptied: falls back to dock");
        m.setCounts(1, 0, 0);
        eq(m.zone(), NavModel.TOP, "everything emptied: falls back to top");
        m.setCounts(1, 2, 2);
        eq(m.zone(), NavModel.TOP, "refill keeps a valid zone");
    }

    /** Every tile must be reachable from the start, in every layout up to these sizes. */
    static void navReachability() {
        int layouts = 0;
        for (int cols = 1; cols <= 7; cols++)
            for (int top = 0; top <= 2; top++)
                for (int dock = 0; dock <= 6; dock++)
                    for (int grid = 0; grid <= 22; grid++) {
                        NavModel start = nav(cols, top, dock, grid);
                        Set<String> seenStates = new HashSet<>();
                        Set<String> seenItems = new HashSet<>();
                        ArrayDeque<NavModel> q = new ArrayDeque<>();
                        q.add(start);
                        seenStates.add(start.state());
                        while (!q.isEmpty()) {
                            NavModel cur = q.poll();
                            if (cur.hasFocus()) seenItems.add(cur.zone() + ":" + cur.index());
                            for (int d = 0; d < 4; d++) {
                                NavModel n = cur.copy();
                                n.move(d);
                                if (seenStates.add(n.state())) q.add(n);
                            }
                        }
                        int want = top + dock + grid;
                        if (seenItems.size() != want) {
                            check(false, "reachability cols=" + cols + " top=" + top + " dock=" + dock
                                    + " grid=" + grid + " reached " + seenItems.size() + "/" + want);
                        }
                        layouts++;
                    }
        check(true, "reachability over " + layouts + " layouts");
    }

    /** Random layouts and key mashing: indices stay in range and Left/Right undo each other. */
    static void navFuzz() {
        Random rnd = new Random(42);
        int bad = 0;
        for (int t = 0; t < 3000 && bad < 5; t++) {
            int cols = 1 + rnd.nextInt(8);
            NavModel m = nav(cols, rnd.nextInt(3), rnd.nextInt(7), rnd.nextInt(40));
            for (int k = 0; k < 400; k++) {
                if (rnd.nextInt(50) == 0) m.setCounts(rnd.nextInt(3), rnd.nextInt(7), rnd.nextInt(40));
                int d = rnd.nextInt(4);
                int z = m.zone(), i = m.index();
                boolean moved = m.move(d);
                boolean anyItems = m.count(NavModel.TOP) + m.count(NavModel.DOCK) + m.count(NavModel.GRID) > 0;
                if (anyItems && !m.hasFocus()) { bad++; check(false, "lost focus with items present"); }
                if (m.hasFocus() && (m.index() < 0 || m.index() >= m.count(m.zone()))) {
                    bad++;
                    check(false, "index out of range " + m.state());
                }
                if (moved && (d == NavModel.LEFT || d == NavModel.RIGHT) && m.zone() == z) {
                    NavModel back = m.copy();
                    back.move(d == NavModel.LEFT ? NavModel.RIGHT : NavModel.LEFT);
                    if (back.index() != i) { bad++; check(false, "left/right not reversible at " + z + ":" + i); }
                }
            }
        }
        check(bad == 0, "fuzz: 3000 layouts x 400 keys");
    }

    static void navScroll() {
        eq(NavModel.scrollRows(0, 4), 0, "row 0 no scroll");
        eq(NavModel.scrollRows(3, 4), 0, "row 3 still visible");
        eq(NavModel.scrollRows(4, 4), 1, "row 4 scrolls one");
        eq(NavModel.scrollRows(9, 4), 6, "row 9 scrolls six");
        eq(NavModel.scrollRows(2, 0), 2, "visibleRows clamps to 1");
    }

    // ---- AppOrder -------------------------------------------------------------------------

    static AppEntry app(String pkg, String label) {
        return new AppEntry(pkg, pkg + ".Main", label, true);
    }

    static List<String> pkgs(List<AppEntry> list) {
        List<String> out = new ArrayList<>();
        for (AppEntry e : list) out.add(e.pkg);
        return out;
    }

    static void orderSplit() {
        List<AppEntry> apps = Arrays.asList(
                app("com.b", "Beta"), app("com.a", "alpha"), app("com.a", "Alpha duplicate"),
                app("com.netflix.ninja", "Netflix"), app("com.z", "Zed"), null,
                app("com.amazon.tv.launcher", "Home"), app("com.blank", "   "));
        List<String> pins = Arrays.asList("com.missing", "com.netflix.ninja", "com.z");
        AppOrder.Split one = AppOrder.split(apps, pins, 1);
        eq(pkgs(one.dock), Arrays.asList("com.netflix.ninja"), "dock respects max");
        eq(pkgs(one.grid), Arrays.asList("com.a", "com.b", "com.blank", "com.z"), "grid sorted, deduped, hidden removed");
        AppOrder.Split five = AppOrder.split(apps, pins, 5);
        eq(pkgs(five.dock), Arrays.asList("com.netflix.ninja", "com.z"), "dock in pin order, missing skipped");
        eq(pkgs(five.grid), Arrays.asList("com.a", "com.b", "com.blank"), "pinned apps leave the grid");
        eq(five.grid.get(0).label, "alpha", "first duplicate wins");
        eq(five.grid.get(2).label, "com.blank", "blank label falls back to package");
        AppOrder.Split empty = AppOrder.split(Collections.<AppEntry>emptyList(), pins, 5);
        check(empty.dock.isEmpty() && empty.grid.isEmpty(), "no apps, no tiles");
        List<AppEntry> defaults = Arrays.asList(app("com.apple.atve.amazon.appletv", "Apple TV"),
                app("com.amazon.firetv.youtube", "YouTube"), app("com.amazon.firebat", "Prime Video"),
                app("com.netflix.ninja", "Netflix"));
        eq(pkgs(AppOrder.split(defaults, AppOrder.parsePins(AppOrder.DEFAULT_PINS), 5).dock),
                Arrays.asList("com.netflix.ninja", "com.amazon.firebat", "com.amazon.firetv.youtube",
                        "com.apple.atve.amazon.appletv"), "default dock: Netflix, Prime, YouTube, Apple TV");
    }

    static void orderPins() {
        eq(AppOrder.parsePins(" a.b , junk, a.b, c.d,,; rm -rf /, x.y.z ,9.bad, ok_1.v2"),
                Arrays.asList("a.b", "c.d", "x.y.z", "ok_1.v2"), "parse drops junk and duplicates");
        eq(AppOrder.parsePins(null), Collections.<String>emptyList(), "parse null");
        eq(AppOrder.parsePins(""), Collections.<String>emptyList(), "parse empty");
        List<String> p = Arrays.asList("a.b", "c.d");
        eq(AppOrder.parsePins(AppOrder.joinPins(p)), p, "join/parse round trip");
        eq(AppOrder.joinPins(Collections.<String>emptyList()), "", "join empty");
    }

    static void orderToggle() {
        List<AppEntry> dock = Arrays.asList(app("com.netflix.ninja", "Netflix"));
        List<String> pins = Arrays.asList("com.missing", "com.netflix.ninja");
        eq(AppOrder.togglePin(pins, dock, "com.netflix.ninja", 5), Arrays.asList("com.missing"), "unpin");
        eq(AppOrder.togglePin(pins, dock, "com.z", 5), Arrays.asList("com.missing", "com.netflix.ninja", "com.z"), "pin appends");
        List<AppEntry> full = Arrays.asList(app("a.a", "A"), app("b.b", "B"), app("c.c", "C"), app("d.d", "D"), app("e.e", "E"));
        check(AppOrder.togglePin(Arrays.asList("a.a", "b.b", "c.c", "d.d", "e.e"), full, "f.f", 5) == null, "full dock refuses");
        eq(AppOrder.togglePin(Arrays.asList("a.a", "b.b", "c.c", "d.d", "e.e"), full, "c.c", 5),
                Arrays.asList("a.a", "b.b", "d.d", "e.e"), "full dock still unpins");
        eq(AppOrder.togglePin(Arrays.asList("x.x", "x.x"), Collections.<AppEntry>emptyList(), "x.x", 5),
                Arrays.asList("x.x"), "pin of a stale entry does not duplicate");
    }

    // ---- Ambient --------------------------------------------------------------------------

    static void ambientSky() {
        eq(Ambient.skyTint(0), 0x2A1F5C, "midnight");
        eq(Ambient.skyTint(1110), 0x8A4B2E, "dusk keyframe");
        eq(Ambient.skyTint(1440), Ambient.skyTint(0), "wraps at 24h");
        eq(Ambient.skyTint(-60), Ambient.skyTint(1380), "negative wraps");
        int worst = 0;
        for (int m = 0; m < 1440; m++) {
            int a = Ambient.skyTint(m), b = Ambient.skyTint(m + 1);
            for (int sh = 0; sh <= 16; sh += 8) worst = Math.max(worst, Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)));
        }
        check(worst <= 3, "sky changes smoothly minute to minute (max step " + worst + ")");
        eq(Ambient.mix(0x000000, 0xFFFFFF, 0f), 0x000000, "mix t=0");
        eq(Ambient.mix(0x000000, 0xFFFFFF, 1f), 0xFFFFFF, "mix t=1");
        eq(Ambient.mix(0x000000, 0xFEFEFE, 0.5f), 0x7F7F7F, "mix midpoint");
        eq(Ambient.mix(0x102030, 0x405060, -3f), 0x102030, "mix clamps low");
        eq(Ambient.mix(0xFF102030, 0x405060, 0f), 0x102030, "mix strips alpha");
    }

    static void ambientQuiet() {
        eq(Ambient.quietDim(12 * 60), 0f, "noon not dimmed");
        eq(Ambient.quietDim(23 * 60), 0f, "23:00 starts at zero");
        check(Math.abs(Ambient.quietDim(23 * 60 + 15) - Ambient.QUIET_MAX / 2) < 1e-4, "23:15 half way");
        eq(Ambient.quietDim(2 * 60), Ambient.QUIET_MAX, "2am fully dimmed");
        eq(Ambient.quietDim(6 * 60 + 30), 0f, "6:30 back to normal");
        float worst = 0f;
        for (int m = 0; m < 1440; m++) {
            float a = Ambient.quietDim(m), b = Ambient.quietDim(m + 1);
            check(a >= 0f && a <= Ambient.QUIET_MAX, "dim in range at " + m);
            worst = Math.max(worst, Math.abs(a - b));
        }
        check(worst <= Ambient.QUIET_MAX / 30f + 1e-4, "dim fades, never jumps (max step " + worst + ")");
    }

    static void ambientGreeting() {
        eq(Ambient.greeting(4 * 60 + 59), "Good night", "04:59");
        eq(Ambient.greeting(5 * 60), "Good morning", "05:00");
        eq(Ambient.greeting(11 * 60 + 59), "Good morning", "11:59");
        eq(Ambient.greeting(12 * 60), "Good afternoon", "12:00");
        eq(Ambient.greeting(17 * 60), "Good evening", "17:00");
        eq(Ambient.greeting(22 * 60), "Good night", "22:00");
    }

    static void ambientGlow() {
        int black = Ambient.glowFrom(0x000000);
        check(((black >> 16) & 0xFF) >= 130, "black icon still gives a visible glow");
        int white = Ambient.glowFrom(0xFFFFFF);
        check(((white >> 16) & 0xFF) == ((white >> 8) & 0xFF) && ((white >> 8) & 0xFF) == (white & 0xFF), "white stays grey");
        int red = Ambient.glowFrom(0xE50914); // Netflix red
        check(((red >> 16) & 0xFF) > ((red >> 8) & 0xFF) + 60, "red stays red");
        int blue = Ambient.glowFrom(0x00A8E1); // Prime blue
        check((blue & 0xFF) > ((blue >> 16) & 0xFF) + 60, "blue stays blue");
        Random rnd = new Random(7);
        for (int i = 0; i < 2000; i++) {
            int g = Ambient.glowFrom(rnd.nextInt(0x1000000));
            check((g & 0xFF000000) == 0, "glow has no alpha bits");
            int max = Math.max((g >> 16) & 0xFF, Math.max((g >> 8) & 0xFF, g & 0xFF));
            if (max < 138 || max > 232) check(false, "glow brightness out of band: " + Integer.toHexString(g));
        }
    }
}
