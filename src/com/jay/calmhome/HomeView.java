package com.jay.calmhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole home screen, drawn on one Canvas: a dark room lit by a glow that follows the time of
 * day and takes on the colour of whichever app has focus. Layout is in design units on a
 * 1920x1080 canvas; {@link #s} turns them into pixels.
 */
final class HomeView extends View {
    interface Listener {
        void onLaunch(AppEntry e);

        void onTogglePin(AppEntry e);

        void onSettings();
    }

    static final int COLS = 6, MAX_DOCK = 5;

    private static final float MX = 112;
    private static final float DOCK_Y = 360, DW = 300, DH = 169, DG = 30;
    private static final float GRID_Y = 668, GW = 250, GH = 141, GG = 26, PITCH = GH + GG + 40;
    private static final float GRID_LIFT = 588;
    private static final int VISIBLE_ROWS = 4;
    private static final float RADIUS = 14;
    private static final int ROOM = 0xFF06060A;

    private final NavModel nav = new NavModel(COLS);
    private List<AppEntry> dock = Collections.emptyList();
    private List<AppEntry> grid = Collections.emptyList();
    private Map<String, TileArt> art = Collections.emptyMap();
    private Listener listener;
    private boolean loaded;

    private float s = 1f;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint shade = new Paint();
    private final Matrix matrix = new Matrix();
    private final RectF r = new RectF();
    private final RectF focusNow = new RectF();
    private final RectF focusGoal = new RectF();
    private final Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private float camY, camGoal;
    private int glowNow = Ambient.skyTint(0), glowGoal = glowNow;
    private int glowShaderColor = -1, skyShaderColor = -1;
    private RadialGradient glowShader, skyShader;
    private LinearGradient topFade, bottomFade;
    private float dimNow;
    private long lastFrame;
    private boolean snap = true;
    private boolean longFired;

    private long clockMinute = -1;
    private int minuteOfDay;
    private String clockMain = "", clockSuffix = "", dateText = "", greetText = "";

    HomeView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        ring.setStyle(Paint.Style.STROKE);
        updateClock();
        glowNow = glowGoal = Ambient.skyTint(minuteOfDay);
        dimNow = Ambient.quietDim(minuteOfDay);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Replaces the tiles. Focus follows restorePkg (or the current app) to its new position. */
    void setData(List<AppEntry> newDock, List<AppEntry> newGrid, Map<String, TileArt> newArt, String restorePkg) {
        String keep = restorePkg != null ? restorePkg : focusedPkg();
        dock = newDock;
        grid = newGrid;
        art = newArt;
        boolean first = !loaded;
        loaded = true;
        nav.setCounts(1, dock.size(), grid.size());
        if (keep != null) {
            int i = indexOf(dock, keep);
            if (i >= 0) nav.focus(NavModel.DOCK, i);
            else if ((i = indexOf(grid, keep)) >= 0) nav.focus(NavModel.GRID, i);
        }
        if (first) snap = true;
        retarget();
        invalidate();
    }

    /** Clock or time zone changed: recompute on the next frame. */
    void onClockChanged() {
        clockMinute = -1;
        invalidate();
    }

    String focusedPkg() {
        AppEntry e = focusedEntry();
        return e == null ? null : e.pkg;
    }

    AppEntry focusedEntry() {
        if (!nav.hasFocus()) return null;
        int i = nav.index();
        if (nav.zone() == NavModel.DOCK && i < dock.size()) return dock.get(i);
        if (nav.zone() == NavModel.GRID && i < grid.size()) return grid.get(i);
        return null;
    }

    private static int indexOf(List<AppEntry> list, String pkg) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).pkg.equals(pkg)) return i;
        }
        return -1;
    }

    // ---- geometry ---------------------------------------------------------------------------

    private RectF dockRect(int i, RectF out) {
        float x = (MX + i * (DW + DG)) * s, y = DOCK_Y * s;
        out.set(x, y, x + DW * s, y + DH * s);
        return out;
    }

    private RectF gridRect(int i, RectF out) {
        int row = i / COLS, col = i % COLS;
        float x = (MX + col * (GW + GG)) * s, y = (GRID_Y + row * PITCH) * s;
        out.set(x, y, x + GW * s, y + GH * s);
        return out;
    }

    private RectF settingsRect(RectF out) {
        float w = 188, h = 60, right = 1920 - MX, top = 84;
        out.set((right - w) * s, top * s, right * s, (top + h) * s);
        return out;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        s = Math.min(w / 1920f, h / 1080f);
        glowShader = null;
        skyShader = null;
        topFade = new LinearGradient(0, 0, 0, 96 * s, ROOM, ROOM & 0x00FFFFFF, Shader.TileMode.CLAMP);
        bottomFade = new LinearGradient(0, h - 120 * s, 0, h, ROOM & 0x00FFFFFF, ROOM, Shader.TileMode.CLAMP);
        snap = true;
        retarget();
    }

    /** Recomputes where focus, camera and glow are heading. */
    private void retarget() {
        updateClock();
        int z = nav.zone();
        if (nav.hasFocus()) {
            if (z == NavModel.TOP) {
                settingsRect(focusGoal);
                focusGoal.inset(-4 * s, -4 * s);
            } else {
                if (z == NavModel.DOCK) dockRect(nav.index(), focusGoal);
                else gridRect(nav.index(), focusGoal);
                focusGoal.inset(-11 * s, -11 * s * 9f / 16f);
            }
        }
        camGoal = z == NavModel.GRID && nav.hasFocus()
                ? -(GRID_LIFT + NavModel.scrollRows(nav.row(), VISIBLE_ROWS) * PITCH) * s : 0f;
        AppEntry e = focusedEntry();
        TileArt t = e == null ? null : art.get(e.key());
        int sky = Ambient.skyTint(minuteOfDay);
        glowGoal = t == null ? sky : Ambient.mix(sky, t.glow, 0.7f);
        if (snap && getWidth() > 0) {
            focusNow.set(focusGoal);
            camY = camGoal;
            glowNow = glowGoal;
            snap = false;
        }
        postInvalidateOnAnimation();
    }

    /** @return true when the minute changed. */
    private boolean updateClock() {
        long minute = System.currentTimeMillis() / 60000L;
        if (minute == clockMinute) return false;
        clockMinute = minute;
        Calendar cal = Calendar.getInstance();
        minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        Date d = cal.getTime();
        String full = android.text.format.DateFormat.getTimeFormat(getContext()).format(d);
        int cut = Math.max(full.lastIndexOf(' '), Math.max(full.lastIndexOf(' '), full.lastIndexOf(' ')));
        if (cut > 0 && !full.substring(cut + 1).matches(".*\\d.*")) {
            clockMain = full.substring(0, cut).trim();
            clockSuffix = full.substring(cut + 1).trim();
        } else {
            clockMain = full;
            clockSuffix = "";
        }
        dateText = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(d);
        greetText = Ambient.greeting(minuteOfDay);
        return true;
    }

    // ---- drawing ----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16f : Math.min(64f, now - lastFrame);
        lastFrame = now;
        if (updateClock()) {
            clockMinute = -1; // let retarget() see the same new minute
            retarget();
        }
        boolean moving = step(dt);
        drawRoom(c);
        c.save();
        c.translate(0, camY);
        drawHeader(c);
        drawDock(c);
        drawGrid(c);
        drawFocus(c);
        c.restore();
        drawEdges(c);
        if (dimNow > 0.002f) c.drawColor(((int) (dimNow * 255f)) << 24);
        if (moving) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    /** Eases camera, focus and glow toward their goals. @return true while still moving. */
    private boolean step(float dt) {
        float k = 1f - (float) Math.exp(-dt / 90f);
        boolean moving = false;
        camY += (camGoal - camY) * k;
        if (Math.abs(camGoal - camY) < 0.5f) camY = camGoal;
        else moving = true;
        moving |= approach(focusNow, focusGoal, k);
        int g = Ambient.mix(glowNow, glowGoal, Math.max(k * 0.55f, 0.02f));
        if (g == glowNow && g != glowGoal) g = glowGoal;
        glowNow = g;
        if (glowNow != glowGoal) moving = true;
        float dimGoal = Ambient.quietDim(minuteOfDay);
        dimNow += (dimGoal - dimNow) * k * 0.15f;
        if (Math.abs(dimGoal - dimNow) < 0.002f) dimNow = dimGoal;
        else moving = true;
        return moving;
    }

    private static boolean approach(RectF a, RectF b, float k) {
        a.left += (b.left - a.left) * k;
        a.top += (b.top - a.top) * k;
        a.right += (b.right - a.right) * k;
        a.bottom += (b.bottom - a.bottom) * k;
        if (Math.abs(a.left - b.left) < 0.5f && Math.abs(a.top - b.top) < 0.5f
                && Math.abs(a.right - b.right) < 0.5f && Math.abs(a.bottom - b.bottom) < 0.5f) {
            a.set(b);
            return false;
        }
        return true;
    }

    /** The room: near-black, a big soft glow top-left, and a fainter sky glow low on the right. */
    private void drawRoom(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(ROOM);
        if (glowShader == null || glowShaderColor != glowNow) {
            glowShaderColor = glowNow;
            glowShader = new RadialGradient(w * 0.16f, h * 0.02f, Math.max(w, h) * 0.9f,
                    new int[]{0x70000000 | glowNow, 0x26000000 | glowNow, 0x00000000 | glowNow},
                    new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
        }
        int sky = Ambient.skyTint(minuteOfDay);
        if (skyShader == null || skyShaderColor != sky) {
            skyShaderColor = sky;
            skyShader = new RadialGradient(w * 0.92f, h * 1.1f, w * 0.62f,
                    new int[]{0x38000000 | sky, 0x00000000 | sky}, null, Shader.TileMode.CLAMP);
        }
        c.save();
        c.translate(0, camY * 0.25f); // parallax: the light moves slower than the room
        fill.setShader(skyShader);
        c.drawRect(0, -h, w, h * 2, fill);
        fill.setShader(glowShader);
        c.drawRect(0, -h, w, h * 2, fill);
        fill.setShader(null);
        c.restore();
    }

    private void drawHeader(Canvas c) {
        float x = MX * s;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(light);
        text.setLetterSpacing(0.03f);
        text.setTextSize(30 * s);
        text.setColor(0x99FFFFFF);
        c.drawText(greetText, x, 118 * s, text);
        text.setTypeface(thin);
        text.setLetterSpacing(-0.01f);
        text.setTextSize(148 * s);
        text.setColor(0xF5FFFFFF);
        c.drawText(clockMain, x, 254 * s, text);
        if (!clockSuffix.isEmpty()) {
            float cw = text.measureText(clockMain);
            text.setTypeface(light);
            text.setLetterSpacing(0.04f);
            text.setTextSize(38 * s);
            text.setColor(0x99FFFFFF);
            c.drawText(clockSuffix, x + cw + 16 * s, 254 * s, text);
        }
        text.setTypeface(light);
        text.setLetterSpacing(0.01f);
        text.setTextSize(32 * s);
        text.setColor(0xB3FFFFFF);
        c.drawText(dateText, x, 306 * s, text);

        if (!(nav.zone() == NavModel.TOP && nav.hasFocus())) {
            settingsRect(r);
            fill.setColor(0x1AFFFFFF);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            text.setTypeface(medium);
            text.setLetterSpacing(0.02f);
            text.setTextSize(26 * s);
            text.setColor(0xCCFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Settings", r.centerX(), r.centerY() + 9 * s, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawDock(Canvas c) {
        if (!loaded) return;
        if (dock.isEmpty()) {
            dockRect(0, r);
            r.right = (MX + 3 * (DW + DG) - DG) * s;
            fill.setColor(0x0DFFFFFF);
            c.drawRoundRect(r, RADIUS * s, RADIUS * s, fill);
            text.setTypeface(light);
            text.setLetterSpacing(0.01f);
            text.setTextSize(28 * s);
            text.setColor(0x80FFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Press Menu on any app below to pin it here", r.centerX(), r.centerY() + 10 * s, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        dockRect(0, r);
        float left = r.left - 24 * s, top = r.top - 24 * s;
        dockRect(dock.size() - 1, r);
        r.set(left, top, r.right + 24 * s, r.bottom + 24 * s);
        fill.setColor(0x0BFFFFFF);
        c.drawRoundRect(r, (RADIUS + 12) * s, (RADIUS + 12) * s, fill);
        for (int i = 0; i < dock.size(); i++) drawTile(c, dockRect(i, r), dock.get(i), 214);
    }

    private void drawGrid(Canvas c) {
        if (!loaded) return;
        text.setTypeface(medium);
        text.setLetterSpacing(0.18f);
        text.setTextSize(21 * s);
        text.setColor(0x73FFFFFF);
        text.setTextAlign(Paint.Align.LEFT);
        String title = grid.isEmpty() && dock.isEmpty() ? "NO APPS FOUND" : "ALL APPS  ·  " + grid.size();
        c.drawText(title, MX * s, (GRID_Y - 30) * s, text);
        int h = getHeight();
        for (int i = 0; i < grid.size(); i++) {
            gridRect(i, r);
            if (r.bottom + camY < -PITCH * s || r.top + camY > h) continue;
            drawTile(c, r, grid.get(i), 200);
        }
    }

    private void drawTile(Canvas c, RectF rect, AppEntry e, int alpha) {
        float rad = RADIUS * s * (rect.width() / (DW * s) > 0.9f ? 1.1f : 1f);
        TileArt t = art.get(e.key());
        if (t != null) {
            matrix.setScale(rect.width() / AppRepo.ART_W, rect.height() / AppRepo.ART_H);
            matrix.postTranslate(rect.left, rect.top);
            t.shader.setLocalMatrix(matrix);
            tile.setShader(t.shader);
            tile.setAlpha(alpha);
            c.drawRoundRect(rect, rad, rad, tile);
            tile.setShader(null);
            return;
        }
        fill.setColor(0xFF1A1B22);
        fill.setAlpha(alpha);
        c.drawRoundRect(rect, rad, rad, fill);
        fill.setAlpha(255);
        text.setTypeface(medium);
        text.setLetterSpacing(0f);
        text.setTextSize(24 * s);
        text.setColor(0xD9FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(AppRepo.fit(e.label, text, rect.width() - 24 * s), rect.centerX(), rect.centerY() + 8 * s, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** The lifted, glowing focused tile with its name underneath. */
    private void drawFocus(Canvas c) {
        if (!loaded || !nav.hasFocus()) return;
        RectF f = focusNow;
        if (nav.zone() == NavModel.TOP) {
            float rad = f.height() / 2;
            fill.setColor(0xF2FFFFFF);
            c.drawRoundRect(f, rad, rad, fill);
            text.setTypeface(medium);
            text.setLetterSpacing(0.02f);
            text.setTextSize(27 * s);
            text.setColor(0xFF0B0B10);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Settings", f.centerX(), f.centerY() + 9.5f * s, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        AppEntry e = focusedEntry();
        if (e == null) return;
        float rad = RADIUS * s * 1.15f;
        ring.setStrokeWidth(7f * s);
        for (int k = 3; k >= 1; k--) {
            float g = k * 5.5f * s;
            r.set(f.left - g, f.top - g, f.right + g, f.bottom + g);
            ring.setColor(((0x2A - k * 7) << 24) | glowNow);
            c.drawRoundRect(r, rad + g, rad + g, ring);
        }
        drawTile(c, f, e, 255);
        ring.setStrokeWidth(3.5f * s);
        ring.setColor(0xF2FFFFFF);
        r.set(f);
        r.inset(-2.5f * s, -2.5f * s);
        c.drawRoundRect(r, rad + 2.5f * s, rad + 2.5f * s, ring);
        text.setTypeface(medium);
        text.setLetterSpacing(0.01f);
        text.setTextSize(28 * s);
        text.setColor(0xEEFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(AppRepo.fit(e.label, text, f.width() + 80 * s), f.centerX(), f.bottom + 42 * s, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** Soft fades where the grid slides under the screen edges, plus a quiet remote hint. */
    private void drawEdges(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (camY < -1f && topFade != null) {
            shade.setShader(topFade);
            c.drawRect(0, 0, w, 96 * s, shade);
        }
        if (bottomFade != null && grid.size() > COLS * 2) {
            shade.setShader(bottomFade);
            c.drawRect(0, h - 120 * s, w, h, shade);
        }
        shade.setShader(null);
        if (focusedEntry() != null) {
            text.setTypeface(light);
            text.setLetterSpacing(0.06f);
            text.setTextSize(21 * s);
            text.setColor(0x59FFFFFF);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("MENU  pin · unpin", w - MX * s, h - 40 * s, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ---- remote control ---------------------------------------------------------------------

    private static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    private boolean backGoesToDock() {
        return nav.zone() == NavModel.GRID && nav.count(NavModel.DOCK) > 0;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                go(NavModel.UP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                go(NavModel.DOWN);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                go(NavModel.LEFT);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                go(NavModel.RIGHT);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (ev.getRepeatCount() == 0) pinFocused();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (backGoesToDock()) {
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
            if (!longFired && ev.isTracking() && !ev.isCanceled()) activate();
            longFired = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && ev.isTracking() && !ev.isCanceled() && backGoesToDock()) {
            nav.enter(NavModel.DOCK);
            retarget();
            announce();
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    private void go(int dir) {
        if (nav.move(dir)) {
            retarget();
            announce();
        }
    }

    private void activate() {
        if (listener == null || !nav.hasFocus()) return;
        if (nav.zone() == NavModel.TOP) {
            listener.onSettings();
            return;
        }
        AppEntry e = focusedEntry();
        if (e != null) listener.onLaunch(e);
    }

    private void pinFocused() {
        AppEntry e = focusedEntry();
        if (e != null && listener != null) listener.onTogglePin(e);
    }

    private void announce() {
        AppEntry e = focusedEntry();
        announceForAccessibility(e != null ? e.label : nav.zone() == NavModel.TOP ? "Settings" : "");
    }
}
