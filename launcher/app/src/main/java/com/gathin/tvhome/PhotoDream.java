package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * Screensaver: the photo carousel, the active theme's photos first and then the screensaver topics. Interactive,
 * so Left and Right change the photo and Up shows its details; Back and any other key end it.
 */
public class PhotoDream extends DreamService {

    private PhotoCarouselView carousel;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        carousel = new PhotoCarouselView(this);
        setContentView(carousel);
        load();
    }

    private void load() {
        if (carousel != null) carousel.setItems(PhotoSets.saver(this, this::load), 0, PhotoSets.interval(this), true);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        carousel.start();
    }

    @Override
    public void onDreamingStopped() {
        carousel.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (carousel != null) carousel.release();
        carousel = null;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (carousel != null && carousel.handleKey(event)) return true;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK && carousel != null && carousel.infoShown()) {
            if (event.getAction() == KeyEvent.ACTION_UP) carousel.setInfo(false);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) finish();   // Back, OK or anything else wakes the TV
        return true;
    }
}
