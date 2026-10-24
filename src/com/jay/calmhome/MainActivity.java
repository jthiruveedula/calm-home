package com.jay.calmhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Hosts the UI. App discovery and icon rendering run on one background thread; the icon cache is
 * only ever touched there, so no locks are needed.
 */
public final class MainActivity extends Activity implements CalmView.Host {
    private CalmView view;
    private com.jay.calmhome.Settings settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler bg;
    private final Map<String, TileArt> artCache = new HashMap<>(); // loader thread only
    private int generation;
    private Toast toast;
    private List<AppEntry> lastApps = java.util.Collections.emptyList();

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
            refresh();
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
        settings = new com.jay.calmhome.Settings(this);
        view = new CalmView(this);
        view.init(settings, this);
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
        view.wake();
        refresh();
    }

    /** Moves a pin from an uninstalled app to its replacement (YouTube to SmartTube). */
    private void migratePins(List<AppEntry> apps) {
        java.util.Set<String> installed = new HashSet<>();
        for (AppEntry e : apps) installed.add(e.pkg);
        List<String> pins = AppOrder.parsePins(settings.pins());
        List<String> next = AppOrder.migratePins(pins, installed);
        if (!next.equals(pins)) settings.pins(AppOrder.joinPins(next));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        view.showHome();
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

    /** Re-reads installed apps on the loader thread, then swaps them in on the UI thread. */
    private void refresh() {
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
                            t = null; // the tile falls back to a plain placeholder
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
                        migratePins(apps);
                        view.setData(apps, snapshot);
                    }
                });
            }
        });
    }

    @Override
    public void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(e.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(e.pkg, e.activity))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        boolean ok = start(i);
        if (!ok) {
            PackageManager pm = getPackageManager();
            Intent alt = pm.getLeanbackLaunchIntentForPackage(e.pkg);
            if (alt == null) alt = pm.getLaunchIntentForPackage(e.pkg);
            ok = alt != null && start(alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        if (ok) {
            settings.log(LaunchLog.record(settings.log(), e.pkg));
            settings.times(LaunchLog.recordTime(settings.times(), e.pkg, System.currentTimeMillis()));
        }
        else say("Couldn't open " + e.label);
    }

    @Override
    public void togglePin(AppEntry e) {
        List<String> pins = AppOrder.parsePins(settings.pins());
        List<String> next = AppOrder.togglePin(pins, view.dockApps(), e.pkg, CalmView.MAX_DOCK);
        if (next == null) {
            say("Favorites are full. Unpin one first.");
            return;
        }
        settings.pins(AppOrder.joinPins(next));
        say(next.contains(e.pkg) ? e.label + " added to Favorites" : e.label + " removed from Favorites");
        view.pinsChanged(e.pkg);
    }

    @Override
    public void openSettings() {
        Intent s = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (s.resolveActivity(getPackageManager()) != null && start(s)) return;
        for (AppEntry e : lastApps) {
            if (e.pkg.contains(".settings")) {
                launch(e);
                return;
            }
        }
        say("Settings isn't available");
    }

    @Override
    public void openSettings(String action) {
        Intent s = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (s.resolveActivity(getPackageManager()) != null && start(s)) return;
        openSettings();
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
