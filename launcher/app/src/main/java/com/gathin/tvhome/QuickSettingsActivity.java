package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The quick panel: a full-height sheet at the screen's edge with the time, the date and a grid of tiles, like
 * Android TV's dashboard. The remote's Settings button opens it when {@code settings_key_panel} is on, and
 * holding Home opens it where the system has no panel of its own (HomeKeyService). Translucent and in a task
 * of its own, so the app behind keeps running and Back returns to it. The same key again closes it.
 */
public final class QuickSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    private TextView sleepStatus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        boolean start = getResources().getBoolean(R.bool.rtl_menu_bar);
        View panel = findViewById(R.id.panel);
        if (start) {
            android.widget.FrameLayout.LayoutParams position =
                    (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = android.view.Gravity.START;
            panel.setLayoutParams(position);
        }
        View scrim = findViewById(R.id.scrim);
        // Darkens only the panel's side, so the picture behind stays visible.
        scrim.setBackground(new GradientDrawable(start ? GradientDrawable.Orientation.LEFT_RIGHT
                : GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0xB3000000, 0x66000000, 0x00000000}));
        scrim.setOnClickListener(v -> finish());

        Date now = new Date();
        ((TextView) findViewById(R.id.time)).setText(android.text.format.DateFormat.getTimeFormat(this).format(now));
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        ((TextView) findViewById(R.id.date)).setText(new SimpleDateFormat(
                android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now));

        LinearLayout rows = findViewById(R.id.rows);
        addTile(rows, R.drawable.ic_settings, getString(R.string.tv_settings), null,
                SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        addTile(rows, R.drawable.ic_wifi, getString(R.string.quick_network), getString(online()
                ? R.string.connected : R.string.offline), SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addTile(rows, R.drawable.ic_picture, getString(R.string.quick_picture), null,
                SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS));
        if (HomeKeyService.canSleep()) {
            View sleep = addTile(rows, R.drawable.ic_sleep, getString(R.string.sleep_timer), "", null);
            sleepStatus = sleep.findViewById(R.id.status);
            showSleep(sleep);
            sleep.setOnClickListener(v -> {
                HomeKeyService.setSleepTimer(nextSleepMinutes());
                showSleep(v);
            });
        }
        addTile(rows, R.drawable.ic_apps, getString(R.string.quick_apps), getString(R.string.quick_apps_hint),
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addTile(rows, R.drawable.ic_home_settings, getString(R.string.launcher_settings), null,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        ((ViewGroup) rows.getChildAt(0)).getChildAt(0).requestFocus();

        if (!Config.load(this).reducedMotion) {
            float shift = 48 * getResources().getDisplayMetrics().density;
            panel.setTranslationX(start ? -shift : shift);
            panel.setAlpha(0f);
            panel.animate().translationX(0).alpha(1f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(2.2f)).start();
        }
    }

    /** Settings (or a held Home) pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    @SuppressWarnings("deprecation")
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean online() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null;
    }

    private static int sleepMinutesLeft() {
        long end = HomeKeyService.sleepTimerEnd(), left = end - System.currentTimeMillis();
        return end > 0 && left > 0 ? (int) ((left + 59_999) / 60_000) : 0;
    }

    /** The next length after the time left, in the pill menu's order; after the longest, off. */
    static int nextSleepMinutes(int current) {
        for (int minutes : HomeKeyService.SLEEP_MINUTES) if (minutes > current) return minutes;
        return 0;
    }

    private static int nextSleepMinutes() {
        return nextSleepMinutes(sleepMinutesLeft());
    }

    private void showSleep(View tile) {
        int left = sleepMinutesLeft();
        String state = left > 0 ? getString(R.string.sleep_minutes, left) : getString(R.string.off);
        sleepStatus.setText(state);
        sleepStatus.setVisibility(View.VISIBLE);
        tile.setContentDescription(getString(R.string.sleep_timer) + ", " + state);
    }

    /** Adds a tile, two per row; a null target leaves the click to the caller. */
    private View addTile(LinearLayout rows, int icon, String title, String status, Intent target) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout row = rows.getChildCount() == 0 ? null : (LinearLayout) rows.getChildAt(rows.getChildCount() - 1);
        if (row == null || row.getChildCount() == 2) {
            row = new LinearLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (rows.getChildCount() > 0) lp.topMargin = (int) (10 * density);
            rows.addView(row, lp);
        }
        View tile = getLayoutInflater().inflate(R.layout.item_quick_tile, row, false);
        if (row.getChildCount() == 1) {
            ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginStart((int) (10 * density));
        }
        ((ImageView) tile.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
        TextView hint = tile.findViewById(R.id.status);
        if (status != null && !status.isEmpty()) {
            hint.setText(status);
            hint.setVisibility(View.VISIBLE);
        }
        tile.setContentDescription(status == null || status.isEmpty() ? title : title + ", " + status);
        if (target != null) {
            tile.setOnClickListener(v -> {
                try {
                    startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
                finish();
            });
        }
        row.addView(tile);
        return tile;
    }
}
