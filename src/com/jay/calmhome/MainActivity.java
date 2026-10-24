package com.jay.calmhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Hosts the home screen. App discovery and tile art run on one background thread; the art cache
 * is only ever touched there, so no locks are needed.
 */
public final class MainActivity extends Activity implements HomeView.Listener {
    private static final String PREFS = "calm", KEY_PINS = "pins";

    private HomeView view;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler bg;
    private final Map<String, TileArt> artCache = new HashMap<>(); // loader thread only
    private int generation;
    private Toast toast;

    private List<AppEntry> lastApps = Collections.emptyList();
    private List<AppEntry> lastDock = Collections.emptyList();
    private Map<String, TileArt> lastArt = Collections.emptyMap();

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Uri data = intent.getData();
            final String pkg = data == null ? null : data.getSchemeSpecificPart();
            if (pkg != null) {
                bg.post(new Runnable() {
                    @Override
                    public void run() {
                        Iterator<String> it = artCache.keySet().iterator();
                        while (it.hasNext()) {
                            if (it.next().startsWith(pkg + "/")) it.remove();
                        }
                    }
                });
            }
            refresh(null);
        }
    };

    private final BroadcastReceiver clock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            view.onClockChanged();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        view = new HomeView(this);
        view.setListener(this);
        setContentView(view);
        view.requestFocus();
        worker = new HandlerThread("calm-loader", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        worker.start();
        bg = new Handler(worker.getLooper());
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clock, tf);
        view.onClockChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(null);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(clock);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        worker.quitSafely();
        super.onDestroy();
    }

    /** Re-reads installed apps on the loader thread, then swaps the tiles in on the UI thread. */
    private void refresh(final String restorePkg) {
        final int gen = ++generation;
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        bg.post(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> apps = AppRepo.query(pm, self);
                final Map<String, TileArt> snapshot = new HashMap<>();
                for (AppEntry e : apps) {
                    TileArt t = artCache.get(e.key());
                    if (t == null) {
                        try {
                            t = AppRepo.makeArt(pm, e);
                        } catch (RuntimeException | OutOfMemoryError ex) {
                            t = null; // the tile falls back to a plain labelled card
                        }
                        if (t != null) artCache.put(e.key(), t);
                    }
                    if (t != null) snapshot.put(e.key(), t);
                }
                artCache.keySet().retainAll(snapshot.keySet());
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || isDestroyed()) return;
                        lastApps = apps;
                        lastArt = snapshot;
                        apply(restorePkg);
                    }
                });
            }
        });
    }

    private void apply(String restorePkg) {
        AppOrder.Split split = AppOrder.split(lastApps, pins(), HomeView.MAX_DOCK);
        lastDock = split.dock;
        view.setData(split.dock, split.grid, lastArt, restorePkg);
    }

    private List<String> pins() {
        return AppOrder.parsePins(prefs.getString(KEY_PINS, AppOrder.DEFAULT_PINS));
    }

    @Override
    public void onLaunch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(e.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(e.pkg, e.activity))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (start(i)) return;
        PackageManager pm = getPackageManager();
        Intent alt = pm.getLeanbackLaunchIntentForPackage(e.pkg);
        if (alt == null) alt = pm.getLaunchIntentForPackage(e.pkg);
        if (alt != null && start(alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        say("Couldn't open " + e.label);
    }

    @Override
    public void onTogglePin(AppEntry e) {
        List<String> next = AppOrder.togglePin(pins(), lastDock, e.pkg, HomeView.MAX_DOCK);
        if (next == null) {
            say("Favourites are full. Unpin one first.");
            return;
        }
        prefs.edit().putString(KEY_PINS, AppOrder.joinPins(next)).apply();
        say(next.contains(e.pkg) ? e.label + " added to favourites" : e.label + " removed from favourites");
        apply(e.pkg);
    }

    @Override
    public void onSettings() {
        Intent s = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (s.resolveActivity(getPackageManager()) != null && start(s)) return;
        for (AppEntry e : lastApps) {
            if (e.pkg.contains(".settings")) {
                onLaunch(e);
                return;
            }
        }
        say("Settings isn't available");
    }

    private boolean start(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void say(String msg) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        toast.show();
    }
}
