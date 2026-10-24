package com.jay.calmhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole UI on one Canvas, in the Liquid Glass design's 1920x1080 units: Home (with a hero
 * card for the app to continue), All apps, Customize, a Quick settings panel and an ambient clock.
 * Focus is geometric (SpatialNav) and moves on springs (Motion); everything focusable is an Item.
 */
final class CalmView extends View {
    interface Host {
        void launch(AppEntry e);

        void togglePin(AppEntry e);

        void openSettings();

        void openSettings(String action);
    }

    static final int HOME = 0, APPS = 1, CUSTOM = 2, QUICK = 3;
    static final int MAX_DOCK = HomeLayout.MAX_DOCK;

    private static final int CIRCLE = 0, FAV = 1, MORE = 2, PRIMARY = 3, CHIP = 4, APPT = 5,
            TOGGLE = 6, SWATCH = 7, DOT = 8, HERO = 9, ROW = 10;
    private static final float W = Background.W, H = Background.H;
    private static final int INK = 0xFF05070D;
    private static final float PANEL_L = 1216f, PANEL_R = 1824f;

    private static final class Item {
        final int kind;
        final String id;
        final float l, t, w, h;
        String label = "", sub = "";
        float size = 20f;
        AppEntry app;
        TileArt art;
        boolean on, pinned, dark;
        int color, icon;
        Shader shade, glow;
        Runnable select, menu;

        Item(int kind, String id, float l, float t, float w, float h) {
            this.kind = kind;
            this.id = id;
            this.l = l;
            this.t = t;
            this.w = w;
            this.h = h;
        }
    }

    private final Gfx g = new Gfx();
    private final Background bg = new Background();
    private final List<Item> items = new ArrayList<>();
    private final List<Item> under = new ArrayList<>();
    private SpatialNav.Box[] boxes = new SpatialNav.Box[0];
    private Settings st;
    private Host host;

    private int screen = HOME;
    private int focus;
    private float prefX;
    private List<AppEntry> all = Collections.emptyList();
    private List<AppEntry> dock = Collections.emptyList();
    private List<AppEntry> sorted = Collections.emptyList();
    private List<String> pins = Collections.emptyList();
    private Map<String, TileArt> art = Collections.emptyMap();
    private boolean loaded;

    private float s = 1f, ox, oy;
    private float camY, camGoal, contentH = H;
    private final RectF fNow = new RectF(), fGoal = new RectF();
    private final float[][] edge = {{0f, 0f}, {0f, 0f}, {0f, 0f}, {0f, 0f}};
    private float parX, parY, parGoalX, parGoalY;
    private boolean snap = true;
    private final long t0 = SystemClock.uptimeMillis();
    private int sceneFrom, sceneTo;
    private long sceneSince = -1000000L, lastAuto = SystemClock.uptimeMillis();
    private long lastFrame, screenSince = SystemClock.uptimeMillis();
    private boolean longFired;

    private long lastInput = SystemClock.uptimeMillis();
    private boolean ambientOn, swallowUp;
    private float amb;
    /** 1 right after Select, decaying to 0: the focused item dips a little, like a real button. */
    private float press;

    private long clockMinute = -1;
    private int minuteOfDay;
    private String clockText = "", dateText = "", greetText = "";

    CalmView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void init(Settings settings, Host h) {
        st = settings;
        host = h;
        boolean[] mask = st.scenes();
        sceneTo = sceneFrom = Scenes.step(Scenes.ALL.length - 1, mask, 1);
        refreshClock();
        rebuild(null);
    }

    /** New app list from the loader thread. */
    void setData(List<AppEntry> apps, Map<String, TileArt> newArt) {
        boolean first = !loaded;
        all = apps;
        art = newArt;
        loaded = true;
        recompute();
        // The first real data replaces a placeholder-only screen: start on the hero,
        // not wherever focus happened to sit while the list was loading.
        rebuild(first ? "#default" : null);
    }

    void showHome() {
        wake();
        if (screen != HOME) go(HOME);
    }

    /** Back from another app or a remote press: leave the ambient screen and restart the idle timer. */
    void wake() {
        lastInput = SystemClock.uptimeMillis();
        ambientOn = false;
        invalidate();
    }

    boolean onHome() {
        return screen == HOME;
    }

    private void recompute() {
        pins = AppOrder.parsePins(st.pins());
        AppOrder.Split sp = AppOrder.split(all, pins, MAX_DOCK);
        List<AppEntry> d = new ArrayList<>(sp.dock);
        int hero = AppOrder.hero(d, st.log());
        if (hero > 0) d.add(0, d.remove(hero));
        dock = d;
        sorted = AppOrder.allApps(all, pins, st.sort(), st.log());
    }

    List<AppEntry> dockApps() {
        return dock;
    }

    /** Re-reads pins after a change and keeps focus on the same app. */
    void pinsChanged(String pkg) {
        recompute();
        rebuild("app:" + pkg);
    }

    private static final String TAG = "CalmHome";
    private static final String[] SCREENS = {"home", "apps", "custom", "quick"};

    private void go(int to) {
        Log.i(TAG, "screen " + SCREENS[to]);
        screen = to;
        camY = camGoal = 0f;
        screenSince = SystemClock.uptimeMillis();
        snap = to != QUICK;
        recompute();
        rebuild("#default");
    }

    // ---- building screens -------------------------------------------------------------------

