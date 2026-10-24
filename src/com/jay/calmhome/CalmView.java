package com.jay.calmhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
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
 * The whole UI on one Canvas, in the Liquid Glass design's 1920x1080 units: Home, All apps and
 * Customize. Focus is geometric (SpatialNav); everything focusable is an Item with a rectangle.
 */
final class CalmView extends View {
    interface Host {
        void launch(AppEntry e);

        void togglePin(AppEntry e);

        void openSettings();
    }

    static final int HOME = 0, APPS = 1, CUSTOM = 2;
    static final int MAX_DOCK = 7;

    private static final int CIRCLE = 0, FAV = 1, MORE = 2, PRIMARY = 3, CHIP = 4, APPT = 5,
            TOGGLE = 6, SWATCH = 7, DOT = 8;
    private static final float W = Background.W, H = Background.H;
    private static final int INK = 0xFF05070D;

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
    private boolean snap = true;
    private final long t0 = SystemClock.uptimeMillis();
    private int sceneFrom, sceneTo;
    private long sceneSince = -1000000L, lastAuto = SystemClock.uptimeMillis();
    private long lastFrame, screenSince = SystemClock.uptimeMillis();
    private boolean longFired;

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
        // The first real data replaces a placeholder-only screen: start on the first favourite,
        // not wherever focus happened to sit while the list was loading.
        rebuild(first ? "#default" : null);
    }

    void showHome() {
        if (screen != HOME) go(HOME);
    }

    boolean onHome() {
        return screen == HOME;
    }

    private void recompute() {
        pins = AppOrder.parsePins(st.pins());
        AppOrder.Split sp = AppOrder.split(all, pins, MAX_DOCK);
        dock = sp.dock;
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

    private void go(int to) {
        screen = to;
        camY = camGoal = 0f;
        screenSince = SystemClock.uptimeMillis();
        snap = true;
        recompute();
        rebuild("#default");
    }

    // ---- building screens -------------------------------------------------------------------

    private void rebuild(String keepId) {
        String keep = keepId != null ? keepId : (focus < items.size() ? items.get(focus).id : null);
        items.clear();
        if (screen == HOME) buildHome();
        else if (screen == APPS) buildApps();
        else buildCustom();
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
        int want = screen == HOME ? FAV : screen == APPS ? APPT : TOGGLE;
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
        Item set = add(new Item(CIRCLE, "settings", right - 160f, 64f, 72f, 72f));
        set.icon = Gfx.ICON_GEAR;
        set.label = "Settings";
        set.select = new Runnable() {
            @Override
            public void run() {
                host.openSettings();
            }
        };
        if (st.flag(Settings.PHOTO)) {
            float tw = g.measure(sceneLabel(), 20f, g.regular);
            Item prev = add(new Item(CIRCLE, "scene-prev", 96f + 22f + tw + 16f, 721f, 44f, 44f));
            prev.icon = Gfx.ICON_PREV;
            prev.label = "Previous photo";
            prev.select = new Runnable() {
                @Override
                public void run() {
                    stepScene(-1);
                }
            };
            Item next = add(new Item(CIRCLE, "scene-next", prev.l + 60f, 721f, 44f, 44f));
            next.icon = Gfx.ICON_NEXT;
            next.label = "Next photo";
            next.select = new Runnable() {
                @Override
                public void run() {
                    stepScene(1);
                }
            };
        }
        for (int i = 0; i < dock.size(); i++) {
            final AppEntry e = dock.get(i);
            Item it = add(new Item(FAV, "app:" + e.pkg, 96f + i * 214f, 858f, 190f, 150f));
            appItem(it, e);
        }
        Item more = add(new Item(MORE, "more", 96f + dock.size() * 214f, 858f, 190f, 150f));
        more.label = "All apps";
        more.icon = Gfx.ICON_GRID;
        more.select = new Runnable() {
            @Override
            public void run() {
                go(APPS);
            }
        };
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
            case APPT:
                return 1.08f;
            case DOT:
                return 1.15f;
            case SWATCH:
                return 1.05f;
            default:
                return 1.04f;
        }
    }

    /** Recomputes where focus and the camera are heading. */
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
            announceForAccessibility(it.label);
        }
        if (snap) {
            fNow.set(fGoal);
            camY = camGoal;
            snap = false;
        }
        postInvalidateOnAnimation();
    }

    private boolean step(float dt) {
        float k = 1f - (float) Math.exp(-dt / 80f);
        boolean moving = false;
        camY += (camGoal - camY) * k;
        if (Math.abs(camGoal - camY) < 0.4f) camY = camGoal;
        else moving = true;
        moving |= approach(k);
        return moving;
    }

    private boolean approach(float k) {
        fNow.left += (fGoal.left - fNow.left) * k;
        fNow.top += (fGoal.top - fNow.top) * k;
        fNow.right += (fGoal.right - fNow.right) * k;
        fNow.bottom += (fGoal.bottom - fNow.bottom) * k;
        if (Math.abs(fNow.left - fGoal.left) < 0.3f && Math.abs(fNow.top - fGoal.top) < 0.3f
                && Math.abs(fNow.right - fGoal.right) < 0.3f && Math.abs(fNow.bottom - fGoal.bottom) < 0.3f) {
            fNow.set(fGoal);
            return false;
        }
        return true;
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
        boolean moving = step(dt);
        c.drawColor(INK);
        c.save();
        c.translate(ox, oy);
        c.scale(s, s);
        bg.draw(c, (now - t0) / 1000.0, st.motion(), sceneFrom, sceneTo, now - sceneSince);
        g.setTint(Options.GLASS_TINT[st.glass()]);
        c.save();
        c.translate(0f, camY);
        if (screen == HOME) drawHomeStatic(c);
        else if (screen == APPS) drawAppsStatic(c);
        else drawCustomStatic(c);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i == focus) continue; // drawn once, on top, by drawFocus
            if (it.t + it.h + camY < -40f || it.t + camY > H + 40f) continue;
            c.save();
            c.translate(it.l, it.t);
            drawItem(c, it, 0f);
            c.restore();
        }
        drawFocus(c);
        c.restore();
        float fade = Math.min(1f, (now - screenSince) / 240f);
        if (fade < 1f) {
            g.fill.setShader(null);
            g.fill.setColor(((int) ((1f - fade) * 0x99) << 24) | 0x05070D);
            c.drawRect(0f, 0f, W, H, g.fill);
            moving = true;
        }
        c.restore();
        if (st.flag(Settings.QUIET)) {
            float dim = Ambient.quietDim(minuteOfDay);
            if (dim > 0.004f) c.drawColor(((int) (dim * 255f) << 24));
        }
        boolean fading = now - sceneSince < Scenes.FADE_MS;
        long delay = (st.motion() != 2 || fading || moving) ? 33L : 500L;
        postInvalidateDelayed(delay);
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
            float pw = 22f + g.measure(label, 20f, g.regular) + 16f + 44f + 16f + 44f + 12f;
            g.glass(c, 96f, 711f, 96f + pw, 775f, 32f, 0f);
            g.label(c, label, 96f + 22f, 749f, 20f, g.regular, 0xE6FFFFFF, Paint.Align.LEFT);
        }
        g.label(c, "Favorites", 96f, 833f, 26f, g.medium, 0xE6FFFFFF, Paint.Align.LEFT);
        if (loaded && dock.isEmpty()) {
            g.label(c, "Press Menu on any app in All apps to pin it here", 96f + 214f + 20f, 940f, 22f,
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
        c.save();
        c.translate(fNow.centerX(), fNow.centerY());
        c.scale(fNow.width() / it.w, fNow.height() / it.h);
        c.translate(-it.w / 2f, -it.h / 2f);
        drawItem(c, it, 1f);
        c.restore();
    }

    /** Draws one item with its top-left at the origin. lift 0..1 is the focused look. */
    private void drawItem(Canvas c, Item it, float lift) {
        float w = it.w, h = it.h;
        switch (it.kind) {
            case CIRCLE:
                g.glass(c, 0f, 0f, w, h, h / 2f, lift);
                g.icon(c, it.icon, w / 2f, h / 2f, h > 60f ? 30f : 20f, 0xFFFFFFFF, h > 60f ? 2f : 2.4f);
                break;
            case FAV:
                g.glass(c, 0f, 0f, w, h, 32f, lift);
                drawIcon(c, it, w / 2f - 36f, 22f, 72f, 20f);
                g.label(c, AppRepo.fit(it.label, g.text, w - 24f), w / 2f, 122f, 20f, g.medium, 0xFFFFFFFF,
                        Paint.Align.CENTER);
                break;
            case MORE:
                g.glass(c, 0f, 0f, w, h, 32f, lift);
                g.icon(c, Gfx.ICON_GRID, w / 2f, 58f, 44f, 0xFFFFFFFF, 1.6f);
                g.label(c, it.label, w / 2f, 122f, 20f, g.medium, 0xFFFFFFFF, Paint.Align.CENTER);
                break;
            case APPT:
                g.glass(c, 0f, 0f, w, h, 32f, lift);
                drawIcon(c, it, w / 2f - 42f, 38f, 84f, 22f);
                g.label(c, AppRepo.fit(it.label, g.text, w - 28f), w / 2f, 156f, 20f, g.medium, 0xFFFFFFFF,
                        Paint.Align.CENTER);
                if (it.pinned) badge(c, w);
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
        if (lift > 0f) {
            float r = it.kind == CIRCLE || it.kind == PRIMARY || it.kind == CHIP || it.kind == DOT ? h / 2f
                    : it.kind == TOGGLE ? 24f : it.kind == SWATCH ? 20f : 32f;
            g.ring(c, 0f, 0f, w, h, r);
        }
    }

    private void drawIcon(Canvas c, Item it, float x, float y, float size, float radius) {
        if (it.art == null) {
            g.fill.setShader(null);
            g.fill.setColor(0x33FFFFFF);
            c.drawRoundRect(x, y, x + size, y + size, radius, radius, g.fill);
            return;
        }
        g.matrix().setScale(size / it.art.bitmap.getWidth(), size / it.art.bitmap.getHeight());
        g.matrix().postTranslate(x, y);
        it.art.shader.setLocalMatrix(g.matrix());
        g.img.setShader(it.art.shader);
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
        Runnable menu = items.get(focus).menu;
        if (menu != null) menu.run();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
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
        if (isSelect(code)) {
            longFired = true;
            pinFocused();
            return true;
        }
        return super.onKeyLongPress(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if (isSelect(code)) {
            if (!longFired && ev.isTracking() && !ev.isCanceled() && focus >= 0 && focus < items.size()) {
                Runnable select = items.get(focus).select;
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
