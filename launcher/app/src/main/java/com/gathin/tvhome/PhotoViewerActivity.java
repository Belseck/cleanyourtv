package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The photo carousel full screen: the theme's background photos from "View background", or the screensaver's
 * from Back on Home. Back closes the details first, then the viewer; Home leaves it as anywhere else.
 */
public final class PhotoViewerActivity extends Activity {
    static final String EXTRA_SET = "com.gathin.tvhome.extra.PHOTO_SET";
    static final String EXTRA_FILES = "com.gathin.tvhome.extra.PHOTO_FILES";
    static final String EXTRA_START = "com.gathin.tvhome.extra.PHOTO_START";
    static final String EXTRA_INTERVAL = "com.gathin.tvhome.extra.PHOTO_INTERVAL";
    static final String EXTRA_SHUFFLE = "com.gathin.tvhome.extra.PHOTO_SHUFFLE";
    static final String EXTRA_SAVER = "com.gathin.tvhome.extra.PHOTO_SAVER";

    private PhotoCarouselView carousel;
    private boolean saver;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        carousel = new PhotoCarouselView(this);
        setContentView(carousel);
        saver = getIntent().getBooleanExtra(EXTRA_SAVER, false);
        load();
    }

    private void load() {
        if (saver) {
            carousel.setItems(PhotoSets.saver(this, this::reload), 0, PhotoSets.interval(this), true);
            return;
        }
        String set = getIntent().getStringExtra(EXTRA_SET);
        List<PhotoSets.Item> items = new ArrayList<>();
        if (set != null) {
            items = PhotoSets.items(this, set);
        } else {
            String[] files = getIntent().getStringArrayExtra(EXTRA_FILES);
            if (files != null) for (String f : files) items.add(new PhotoSets.Item(new File(f), null, ""));
        }
        carousel.setItems(items, getIntent().getIntExtra(EXTRA_START, 0), getIntent().getIntExtra(EXTRA_INTERVAL, 300),
                getIntent().getBooleanExtra(EXTRA_SHUFFLE, false));
    }

    /** New photos arrived while the viewer was open. */
    private void reload() {
        if (!isDestroyed()) load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        carousel.start();
    }

    @Override
    protected void onPause() {
        carousel.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        carousel.release();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (carousel.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (carousel.infoShown()) {
            carousel.setInfo(false);
            return;
        }
        finish();
    }
}