    private void rebuild(String keepId) {
        String keep = keepId != null ? keepId : (focus >= 0 && focus < items.size() ? items.get(focus).id : null);
        items.clear();
        under.clear();
        if (screen == HOME) buildHome();
        else if (screen == APPS) buildApps();
        else if (screen == CUSTOM) buildCustom();
        else {
            buildHome();
            under.addAll(items);
            items.clear();
            buildQuick();
        }
        boxes = new SpatialNav.Box[items.size()];
        for (int i = 0; i < boxes.length; i++) {
            Item it = items.get(i);
            boxes[i] = new SpatialNav.Box(it.l, it.t, it.l + it.w, it.t + it.h);
        }
        focus = -1;
        if (keep != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(keep)) focus = i;
            }
        }
        if (focus < 0) focus = defaultFocus();
        if (focus >= 0 && focus < boxes.length) prefX = boxes[focus].cx();
        retarget();
    }

    private int defaultFocus() {
        int want = screen == HOME ? HERO : screen == APPS ? APPT : screen == QUICK ? ROW : TOGGLE;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind == want) return i;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind == MORE) return i;
        }
        return items.isEmpty() ? -1 : 0;
    }

    private Item add(Item it) {
        items.add(it);
        return it;
    }

    private void buildHome() {
        contentH = H;
        float right = 1824f;
        if (st.flag(Settings.PROFILE)) right -= profileWidth() + 16f;
        Item cust = add(new Item(CIRCLE, "customize", right - 72f, 64f, 72f, 72f));
        cust.icon = Gfx.ICON_SLIDERS;
        cust.label = "Customize home";
        cust.select = new Runnable() {
            @Override
            public void run() {
                go(CUSTOM);
            }
        };
        Item set = add(new Item(CIRCLE, "quick", right - 160f, 64f, 72f, 72f));
        set.icon = Gfx.ICON_GEAR;
        set.label = "Quick settings";
        set.select = new Runnable() {
            @Override
            public void run() {
                go(QUICK);
            }
        };
        if (st.flag(Settings.PHOTO)) {
            float tw = g.measure(sceneLabel(), 20f, g.regular);
            float py = photoTop();
            Item prev = add(new Item(CIRCLE, "scene-prev", 96f + 22f + tw + 16f, py + 10f, 44f, 44f));
            prev.icon = Gfx.ICON_PREV;
            prev.label = "Previous photo";
            prev.select = new Runnable() {
                @Override
                public void run() {
                    stepScene(-1);
                }
            };
            Item next = add(new Item(CIRCLE, "scene-next", prev.l + 60f, py + 10f, 44f, 44f));
            next.icon = Gfx.ICON_NEXT;
            next.label = "Next photo";
            next.select = new Runnable() {
                @Override
                public void run() {
                    stepScene(1);
                }
            };
        }
        SpatialNav.Box[] row = HomeLayout.row(dock.size());
        for (int i = 0; i < dock.size(); i++) {
            final AppEntry e = dock.get(i);
            SpatialNav.Box b = row[i];
            Item it = add(new Item(i == 0 ? HERO : FAV, "app:" + e.pkg, b.l, b.t, b.r - b.l, b.b - b.t));
            appItem(it, e);
            if (i == 0) heroItem(it);
        }
        SpatialNav.Box mb = row[dock.size()];
        Item more = add(new Item(MORE, "more", mb.l, mb.t, mb.r - mb.l, mb.b - mb.t));
        more.label = "All apps";
        more.icon = Gfx.ICON_GRID;
        more.select = new Runnable() {
            @Override
            public void run() {
                go(APPS);
            }
        };
    }

    private float photoTop() {
        return st.flag(Settings.CLOCK) ? 248f : 160f;
    }

    private void heroItem(Item it) {
        int tint = it.art != null ? it.art.tint : st.accent();
        long when = LaunchLog.timeOf(st.times(), it.app.pkg);
        String ago = LaunchLog.ago(when, System.currentTimeMillis());
        it.sub = ago.isEmpty() ? "Ready when you are" : "Last opened " + ago.substring(0, 1).toLowerCase(Locale.US)
                + ago.substring(1);
        it.color = tint;
        it.shade = new LinearGradient(it.l, it.t, it.l + it.w, it.t + it.h,
                new int[]{(tint & 0xFFFFFF) | 0xB0000000, (tint & 0xFFFFFF) | 0x40000000, 0x26000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        it.glow = new android.graphics.RadialGradient(it.l + it.w - 110f, it.t + it.h * 0.45f, 330f,
                new int[]{(tint & 0xFFFFFF) | 0x80000000, (tint & 0xFFFFFF) | 0x26000000, tint & 0xFFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    private void appItem(Item it, final AppEntry e) {
        it.app = e;
        it.label = e.label;
        it.art = art.get(e.key());
        it.pinned = pins.contains(e.pkg);
        it.select = new Runnable() {
            @Override
            public void run() {
                host.launch(e);
            }
        };
        it.menu = new Runnable() {
            @Override
            public void run() {
                host.togglePin(e);
            }
        };
    }

    private void buildApps() {
        float hw = 60f + g.measure("Home", 20f, g.medium);
        Item h2 = add(new Item(PRIMARY, "home", 1824f - hw, 120f, hw, 56f));
        h2.label = "Home";
        h2.size = 20f;
        h2.select = new Runnable() {
            @Override
            public void run() {
                go(HOME);
            }
        };
        float x = h2.l - 24f;
        for (int i = Options.SORT.length - 1; i >= 0; i--) {
            final int idx = i;
            float w = 52f + g.measure(Options.SORT[i], 20f, g.medium);
            x -= w;
            Item chip = add(new Item(CHIP, "sort:" + i, x, 120f, w, 56f));
            chip.label = Options.SORT[i];
            chip.on = st.sort() == i;
            chip.select = new Runnable() {
                @Override
                public void run() {
                    st.put("sort", idx);
                    recompute();
                    rebuild("sort:" + idx);
                }
            };
            x -= 12f;
        }
        for (int i = 0; i < sorted.size(); i++) {
            AppEntry e = sorted.get(i);
            Item it = add(new Item(APPT, "app:" + e.pkg, 96f + (i % 6) * 292f, 212f + (i / 6) * 224f, 268f, 200f));
            appItem(it, e);
        }
        int rows = (sorted.size() + 5) / 6;
        contentH = 212f + rows * 224f + 40f;
    }

    private void buildQuick() {
        contentH = H;
        Object[][] rows = {
                // Only screens Fire OS lets a third-party app open (checked on a Fire TV Stick 4K);
                // display, sound and privacy need Amazon's signature permission, so they stay
                // behind All settings.
                {"Wi-Fi & network", "Connect or change networks", Gfx.ICON_WIFI, "android.settings.WIFI_SETTINGS"},
                {"Remotes & Bluetooth", "Pair a remote, headphones, a game pad", Gfx.ICON_REMOTE, "com.amazon.device.settings.action.CONTROLLERS"},
                {"Applications", "Storage, updates, force stop", Gfx.ICON_GRID, "android.settings.APPLICATION_SETTINGS"},
                {"My Fire TV", "System updates, restart, about", Gfx.ICON_DISPLAY, "com.amazon.device.settings.action.DEVICE"},
                {"Ambient screen", "A calm clock over your scenes", Gfx.ICON_MOON, "#ambient"},
                {"Customize Calm Home", "Clock, scenes, glass, accent", Gfx.ICON_SLIDERS, "#custom"},
                {"All settings", "Display, sound, privacy and ads", Gfx.ICON_GEAR, null},
        };
        for (int i = 0; i < rows.length; i++) {
            final String action = (String) rows[i][3];
            Item r = add(new Item(ROW, "q:" + i, PANEL_L + 32f, 226f + i * 110f, PANEL_R - PANEL_L - 64f, 96f));
            r.label = (String) rows[i][0];
            r.sub = (String) rows[i][1];
            r.icon = (Integer) rows[i][2];
            r.select = new Runnable() {
                @Override
                public void run() {
                    if ("#ambient".equals(action)) {
                        go(HOME);
                        ambientOn = true;
                    } else if ("#custom".equals(action)) {
                        go(CUSTOM);
                    } else if (action == null) {
                        host.openSettings();
                    } else {
                        host.openSettings(action);
                    }
                }
            };
        }
    }

    private void buildCustom() {
        contentH = H;
        float dw = 80f + g.measure("Done", 24f, g.medium);
        Item done = add(new Item(PRIMARY, "done", 1824f - dw, 84f, dw, 72f));
        done.label = "Done";
        done.size = 24f;
        done.select = new Runnable() {
            @Override
            public void run() {
                go(HOME);
            }
        };
        String[][] defs = {
                {Settings.CLOCK, "Clock", "Large time"},
                {Settings.DATE, "Date", "Weekday and day"},
                {Settings.GREETING, "Greeting", "Hello by time of day"},
                {Settings.PROFILE, "Profile", "Your name on Home"},
                {Settings.PHOTO, "Photo controls", "Name and arrows"},
                {Settings.QUIET, "Quiet hours", "Dim 11 pm to 6:30 am"},
                {Settings.AMBIENT, "Ambient screen", "Clock after 3 idle min"},
        };
        for (int i = 0; i < defs.length; i++) {
            final String key = defs[i][0];
            Item t = add(new Item(TOGGLE, "tg:" + key, 136f + (i % 2) * 392f, 310f + (i / 2) * 112f, 376f, 96f));
            t.label = defs[i][1];
            t.sub = defs[i][2];
            t.on = st.flag(key);
            t.select = new Runnable() {
                @Override
                public void run() {
                    st.flag(key, !st.flag(key));
                    rebuild("tg:" + key);
                }
            };
        }
        boolean[] mask = st.scenes();
        for (int i = 0; i < Scenes.ALL.length; i++) {
            final int idx = i;
            Item sw = add(new Item(SWATCH, "sc:" + i, 1016f + i * 156f, 348f, 142f, 112f));
            sw.label = Scenes.ALL[i].name;
            sw.color = 0xFF000000 | Scenes.ALL[i].blobs[0].color;
            sw.on = mask[i];
            sw.select = new Runnable() {
                @Override
                public void run() {
                    st.scenes(Options.toggleScene(st.scenes(), idx));
                    long now = SystemClock.uptimeMillis();
                    if (!st.scenes()[sceneTo]) setScene(Scenes.step(sceneTo, st.scenes(), 1), now);
                    rebuild("sc:" + idx);
                }
            };
        }
        chips("motion", Options.MOTION, 526f, st.motion());
        chips("interval", Options.INTERVAL_LABELS, 648f, st.interval());
        chips("glass", Options.GLASS, 770f, st.glass());
        for (int i = 0; i < Options.ACCENT.length; i++) {
            final int idx = i;
            Item d = add(new Item(DOT, "accent:" + i, 1016f + i * 72f, 892f, 56f, 56f));
            d.label = "Accent colour " + (i + 1);
            d.color = Options.ACCENT[i];
            d.on = st.accentIndex() == i;
            d.select = new Runnable() {
                @Override
                public void run() {
                    st.put("accent", idx);
                    rebuild("accent:" + idx);
                }
            };
        }
    }

    private void chips(final String key, String[] labels, float y, int selected) {
        float x = 1016f;
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            float w = 52f + g.measure(labels[i], 20f, g.medium);
            Item c = add(new Item(CHIP, key + ":" + i, x, y, w, 56f));
            c.label = labels[i];
            c.on = selected == i;
            c.dark = true;
            c.select = new Runnable() {
                @Override
                public void run() {
                    st.put(key, idx);
                    rebuild(key + ":" + idx);
                }
            };
            x += w + 12f;
        }
    }

    private float profileWidth() {
        return 10f + 52f + 14f + g.measure(st.name(), 24f, g.regular) + 28f;
    }

    // ---- scenes and clock -------------------------------------------------------------------

    private String sceneLabel() {
        return "Photo " + (sceneTo + 1) + " of " + Scenes.ALL.length + " · " + Scenes.ALL[sceneTo].name;
    }

    private void setScene(int to, long now) {
        if (to == sceneTo) return;
        sceneFrom = sceneTo;
        sceneTo = to;
        sceneSince = now;
        lastAuto = now;
    }

    private void stepScene(int dir) {
        setScene(Scenes.step(sceneTo, st.scenes(), dir), SystemClock.uptimeMillis());
        rebuild(null);
    }

    private void autoScene(long now) {
        if (now - lastAuto < Options.INTERVAL_MS[st.interval()]) return;
        lastAuto = now;
        int next = Scenes.step(sceneTo, st.scenes(), 1);
        if (next != sceneTo) {
            setScene(next, now);
            if (screen == HOME) rebuild(null);
        }
    }

    /** @return true when the minute changed. */
    private boolean refreshClock() {
        long minute = System.currentTimeMillis() / 60000L;
        if (minute == clockMinute) return false;
        clockMinute = minute;
        Calendar cal = Calendar.getInstance();
        minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        Date d = cal.getTime();
        clockText = android.text.format.DateFormat.getTimeFormat(getContext()).format(d)
                .replaceAll("(?i)[\\s\\u202F\\u00A0]*[ap]\\.?m\\.?", "").trim();
        dateText = new java.text.SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(d);
        greetText = Ambient.greeting(minuteOfDay);
        return true;
    }

    void onClockChanged() {
        clockMinute = -1;
        invalidate();
    }

    // ---- focus animation --------------------------------------------------------------------

    private static float scaleFor(int kind) {
        switch (kind) {
            case FAV:
            case MORE:
            case CIRCLE:
                return 1.1f;
            case HERO:
                return 1.035f;
            case APPT:
                return 1.08f;
            case DOT:
                return 1.15f;
            case SWATCH:
                return 1.05f;
            case ROW:
                return 1.02f;
            default:
                return 1.04f;
        }
    }

    /** Recomputes where focus, the camera and the background parallax are heading. */
    private void retarget() {
        if (focus >= 0 && focus < items.size()) {
            Item it = items.get(focus);
            float sc = scaleFor(it.kind), dx = it.w * (sc - 1f) / 2f, dy = it.h * (sc - 1f) / 2f;
            fGoal.set(it.l - dx, it.t - dy, it.l + it.w + dx, it.t + it.h + dy);
            if (screen == APPS) {
                camGoal = SpatialNav.keepVisible(it.t, it.t + it.h, camGoal, H, 48f, 56f, contentH);
            } else {
                camGoal = 0f;
            }
            parGoalX = -(fGoal.centerX() - W / 2f) * 0.018f;
            parGoalY = -(fGoal.centerY() - H / 2f) * 0.012f;
            announceForAccessibility(it.label);
        }
        if (snap) {
            fNow.set(fGoal);
            setEdges();
            camY = camGoal;
            parX = parGoalX;
            parY = parGoalY;
            snap = false;
        }
        postInvalidateOnAnimation();
    }

    private void setEdges() {
        edge[0][0] = fNow.left;
        edge[1][0] = fNow.top;
        edge[2][0] = fNow.right;
        edge[3][0] = fNow.bottom;
        for (float[] e : edge) e[1] = 0f;
    }

    private boolean step(float dt) {
        float k = 1f - (float) Math.exp(-dt / 80f);
        boolean moving = false;
        camY += (camGoal - camY) * k;
        if (Math.abs(camGoal - camY) < 0.4f) camY = camGoal;
        else moving = true;
        moving |= Motion.spring(edge[0], fGoal.left, dt);
        moving |= Motion.spring(edge[1], fGoal.top, dt);
        moving |= Motion.spring(edge[2], fGoal.right, dt);
        moving |= Motion.spring(edge[3], fGoal.bottom, dt);
        fNow.set(edge[0][0], edge[1][0], edge[2][0], edge[3][0]);
        float kp = 1f - (float) Math.exp(-dt / 600f);
        parX += (parGoalX - parX) * kp;
        parY += (parGoalY - parY) * kp;
        if (Math.abs(parGoalX - parX) > 0.05f || Math.abs(parGoalY - parY) > 0.05f) moving = true;
        if (press > 0f) {
            press = Math.max(0f, press - dt / 160f);
            moving = true;
        }
        float ka = 1f - (float) Math.exp(-dt / 380f);
        float target = ambientOn ? 1f : 0f;
        amb += (target - amb) * ka;
        if (Math.abs(target - amb) < 0.004f) amb = target;
        else moving = true;
        return moving;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        s = Math.min(w / W, h / H);
        ox = (w - W * s) / 2f;
        oy = (h - H * s) / 2f;
        snap = true;
        retarget();
    }

    // ---- drawing ----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        if (st == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16f : Math.min(64f, now - lastFrame);
        lastFrame = now;
        if (refreshClock() && screen == HOME) rebuild(null);
        autoScene(now);
        if (!ambientOn && screen == HOME && st.flag(Settings.AMBIENT) && Ambient.idle(now, lastInput, Ambient.IDLE_MS)) {
            ambientOn = true;
            Log.i(TAG, "ambient on (idle)");
        }
        boolean moving = step(dt);
        c.drawColor(INK);
        c.save();
        c.translate(ox, oy);
        c.scale(s, s);
        c.save();
        c.translate(parX * (1f - amb), parY * (1f - amb));
        c.scale(1.03f, 1.03f, W / 2f, H / 2f);
        bg.draw(c, (now - t0) / 1000.0, st.motion(), sceneFrom, sceneTo, now - sceneSince);
        c.restore();
        g.setTint(Options.GLASS_TINT[st.glass()]);
        if (amb < 0.995f) {
            boolean layer = amb > 0.005f;
            if (layer) c.saveLayerAlpha(0f, 0f, W, H, Math.round((1f - amb) * 255f));
            moving |= drawUi(c, now);
            if (layer) c.restore();
        }
        if (amb > 0.005f) drawAmbient(c, now);
        c.restore();
        if (st.flag(Settings.QUIET)) {
            float dim = Ambient.quietDim(minuteOfDay);
            if (dim > 0.004f) c.drawColor(((int) (dim * 255f) << 24));
        }
        boolean fading = now - sceneSince < Scenes.FADE_MS;
        long delay = (st.motion() != 2 || fading || moving) ? 33L : 500L;
        postInvalidateDelayed(delay);
    }

    /** @return true while an entrance is still animating */
    private boolean drawUi(Canvas c, long now) {
        boolean moving = false;
        long since = now - screenSince;
        c.save();
        c.translate(0f, camY);
        if (screen == QUICK) {
            // Only Home's text shows under the scrim: redrawing every glass tile beneath an
            // opaque panel cost a 2018 stick ~10% janky frames for no visible gain.
            drawHomeStatic(c);
            float in = Motion.easeOut(since / 320f);
            moving = in < 1f;
            g.fill.setShader(null);
            g.fill.setColor(((int) (0xA6 * in) << 24) | 0x05070D);
            c.drawRect(0f, 0f, W, H, g.fill);
            c.translate((1f - in) * 140f, 0f);
            g.fill.setColor(0xF20A0D18);
            c.drawRoundRect(PANEL_L, 64f, PANEL_R, 1016f, 40f, 40f, g.fill);
            g.glass(c, PANEL_L, 64f, PANEL_R, 1016f, 40f, 0.25f);
            g.text.setLetterSpacing(-0.02f);
            g.label(c, "Quick settings", PANEL_L + 40f, 150f, 44f, g.medium, 0xFFFFFFFF, Paint.Align.LEFT);
            g.text.setLetterSpacing(0f);
            g.label(c, clockText + "  ·  " + dateText, PANEL_L + 40f, 192f, 22f, g.regular, 0xB3FFFFFF, Paint.Align.LEFT);
        } else if (screen == HOME) {
            drawHomeStatic(c);
        } else if (screen == APPS) {
            drawAppsStatic(c);
        } else {
            drawCustomStatic(c);
        }
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i == focus) continue; // drawn once, on top, by drawFocus
            if (it.t + it.h + camY < -40f || it.t + camY > H + 40f) continue;
            float p = Motion.stagger(since, i, 26L, 460L);
            if (p < 1f) moving = true;
            c.save();
            c.translate(it.l, it.t + (1f - p) * 34f);
            drawItem(c, it, 0f);
            c.restore();
        }
        drawFocus(c);
        c.restore();
        float fade = Math.min(1f, since / 260f);
        if (fade < 1f && screen != QUICK) {
            g.fill.setShader(null);
            g.fill.setColor(((int) ((1f - fade) * 0x99) << 24) | 0x05070D);
            c.drawRect(0f, 0f, W, H, g.fill);
            moving = true;
        }
        return moving;
    }

    /** A big, slowly wandering clock over the scenes; it moves a little so nothing burns in. */
    private void drawAmbient(Canvas c, long now) {
        double t = (now - t0) / 1000.0;
        float dx = (float) Math.sin(t / 47.0) * 60f, dy = (float) Math.cos(t / 61.0) * 34f;
        int a = Math.round(amb * 255f);
        g.text.setTypeface(g.light);
        g.text.setTextSize(240f);
        g.text.setColor((a << 24) | 0xFFFFFF);
        g.text.setTextAlign(Paint.Align.CENTER);
        g.text.setLetterSpacing(-0.04f);
        c.drawText(clockText, W / 2f + dx, 560f + dy, g.text);
        g.text.setLetterSpacing(0f);
        g.label(c, dateText, W / 2f + dx, 640f + dy, 34f, g.regular, (Math.round(amb * 0xCC) << 24) | 0xFFFFFF,
                Paint.Align.CENTER);
        g.label(c, "Press any button", W / 2f, 1010f, 20f, g.regular, (Math.round(amb * 0x66) << 24) | 0xFFFFFF,
                Paint.Align.CENTER);
    }

    private void drawHomeStatic(Canvas c) {
        float x = 96f;
        if (st.flag(Settings.GREETING) || st.flag(Settings.DATE)) {
            String line = st.flag(Settings.DATE) && st.flag(Settings.GREETING) ? dateText + " · " + greetText
                    : st.flag(Settings.DATE) ? dateText : greetText;
            g.text.setLetterSpacing(0f);
            g.label(c, line, x, st.flag(Settings.CLOCK) ? 208f : 120f, 30f, g.regular, 0xD9FFFFFF, Paint.Align.LEFT);
        }
        if (st.flag(Settings.CLOCK)) {
            g.text.setTypeface(g.light);
            g.text.setTextSize(112f);
            g.text.setColor(0xFFFFFFFF);
            g.text.setTextAlign(Paint.Align.LEFT);
            g.text.setLetterSpacing(-0.036f);
            c.drawText(clockText, x, 159f, g.text);
            g.text.setLetterSpacing(0f);
        }
        if (st.flag(Settings.PROFILE)) {
            float pw = profileWidth(), px = 1824f - pw;
            g.glass(c, px, 64f, px + pw, 136f, 36f, 0f);
            g.fill.setShader(null);
            g.fill.setColor(st.accent());
            c.drawCircle(px + 36f, 100f, 26f, g.fill);
            String initial = st.name().substring(0, 1).toUpperCase(Locale.getDefault());
            g.label(c, initial, px + 36f, 108f, 24f, g.medium, 0xFF05070D, Paint.Align.CENTER);
            g.label(c, st.name(), px + 76f, 109f, 24f, g.regular, 0xFFFFFFFF, Paint.Align.LEFT);
        }
        if (st.flag(Settings.PHOTO)) {
            String label = sceneLabel();
            float py = photoTop();
            float pw = 22f + g.measure(label, 20f, g.regular) + 16f + 44f + 16f + 44f + 12f;
            g.glass(c, 96f, py, 96f + pw, py + 64f, 32f, 0f);
            g.label(c, label, 96f + 22f, py + 38f, 20f, g.regular, 0xE6FFFFFF, Paint.Align.LEFT);
        }
        float top = HomeLayout.top(dock.size());
        if (!dock.isEmpty()) {
            g.label(c, "Continue", 96f, top - 22f, 26f, g.medium, 0xE6FFFFFF, Paint.Align.LEFT);
            if (dock.size() > 1) {
                SpatialNav.Box b1 = HomeLayout.row(dock.size())[1];
                g.label(c, "Favorites", b1.l, b1.t - 22f, 26f, g.medium, 0xE6FFFFFF, Paint.Align.LEFT);
            }
        } else if (loaded) {
            g.label(c, "Press Menu on any app in All apps to pin it here", 96f + 192f + 20f, 940f, 22f,
                    g.regular, 0xA6FFFFFF, Paint.Align.LEFT);
        }
    }

    private void drawAppsStatic(Canvas c) {
        g.text.setLetterSpacing(-0.023f);
        g.label(c, "All apps", 96f, 122f, 64f, g.medium, 0xFFFFFFFF, Paint.Align.LEFT);
        g.text.setLetterSpacing(0f);
        String n = sorted.size() + " installed on this device · Press Menu to pin or unpin";
        g.label(c, n, 96f, 169f, 24f, g.regular, 0xD9FFFFFF, Paint.Align.LEFT);
        if (loaded && sorted.isEmpty()) {
            g.label(c, "No apps found", 96f, 260f, 28f, g.regular, 0xA6FFFFFF, Paint.Align.LEFT);
        }
    }

    private void drawCustomStatic(Canvas c) {
        g.text.setLetterSpacing(-0.023f);
        g.label(c, "Customize home", 96f, 122f, 64f, g.medium, 0xFFFFFFFF, Paint.Align.LEFT);
        g.text.setLetterSpacing(0f);
        g.label(c, "Pick what shows on Home and the scenes that move behind it. No ads, ever.", 96f, 169f, 24f,
                g.regular, 0xD9FFFFFF, Paint.Align.LEFT);
        g.glass(c, 96f, 208f, 944f, 1016f, 40f, 0f);
        g.glass(c, 976f, 208f, 1824f, 1016f, 40f, 0f);
        int on = 0;
        for (Item it : items) on += it.kind == TOGGLE && it.on ? 1 : 0;
        g.label(c, "Home", 136f, 278f, 32f, g.medium, 0xFFFFFFFF, Paint.Align.LEFT);
        g.label(c, on + " on", 904f, 276f, 20f, g.regular, 0xCCFFFFFF, Paint.Align.RIGHT);
        g.label(c, "Background", 1016f, 278f, 32f, g.medium, 0xFFFFFFFF, Paint.Align.LEFT);
        String[] titles = {"Scenes", "Motion", "Change scene every", "Glass", "Accent"};
        float[] ys = {329f, 507f, 629f, 751f, 873f}; // baselines, 19 units above each control row
        for (int i = 0; i < titles.length; i++) {
            g.label(c, titles[i], 1016f, ys[i], 20f, g.regular, 0xD9FFFFFF, Paint.Align.LEFT);
        }
    }

    private void drawFocus(Canvas c) {
        if (focus < 0 || focus >= items.size()) return;
        Item it = items.get(focus);
        float r = radius(it);
        int glow = it.kind == HERO ? it.color : st.accent();
        g.fill.setShader(null);
        for (int k = 3; k >= 1; k--) {
            float grow = k * 9f;
            g.fill.setColor(((k == 1 ? 0x30 : k == 2 ? 0x1C : 0x0E) << 24) | (glow & 0xFFFFFF));
            c.drawRoundRect(fNow.left - grow, fNow.top - grow + 6f, fNow.right + grow, fNow.bottom + grow + 6f,
                    r + grow, r + grow, g.fill);
        }
        c.save();
        float dip = 1f - 0.045f * (float) Math.sin(Math.PI * press);
        c.translate(fNow.centerX(), fNow.centerY());
        c.scale(fNow.width() / it.w * dip, fNow.height() / it.h * dip);
        c.translate(-it.w / 2f, -it.h / 2f);
        drawItem(c, it, 1f);
        c.restore();
    }

    private static float radius(Item it) {
        switch (it.kind) {
            case CIRCLE:
            case PRIMARY:
            case CHIP:
            case DOT:
                return it.h / 2f;
            case TOGGLE:
            case ROW:
                return 24f;
            case SWATCH:
                return 20f;
            case HERO:
                return 40f;
            default:
                return 32f;
        }
    }

    /** Draws one item with its top-left at the origin. lift 0..1 is the focused look. */
    private void drawItem(Canvas c, Item it, float lift) {
        float w = it.w, h = it.h;
        switch (it.kind) {
            case CIRCLE:
                g.glass(c, 0f, 0f, w, h, h / 2f, lift);
                g.icon(c, it.icon, w / 2f, h / 2f, h > 60f ? 30f : 20f, 0xFFFFFFFF, h > 60f ? 2f : 2.4f);
                break;
            case HERO:
                drawHero(c, it, lift);
                break;
            case FAV:
                g.glass(c, 0f, 0f, w, h, 32f, lift);
                drawIcon(c, it, w / 2f - 40f, 30f, 80f, 22f);
                g.label(c, AppRepo.fit(it.label, g.text, w - 24f), w / 2f, 156f, 20f, g.medium, 0xFFFFFFFF,
                        Paint.Align.CENTER);
                break;
            case MORE:
                g.glass(c, 0f, 0f, w, h, 32f, lift);
                g.icon(c, Gfx.ICON_GRID, w / 2f, 70f, 48f, 0xFFFFFFFF, 1.6f);
                g.label(c, it.label, w / 2f, 156f, 20f, g.medium, 0xFFFFFFFF, Paint.Align.CENTER);
                break;
            case APPT:
                g.glass(c, 0f, 0f, w, h, 32f, lift);
                drawIcon(c, it, w / 2f - 42f, 38f, 84f, 22f);
                g.label(c, AppRepo.fit(it.label, g.text, w - 28f), w / 2f, 156f, 20f, g.medium, 0xFFFFFFFF,
                        Paint.Align.CENTER);
                if (it.pinned) badge(c, w);
                break;
            case ROW:
                drawRow(c, it, lift);
                break;
            case PRIMARY:
                g.fill.setShader(null);
                g.fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, g.fill);
                g.label(c, it.label, w / 2f, h / 2f + it.size * 0.36f, it.size, g.medium, 0xFF05070D, Paint.Align.CENTER);
                break;
            case CHIP:
                drawChip(c, it, lift);
                break;
            case TOGGLE:
                drawToggle(c, it);
                break;
            case SWATCH:
                g.fill.setShader(null);
                g.fill.setColor(it.color);
                c.drawRoundRect(0f, 0f, w, h, 20f, 20f, g.fill);
                g.label(c, it.label, w / 2f, h - 14f, 18f, g.medium, 0xE6FFFFFF, Paint.Align.CENTER);
                if (it.on) g.ring(c, 0f, 0f, w, h, 20f);
                break;
            default:
                g.fill.setShader(null);
                g.fill.setColor(it.color);
                c.drawCircle(w / 2f, h / 2f, h / 2f, g.fill);
                if (it.on) g.ring(c, 0f, 0f, w, h, h / 2f);
                break;
        }
        if (lift > 0f) g.ring(c, 0f, 0f, w, h, radius(it));
    }

    /** The continue card: the app's own colour washed across glass, its icon large, and Open. */
    private void drawHero(Canvas c, Item it, float lift) {
        float w = it.w, h = it.h;
        g.glass(c, 0f, 0f, w, h, 40f, lift * 0.6f);
        if (it.shade != null) {
            g.matrix().setTranslate(-it.l, -it.t);
            it.shade.setLocalMatrix(g.matrix());
            g.fill.setShader(it.shade);
            c.drawRoundRect(0f, 0f, w, h, 40f, 40f, g.fill);
            g.fill.setShader(null);
        }
        if (it.glow != null) {
            // A soft pool of the app's own colour behind the right half, for depth.
            g.matrix().setTranslate(-it.l, -it.t);
            it.glow.setLocalMatrix(g.matrix());
            g.fill.setShader(it.glow);
            c.drawRoundRect(0f, 0f, w, h, 40f, 40f, g.fill);
            g.fill.setShader(null);
        }
        drawIcon(c, it, 36f, 36f, 104f, 26f);
        g.text.setLetterSpacing(-0.015f);
        g.label(c, AppRepo.fit(it.label, g.text, w - 200f), 164f, 86f, 40f, g.medium, 0xFFFFFFFF, Paint.Align.LEFT);
        g.text.setLetterSpacing(0f);
        g.label(c, it.sub, 164f, 124f, 22f, g.regular, 0xCCFFFFFF, Paint.Align.LEFT);
        float bw = 52f + 26f + g.measure("Open", 22f, g.medium) + 26f, by = h - 36f - 60f;
        g.fill.setShader(null);
        g.fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(36f, by, 36f + bw, by + 60f, 30f, 30f, g.fill);
        g.icon(c, Gfx.ICON_PLAY, 36f + 36f, by + 30f, 26f, 0xFF05070D, 2.6f);
        g.label(c, "Open", 36f + 58f, by + 38f, 22f, g.medium, 0xFF05070D, Paint.Align.LEFT);
        if (lift > 0f) {
            g.label(c, "Menu to unpin", w - 36f, h - 56f, 18f, g.regular, 0x99FFFFFF, Paint.Align.RIGHT);
        }
    }

    private void drawRow(Canvas c, Item it, float lift) {
        float w = it.w, h = it.h;
        g.fill.setShader(null);
        g.fill.setColor(lift > 0f ? 0x2EFFFFFF : 0x12FFFFFF);
        c.drawRoundRect(0f, 0f, w, h, 24f, 24f, g.fill);
        g.fill.setColor(lift > 0f ? st.accent() : 0x1FFFFFFF);
        c.drawCircle(52f, h / 2f, 30f, g.fill);
        g.icon(c, it.icon, 52f, h / 2f, 28f, lift > 0f ? 0xFF05070D : 0xFFFFFFFF, 2f);
        g.label(c, it.label, 104f, h / 2f - 4f, 26f, g.medium, 0xFFFFFFFF, Paint.Align.LEFT);
        g.label(c, it.sub, 104f, h / 2f + 28f, 19f, g.regular, 0xB3FFFFFF, Paint.Align.LEFT);
        g.icon(c, Gfx.ICON_NEXT, w - 36f, h / 2f, 22f, lift > 0f ? 0xFFFFFFFF : 0x80FFFFFF, 2.2f);
    }

    private void drawIcon(Canvas c, Item it, float x, float y, float size, float radius) {
        if (it.art == null) {
            g.fill.setShader(null);
            g.fill.setColor(0x33FFFFFF);
            c.drawRoundRect(x, y, x + size, y + size, radius, radius, g.fill);
            return;
        }
        drawArt(c, it.art, x, y, size, radius);
    }

    private void drawArt(Canvas c, TileArt a, float x, float y, float size, float radius) {
        g.matrix().setScale(size / a.bitmap.getWidth(), size / a.bitmap.getHeight());
        g.matrix().postTranslate(x, y);
        a.shader.setLocalMatrix(g.matrix());
        g.img.setShader(a.shader);
        c.drawRoundRect(x, y, x + size, y + size, radius, radius, g.img);
        g.img.setShader(null);
    }

    private void badge(Canvas c, float w) {
        float tw = g.measure("PINNED", 14f, g.medium) + 20f + 1.5f * 6f;
        g.fill.setShader(null);
        g.fill.setColor(st.accent());
        c.drawRoundRect(w - 14f - tw, 14f, w - 14f, 40f, 13f, 13f, g.fill);
        g.text.setLetterSpacing(0.035f);
        g.label(c, "PINNED", w - 14f - tw / 2f, 33f, 14f, g.medium, 0xFF05070D, Paint.Align.CENTER);
        g.text.setLetterSpacing(0f);
    }

    private void drawChip(Canvas c, Item it, float lift) {
        float w = it.w, h = it.h;
        g.fill.setShader(null);
        boolean dark = it.dark;
        if (it.on && dark) g.fill.setColor(0xFFFFFFFF);
        else if (it.on) g.fill.setColor(0x52FFFFFF);
        else g.fill.setColor(lift > 0f ? 0x38FFFFFF : 0x1AFFFFFF);
        c.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, g.fill);
        g.line.setShader(null);
        g.line.setStrokeWidth(1f);
        g.line.setColor(0x4DFFFFFF);
        c.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, h / 2f, h / 2f, g.line);
        g.label(c, it.label, w / 2f, h / 2f + 7f, 20f, g.medium, it.on && dark ? 0xFF05070D : 0xFFFFFFFF,
                Paint.Align.CENTER);
    }

    private void drawToggle(Canvas c, Item it) {
        float w = it.w, h = it.h;
        g.fill.setShader(null);
        g.fill.setColor(it.on ? 0x33FFFFFF : 0x0FFFFFFF);
        c.drawRoundRect(0f, 0f, w, h, 24f, 24f, g.fill);
        g.line.setShader(null);
        g.line.setStrokeWidth(1f);
        g.line.setColor(it.on ? 0x99FFFFFF : 0x33FFFFFF);
        c.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, 24f, 24f, g.line);
        g.label(c, it.label, 22f, 42f, 24f, g.medium, 0xFFFFFFFF, Paint.Align.LEFT);
        g.label(c, AppRepo.fit(it.sub, g.text, w - 130f), 22f, 70f, 18f, g.regular, 0xCCFFFFFF, Paint.Align.LEFT);
        float tx = w - 22f - 60f, ty = h / 2f - 17f;
        g.fill.setColor(it.on ? st.accent() : 0x40FFFFFF);
        c.drawRoundRect(tx, ty, tx + 60f, ty + 34f, 17f, 17f, g.fill);
        g.fill.setColor(0xFFFFFFFF);
        c.drawCircle(it.on ? tx + 60f - 17f : tx + 17f, ty + 17f, 14f, g.fill);
    }

    // ---- remote control ---------------------------------------------------------------------

    private static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    private boolean move(int dir) {
        if (focus < 0 || boxes.length == 0) return true;
        int next = SpatialNav.find(boxes, focus, dir, prefX);
        if (next >= 0) {
            focus = next;
            if (dir == SpatialNav.LEFT || dir == SpatialNav.RIGHT) prefX = boxes[next].cx();
            retarget();
        }
        return true;
    }

    private void pinFocused() {
        if (focus < 0 || focus >= items.size()) return;
        Log.i(TAG, "menu " + items.get(focus).id);
        Runnable menu = items.get(focus).menu;
        if (menu != null) menu.run();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        lastInput = SystemClock.uptimeMillis();
        if (ambientOn || swallowUp) {
            // The first press only wakes the screen; its release must not also act.
            if (ambientOn) {
                Log.i(TAG, "ambient off (key)");
                ambientOn = false;
                swallowUp = true;
                invalidate();
            }
            return code != KeyEvent.KEYCODE_HOME || super.onKeyDown(code, ev);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return move(SpatialNav.UP);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return move(SpatialNav.DOWN);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return move(SpatialNav.LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return move(SpatialNav.RIGHT);
            case KeyEvent.KEYCODE_MENU:
                if (ev.getRepeatCount() == 0) pinFocused();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (screen != HOME) {
                    ev.startTracking();
                    return true;
                }
                return super.onKeyDown(code, ev);
            default:
                if (isSelect(code)) {
                    if (ev.getRepeatCount() == 0) {
                        longFired = false;
                        ev.startTracking();
                    }
                    return true;
                }
                return super.onKeyDown(code, ev);
        }
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent ev) {
        if (swallowUp) return true;
        if (isSelect(code)) {
            longFired = true;
            pinFocused();
            return true;
        }
        return super.onKeyLongPress(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        lastInput = SystemClock.uptimeMillis();
        if (swallowUp) {
            swallowUp = false;
            longFired = false;
            return true;
        }
        if (isSelect(code)) {
            if (!longFired && ev.isTracking() && !ev.isCanceled() && focus >= 0 && focus < items.size()) {
                Runnable select = items.get(focus).select;
                press = 1f;
                Log.i(TAG, "select " + items.get(focus).id);
                postInvalidateOnAnimation();
                if (select != null) select.run();
            }
            longFired = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && screen != HOME && ev.isTracking() && !ev.isCanceled()) {
            go(HOME);
            return true;
        }
        return super.onKeyUp(code, ev);
    }
}
