import com.jay.calmhome.Ambient;
import com.jay.calmhome.AppEntry;
import com.jay.calmhome.AppOrder;
import com.jay.calmhome.LaunchLog;
import com.jay.calmhome.Options;
import com.jay.calmhome.Scenes;
import com.jay.calmhome.SpatialNav;
import com.jay.calmhome.SpatialNav.Box;

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
        navGrid();
        navRows();
        navEdges();
        navReachability();
        navFuzz();
        scrollTests();
        scenesTests();
        optionsTests();
        launchLogTests();
        orderSplit();
        orderAll();
        orderPins();
        orderToggle();
        ambientTests();
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

    // ---- SpatialNav -------------------------------------------------------------------------

    /** A grid of cols x rows-ish tiles (n tiles, row-major) like the All apps screen. */
    static Box[] grid(int n, int cols, float x0, float y0, float w, float h, float gap) {
        Box[] b = new Box[n];
        for (int i = 0; i < n; i++) {
            float x = x0 + (i % cols) * (w + gap), y = y0 + (i / cols) * (h + gap);
            b[i] = new Box(x, y, x + w, y + h);
        }
        return b;
    }

    static float px(Box[] b, int i) { return b[i].cx(); }

    static void navGrid() {
        Box[] g = grid(14, 6, 100, 200, 268, 200, 24); // rows of 6, 6, 2
        eq(SpatialNav.find(g, 0, SpatialNav.RIGHT, px(g, 0)), 1, "right");
        eq(SpatialNav.find(g, 5, SpatialNav.RIGHT, px(g, 5)), -1, "no right past the last column");
        eq(SpatialNav.find(g, 0, SpatialNav.LEFT, px(g, 0)), -1, "no left past the first column (no wrap)");
        eq(SpatialNav.find(g, 6, SpatialNav.LEFT, px(g, 6)), -1, "no left wrap to the previous row");
        eq(SpatialNav.find(g, 0, SpatialNav.DOWN, px(g, 0)), 6, "down one row");
        eq(SpatialNav.find(g, 8, SpatialNav.UP, px(g, 8)), 2, "up one row");
        eq(SpatialNav.find(g, 0, SpatialNav.UP, px(g, 0)), -1, "up from the top row with nothing above");
        eq(SpatialNav.find(g, 12, SpatialNav.DOWN, px(g, 12)), -1, "down from the last row");
        // Down from column 5 onto a short last row lands on its nearest tile, then Up returns to column 5.
        int d = SpatialNav.find(g, 11, SpatialNav.DOWN, px(g, 11));
        eq(d, 13, "down onto a short row lands on its last tile");
        eq(SpatialNav.find(g, d, SpatialNav.UP, px(g, 11)), 11, "up remembers the column");
        eq(SpatialNav.find(g, 13, SpatialNav.UP, px(g, 13)), 7, "without memory, up goes straight up");
    }

    static void navRows() {
        // Header chips above, a favourites row below: Up/Down cross rows, Left/Right never do.
        Box[] b = {
                new Box(1500, 60, 1572, 132),   // 0 settings
                new Box(1590, 60, 1662, 132),   // 1 customize
                new Box(100, 700, 290, 850),    // 2 fav
                new Box(314, 700, 504, 850),    // 3 fav
                new Box(528, 700, 718, 850),    // 4 fav
                new Box(742, 700, 932, 850),    // 5 all apps
        };
        eq(SpatialNav.find(b, 1, SpatialNav.LEFT, px(b, 1)), 0, "chips: left");
        eq(SpatialNav.find(b, 0, SpatialNav.RIGHT, px(b, 0)), 1, "chips: right");
        eq(SpatialNav.find(b, 5, SpatialNav.RIGHT, px(b, 5)), -1, "last favourite: right must not jump to the header");
        eq(SpatialNav.find(b, 2, SpatialNav.LEFT, px(b, 2)), -1, "first favourite: left stays");
        eq(SpatialNav.find(b, 3, SpatialNav.UP, px(b, 3)) >= 0, true, "up from favourites reaches the header");
        eq(SpatialNav.find(b, 0, SpatialNav.DOWN, px(b, 0)) >= 2, true, "down from the header reaches the favourites");
        eq(SpatialNav.find(b, 99, SpatialNav.UP, 0f), -1, "bad index");
        eq(SpatialNav.find(null, 0, SpatialNav.UP, 0f), -1, "null boxes");
        eq(SpatialNav.find(new Box[0], 0, SpatialNav.UP, 0f), -1, "no boxes");
        eq(SpatialNav.find(new Box[]{new Box(0, 0, 10, 10)}, 0, SpatialNav.DOWN, 5f), -1, "single box goes nowhere");
        eq(SpatialNav.find(b, 0, 9, 0f), -1, "unknown direction");
    }

    static void navEdges() {
        // In-beam beats out-of-beam even when farther away.
        Box[] b = {
                new Box(100, 100, 200, 200),   // 0 cur
                new Box(110, 400, 190, 500),   // 1 straight below, far
                new Box(300, 210, 400, 310),   // 2 diagonal, close
        };
        eq(SpatialNav.find(b, 0, SpatialNav.DOWN, 150f), 1, "straight below wins over a closer diagonal");
        // Equal scores: the lower index wins, deterministically.
        Box[] t = {new Box(0, 0, 10, 10), new Box(20, 20, 30, 30), new Box(-20, 20, -10, 30)};
        eq(SpatialNav.find(t, 0, SpatialNav.DOWN, 5f), 1, "tie goes to the lower index");
    }

    /** Every item must be reachable from every other, in many layouts, whatever column is remembered. */
    static void navReachability() {
        int layouts = 0;
        for (int cols = 1; cols <= 7; cols++)
            for (int n = 1; n <= 40; n += (n < 14 ? 1 : 7))
                for (int header = 0; header <= 4; header += 2)
                    for (int footer = 0; footer <= 3; footer += 3) {
                        List<Box> list = new ArrayList<>();
                        for (int i = 0; i < header; i++) list.add(new Box(1000 + i * 90, 40, 1070 + i * 90, 112));
                        for (Box x : grid(n, cols, 100, 200, 200, 120, 20)) list.add(x);
                        float fy = 200 + ((n - 1) / cols + 1) * 140 + 40;
                        for (int i = 0; i < footer; i++) list.add(new Box(100 + i * 210, fy, 290 + i * 210, fy + 100));
                        Box[] b = list.toArray(new Box[0]);
                        for (int start = 0; start < b.length; start++) {
                            Set<Integer> seen = new HashSet<>();
                            Set<String> states = new HashSet<>();
                            ArrayDeque<int[]> q = new ArrayDeque<>();
                            q.add(new int[]{start, start});
                            states.add(start + ":" + start);
                            while (!q.isEmpty()) {
                                int[] s = q.poll();
                                seen.add(s[0]);
                                for (int d = 0; d < 4; d++) {
                                    int nx = SpatialNav.find(b, s[0], d, b[s[1]].cx());
                                    if (nx < 0) continue;
                                    int pref = (d == SpatialNav.LEFT || d == SpatialNav.RIGHT) ? nx : s[1];
                                    if (states.add(nx + ":" + pref)) q.add(new int[]{nx, pref});
                                }
                            }
                            if (seen.size() != b.length) {
                                check(false, "reachability cols=" + cols + " n=" + n + " header=" + header
                                        + " footer=" + footer + " from " + start + " reached " + seen.size() + "/" + b.length);
                            }
                        }
                        layouts++;
                    }
        check(true, "every item reachable in " + layouts + " layouts");
    }

    static void navFuzz() {
        Random rnd = new Random(42);
        int bad = 0;
        for (int t = 0; t < 400 && bad < 5; t++) {
            int n = 1 + rnd.nextInt(30);
            Box[] b = new Box[n];
            for (int i = 0; i < n; i++) {
                float x = rnd.nextInt(1800), y = rnd.nextInt(1000), w = 20 + rnd.nextInt(300), h = 20 + rnd.nextInt(200);
                b[i] = new Box(x, y, x + w, y + h);
            }
            int cur = rnd.nextInt(n);
            float pref = b[cur].cx();
            for (int k = 0; k < 200; k++) {
                int d = rnd.nextInt(4);
                int nx = SpatialNav.find(b, cur, d, pref);
                if (nx < -1 || nx >= n || nx == cur) { bad++; check(false, "bad result " + nx); break; }
                if (nx >= 0) {
                    cur = nx;
                    if (d == SpatialNav.LEFT || d == SpatialNav.RIGHT) pref = b[cur].cx();
                }
            }
        }
        check(bad == 0, "fuzz: random layouts never return a bad index");
    }

    static void scrollTests() {
        eq(SpatialNav.keepVisible(100, 200, 0f, 1080, 48, 48, 3000), 0f, "visible box does not scroll");
        eq(SpatialNav.keepVisible(1000, 1200, 0f, 1080, 48, 48, 3000), -168f, "box below the screen scrolls up");
        eq(SpatialNav.keepVisible(100, 200, -500f, 1080, 48, 48, 3000), -52f, "box above the screen scrolls back");
        eq(SpatialNav.keepVisible(2900, 3000, 0f, 1080, 48, 48, 3000), -1920f, "never scrolls past the content");
        eq(SpatialNav.keepVisible(10, 60, -9999f, 1080, 48, 48, 3000), 0f, "never scrolls above the top");
        eq(SpatialNav.keepVisible(500, 600, -300f, 1080, 48, 48, 900), 0f, "short content never scrolls");
    }

    // ---- Scenes -----------------------------------------------------------------------------

    static void scenesTests() {
        eq(Scenes.ALL.length, 3, "three scenes");
        eq(Scenes.ALL[0].name, "Dusk", "scene 0 name");
        eq(Scenes.ALL[1].name, "Forest", "scene 1 name");
        eq(Scenes.ALL[2].name, "Desert", "scene 2 name");
        eq(Scenes.ALL[0].base, 0x0B1430, "dusk base colour");
        for (Scenes.Scene s : Scenes.ALL) eq(s.blobs.length, 3, s.name + " has three blobs");
        // cross-fade
        eq(Scenes.opacity(0, 0, 0, 0), 1f, "steady: active scene fully visible");
        eq(Scenes.opacity(1, 0, 0, 0), 0f, "steady: others hidden");
        eq(Scenes.opacity(1, 0, 1, 0), 0f, "fade start: new scene invisible");
        eq(Scenes.opacity(0, 0, 1, 0), 1f, "fade start: old scene visible");
        check(Math.abs(Scenes.opacity(1, 0, 1, Scenes.FADE_MS / 2) - 0.5f) < 1e-4, "fade middle: half");
        check(Math.abs(Scenes.opacity(0, 0, 1, Scenes.FADE_MS / 2) - 0.5f) < 1e-4, "fade middle: old half");
        eq(Scenes.opacity(1, 0, 1, Scenes.FADE_MS), 1f, "fade end");
        eq(Scenes.opacity(1, 0, 1, Scenes.FADE_MS * 10), 1f, "fade clamps late");
        eq(Scenes.opacity(0, 0, 1, Scenes.FADE_MS * 10), 0f, "old gone after the fade");
        eq(Scenes.opacity(2, 0, 1, 500), 0f, "uninvolved scene stays hidden");
        float last = 0f;
        boolean monotonic = true;
        for (int ms = 0; ms <= Scenes.FADE_MS; ms += 50) {
            float o = Scenes.opacity(1, 0, 1, ms);
            if (o < last - 1e-6f) monotonic = false;
            last = o;
        }
        check(monotonic, "fade-in never goes backwards");
        // blob geometry: the scene box is 120% of the screen, centred
        float[] r = new float[4];
        Scenes.blobRect(new Scenes.Blob(0f, 0f, 1f, 1f, 0), 1000f, 500f, r);
        check(r[0] == -100f && r[1] == -50f && r[2] == 1200f && r[3] == 600f, "full blob covers 120% box");
        Scenes.blobRect(Scenes.ALL[0].blobs[0], 1920f, 1080f, r);
        check(Math.abs(r[0] - (-192f + 0.05f * 2304f)) < 0.01f && Math.abs(r[2] - 0.55f * 2304f) < 0.01f, "dusk blob 0 position");
        // ken burns: starts at 1.05 and reaches 1.22 / -4% / -3% after 26 s, then returns
        float[] k = new float[3];
        Scenes.kenBurns(0, k);
        check(near(k[0], 1.05f) && near(k[1], 0f) && near(k[2], 0f), "ken burns start");
        Scenes.kenBurns(26, k);
        check(near(k[0], 1.22f) && near(k[1], -0.04f) && near(k[2], -0.03f), "ken burns peak");
        Scenes.kenBurns(52, k);
        check(near(k[0], 1.05f), "ken burns returns");
        Scenes.kenBurns(39, k);
        check(near(k[0], 1.135f), "ken burns half way back");
        Scenes.kenBurns(-13, k);
        check(near(k[0], 1.135f), "ken burns handles negative time");
        // drift
        float[] d = new float[3];
        Scenes.drift(0, d);
        check(near(d[0], 0f) && near(d[2], 0f), "drift start");
        Scenes.drift(15, d);
        check(near(d[0], 0.03f) && near(d[1], -0.02f) && near(d[2], 2f), "drift first pose");
        Scenes.drift(30, d);
        check(near(d[0], -0.02f) && near(d[1], 0.02f) && near(d[2], -1f), "drift last pose");
        Scenes.drift(60, d);
        check(near(d[0], 0f), "drift loops");
        float worst = 0f;
        for (double t = 0; t < 120; t += 0.1) {
            Scenes.kenBurns(t, k);
            float a = k[0];
            Scenes.kenBurns(t + 0.1, k);
            worst = Math.max(worst, Math.abs(k[0] - a));
        }
        check(worst < 0.01f, "ken burns is smooth (max step " + worst + ")");
        // stepping
        boolean[] all = {true, true, true};
        eq(Scenes.step(0, all, 1), 1, "next");
        eq(Scenes.step(2, all, 1), 0, "next wraps");
        eq(Scenes.step(0, all, -1), 2, "previous wraps");
        eq(Scenes.step(0, new boolean[]{true, false, true}, 1), 2, "skips disabled");
        eq(Scenes.step(0, new boolean[]{true, false, false}, 1), 0, "only one enabled stays");
        eq(Scenes.step(1, new boolean[]{false, false, false}, 1), 1, "none enabled stays");
        eq(Scenes.step(0, new boolean[]{}, 1), 0, "empty mask stays");
        eq(Scenes.step(0, new boolean[]{false, true}, -1), 1, "short mask");
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-3f; }

    // ---- Options / LaunchLog ----------------------------------------------------------------

    static void optionsTests() {
        eq(Options.idx(2, 3), 2, "idx in range");
        eq(Options.idx(3, 3), 0, "idx too high falls back");
        eq(Options.idx(-1, 3), 0, "idx negative falls back");
        eq(Options.MOTION.length, 3, "motions");
        eq(Options.INTERVAL_MS.length, Options.INTERVAL_LABELS.length, "intervals line up");
        eq(Options.GLASS.length, Options.GLASS_TINT.length, "glass levels line up");
        eq(Options.INTERVAL_MS[1], 10000L, "default interval is 10 s");
        eq(Options.SORT[1], "A–Z", "sort label uses an en dash");
        check(Arrays.equals(Options.sceneMask("101", 3), new boolean[]{true, false, true}), "mask parse");
        check(Arrays.equals(Options.sceneMask(null, 3), new boolean[]{true, true, true}), "null mask means all on");
        check(Arrays.equals(Options.sceneMask("1", 3), new boolean[]{true, true, true}), "short mask pads with on");
        check(Arrays.equals(Options.sceneMask("000", 3), new boolean[]{true, false, false}), "empty mask keeps one scene");
        eq(Options.maskString(new boolean[]{true, false, true}), "101", "mask string");
        check(Arrays.equals(Options.toggleScene(new boolean[]{true, true, false}, 1), new boolean[]{true, false, false}), "toggle off");
        check(Arrays.equals(Options.toggleScene(new boolean[]{true, false, false}, 0), new boolean[]{true, false, false}), "cannot turn the last scene off");
        check(Arrays.equals(Options.toggleScene(new boolean[]{true, false, false}, 2), new boolean[]{true, false, true}), "toggle on");
        check(Arrays.equals(Options.toggleScene(new boolean[]{true, false, false}, 9), new boolean[]{true, false, false}), "bad index ignored");
        boolean[] src = {true, true};
        Options.toggleScene(src, 0);
        check(src[0], "toggle does not mutate its input");
    }

    static void launchLogTests() {
        eq(LaunchLog.record(null, "a.b"), "a.b", "first launch");
        eq(LaunchLog.record("a.b", "c.d"), "c.d,a.b", "newest first");
        eq(LaunchLog.record("c.d,a.b", "a.b"), "a.b,c.d", "relaunch moves to the front");
        eq(LaunchLog.record("a.b", "not a package"), "a.b", "junk package ignored");
        eq(LaunchLog.record("a.b", null), "a.b", "null package ignored");
        eq(LaunchLog.parse("a.b,,junk;rm,a.b,c.d"), Arrays.asList("a.b", "c.d"), "parse drops junk and duplicates");
        eq(LaunchLog.parse(null), Collections.<String>emptyList(), "parse null");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) sb.append(i == 0 ? "" : ",").append("p.q").append(i);
        eq(LaunchLog.parse(sb.toString()).size(), LaunchLog.MAX, "parse caps the length");
        String log = "";
        for (int i = 0; i < 150; i++) log = LaunchLog.record(log, "p.q" + i);
        eq(LaunchLog.parse(log).size(), LaunchLog.MAX, "record caps the length");
        eq(LaunchLog.parse(log).get(0), "p.q149", "newest kept when capped");
        eq(LaunchLog.ranks("a.b,c.d").get("c.d"), 1, "rank of the second");
        eq(LaunchLog.ranks("a.b,c.d").get("a.b"), 0, "rank of the first");
        check(!LaunchLog.ranks("a.b").containsKey("x.y"), "unknown app has no rank");
    }

    // ---- AppOrder ---------------------------------------------------------------------------

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

    static void orderAll() {
        List<AppEntry> apps = Arrays.asList(app("com.b", "Beta"), app("com.a", "alpha"), app("com.a", "dup"),
                app("com.c", "Charlie"), app("com.amazon.tv.launcher", "Home"), null);
        List<String> pins = Arrays.asList("com.c", "com.b");
        eq(pkgs(AppOrder.allApps(apps, pins, AppOrder.SORT_AZ, "")), Arrays.asList("com.a", "com.b", "com.c"),
                "A-Z: deduped, no Amazon home, case-insensitive");
        eq(pkgs(AppOrder.allApps(apps, pins, AppOrder.SORT_PINNED, "")), Arrays.asList("com.c", "com.b", "com.a"),
                "pinned first, in pin order");
        eq(pkgs(AppOrder.allApps(apps, pins, AppOrder.SORT_RECENT, "com.c,com.a")), Arrays.asList("com.c", "com.a", "com.b"),
                "recent: launched apps newest first, then A-Z");
        eq(pkgs(AppOrder.allApps(apps, pins, AppOrder.SORT_RECENT, "")), Arrays.asList("com.a", "com.b", "com.c"),
                "recent with no history is A-Z");
        eq(pkgs(AppOrder.allApps(apps, pins, AppOrder.SORT_RECENT, "com.gone,com.b")), Arrays.asList("com.b", "com.a", "com.c"),
                "recent ignores uninstalled apps");
        eq(pkgs(AppOrder.allApps(apps, pins, 99, "")), Arrays.asList("com.a", "com.b", "com.c"), "unknown sort is A-Z");
        check(AppOrder.allApps(Collections.<AppEntry>emptyList(), pins, 0, "").isEmpty(), "no apps");
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
        eq(AppOrder.togglePin(pins, dock, "com.netflix.ninja", 7), Arrays.asList("com.missing"), "unpin");
        eq(AppOrder.togglePin(pins, dock, "com.z", 7), Arrays.asList("com.missing", "com.netflix.ninja", "com.z"), "pin appends");
        List<AppEntry> full = new ArrayList<>();
        List<String> fullPins = new ArrayList<>();
        for (char c = 'a'; c < 'a' + 7; c++) {
            full.add(app(c + "." + c, "" + c));
            fullPins.add(c + "." + c);
        }
        check(AppOrder.togglePin(fullPins, full, "z.z", 7) == null, "full dock refuses");
        check(AppOrder.togglePin(fullPins, full, "c.c", 7).size() == 6, "full dock still unpins");
        eq(AppOrder.togglePin(Arrays.asList("x.x", "x.x"), Collections.<AppEntry>emptyList(), "x.x", 7),
                Arrays.asList("x.x"), "pin of a stale entry does not duplicate");
    }

    // ---- Ambient ----------------------------------------------------------------------------

    static void ambientTests() {
        eq(Ambient.mix(0x000000, 0xFFFFFF, 0f), 0x000000, "mix t=0");
        eq(Ambient.mix(0x000000, 0xFFFFFF, 1f), 0xFFFFFF, "mix t=1");
        eq(Ambient.mix(0x000000, 0xFEFEFE, 0.5f), 0x7F7F7F, "mix midpoint");
        eq(Ambient.mix(0x102030, 0x405060, -3f), 0x102030, "mix clamps low");
        eq(Ambient.mix(0x102030, 0x405060, 9f), 0x405060, "mix clamps high");
        eq(Ambient.mix(0xFF102030, 0x405060, 0f), 0x102030, "mix strips alpha from a");
        eq(Ambient.mix(0x102030, 0xFF405060, 1f), 0x405060, "mix strips alpha from b");
        eq(Ambient.mix(0xFF102030, 0xFF405060, 0.5f) & 0xFF000000, 0, "mixed colour has no alpha bits");
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
        eq(Ambient.greeting(0), "Good morning", "00:00");
        eq(Ambient.greeting(11 * 60 + 59), "Good morning", "11:59");
        eq(Ambient.greeting(12 * 60), "Good afternoon", "12:00");
        eq(Ambient.greeting(17 * 60 + 59), "Good afternoon", "17:59");
        eq(Ambient.greeting(18 * 60), "Good evening", "18:00");
        eq(Ambient.greeting(23 * 60 + 59), "Good evening", "23:59");
        eq(Ambient.greeting(-1), "Good evening", "negative wraps");
    }
}
