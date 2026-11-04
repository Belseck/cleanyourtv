package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen photos that change on their own and with the remote: Left and Right go back and forward, Up shows
 * the photo's details (where, who, which camera), Down hides them. Shared by the background viewer, the Back-key
 * screensaver and the system screensaver. Credit for Unsplash photos stays in a corner while one is on screen.
 */
final class PhotoCarouselView extends FrameLayout {

    private final ImageView[] layers = new ImageView[2];
    private int front;
    private final TextView credit, loading;
    private final LinearLayout info;
    private final TextView infoTitle, infoBody;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    /** Network for the details, apart from decoding, so a slow answer never holds up the next photo. */
    private final ExecutorService details = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final boolean reducedMotion;
    private List<PhotoSets.Item> items = new ArrayList<>();
    private int index = -1, intervalSec = 60, generation;
    private boolean shuffle, running;
    private final Runnable tick = () -> show(nextIndex(1), true);

    PhotoCarouselView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        reducedMotion = Config.load(c).reducedMotion;
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            v.setAlpha(0f);
            layers[i] = v;
            addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        // A low shade so the credit and the details read on any photo.
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x00000000}));
        addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (220 * d), Gravity.BOTTOM));

        loading = text(c, 18, 0xCCFFFFFF);
        loading.setText(R.string.photos_loading);
        addView(loading, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        credit = text(c, 13, 0xB3FFFFFF);
        credit.setSingleLine(true);
        credit.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams cp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        cp.setMargins((int) (48 * d), 0, (int) (48 * d), (int) (32 * d));
        addView(credit, cp);

        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackgroundResource(R.drawable.qs_panel_bg);
        info.setPadding((int) (28 * d), (int) (22 * d), (int) (28 * d), (int) (24 * d));
        info.setVisibility(GONE);
        infoTitle = text(c, 20, 0xFFFFFFFF);
        infoTitle.setMaxLines(3);
        infoTitle.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(infoTitle);
        infoBody = text(c, 15, 0xD9FFFFFF);
        infoBody.setLineSpacing(6 * d, 1f);
        infoBody.setPadding(0, (int) (12 * d), 0, 0);
        info.addView(infoBody);
        LayoutParams ip = new LayoutParams((int) (560 * d), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        ip.setMargins((int) (48 * d), 0, 0, (int) (72 * d));
        addView(info, ip);
        for (View v : new View[]{shade, loading, credit, info}) {
            v.setOutlineProvider(null);   // raised above the photos without a shadow
            v.setTranslationZ(10f);
        }
    }

    private static TextView text(Context c, int sp, int color) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setShadowLayer(6, 0, 1, 0x99000000);
        return t;
    }

    /** The photos to cycle; keeps the one on screen when it is still in the new list. */
    void setItems(List<PhotoSets.Item> list, int start, int intervalSec, boolean shuffle) {
        File current = index >= 0 && index < items.size() ? items.get(index).file : null;
        items = new ArrayList<>(list);
        this.intervalSec = Math.max(5, intervalSec);
        this.shuffle = shuffle;
        loading.setVisibility(items.isEmpty() ? VISIBLE : GONE);
        if (items.isEmpty()) return;
        if (current != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).file.equals(current)) {
                    index = i;
                    schedule();
                    return;
                }
            }
        }
        show(Math.max(0, Math.min(start, items.size() - 1)), false);
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    void start() {
        running = true;
        schedule();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    void release() {
        stop();
        decoder.shutdownNow();
        details.shutdownNow();
    }

    private void schedule() {
        handler.removeCallbacks(tick);
        if (running && items.size() > 1) handler.postDelayed(tick, intervalSec * 1000L);
    }

    private int nextIndex(int step) {
        int n = items.size();
        if (n < 2) return Math.max(0, index);
        if (shuffle && step > 0) {
            int next = random.nextInt(n - 1);
            return next >= index ? next + 1 : next;   // never the same photo twice in a row
        }
        return ((index + step) % n + n) % n;
    }

    /** Arrows: Left/Right change the photo, Up shows its details, Down hides them. Other keys are not taken. */
    boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && items.size() > 1) show(nextIndex(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1), true);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && e.getRepeatCount() == 0 && !items.isEmpty()) setInfo(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) setInfo(false);
                return true;
            default:
                return false;
        }
    }

    /** Whether the details are up; Back closes them first. */
    boolean infoShown() {
        return info.getVisibility() == VISIBLE;
    }

    void setInfo(boolean on) {
        info.setVisibility(on ? VISIBLE : GONE);
        if (on) fillInfo();
    }

    private void show(int chosen, boolean animate) {
        if (items.isEmpty()) return;
        int ticket = ++generation;
        PhotoSets.Item item = items.get(chosen);
        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        decoder.execute(() -> {
            Bitmap b = decode(item.file, w, h);
            handler.post(() -> {
                if (ticket != generation) return;   // a later press won
                index = chosen;
                if (b != null) {
                    ImageView next = layers[1 - front], old = layers[front];
                    next.setImageBitmap(b);
                    next.setTranslationZ(1f);   // over the old one; the overlays sit at 10
                    old.setTranslationZ(0f);
                    next.setAlpha(animate && !reducedMotion ? 0f : 1f);
                    next.animate().alpha(1f).setDuration(animate ? (reducedMotion ? 150 : 900) : 0)
                            .withEndAction(() -> old.setAlpha(0f)).start();
                    front = 1 - front;
                }
                credit.setText(creditText(item));
                if (infoShown()) fillInfo();
                fetchDetails(item, chosen);
                schedule();
            });
        });
    }

    /** "Photo: who · Unsplash · where   ▲ Details": the place joins once Unsplash has said it. */
    private String creditText(PhotoSets.Item item) {
        String hint = "▲ " + getContext().getString(R.string.photo_info);
        if (item.photo == null) return hint;
        String where = item.photo.location.isEmpty() ? "" : " · " + item.photo.location;
        return getContext().getString(R.string.photo_credit, item.photo.photographer) + where + "    " + hint;
    }

    /** Asks Unsplash for the place and camera of the photo now on screen, once per photo, off the main thread. */
    private void fetchDetails(PhotoSets.Item item, int at) {
        OnlinePhotos.Photo p = item.photo;
        if (p == null || p.detailed) return;
        details.execute(() -> {
            OnlinePhotos.details(getContext(), item.set, p);
            handler.post(() -> {
                if (!p.detailed || at != index) return;
                credit.setText(creditText(item));
                if (infoShown()) fillInfo();
            });
        });
    }

    /** Details of the photo on screen; location and camera are fetched the first time, off the main thread. */
    private void fillInfo() {
        if (index < 0 || index >= items.size()) return;
        PhotoSets.Item item = items.get(index);
        Context c = getContext();
        OnlinePhotos.Photo p = item.photo;
        List<String> lines = new ArrayList<>();
        if (p == null) {
            infoTitle.setText(item.file.getName());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(item.file.getPath(), o);
            if (o.outWidth > 0) lines.add(c.getString(R.string.photo_size) + ": " + o.outWidth + " × " + o.outHeight);
        } else {
            // Every photo is shown; what the photographer left out is said, not hidden.
            infoTitle.setText(p.description.isEmpty() ? c.getString(R.string.photo_no_description) : p.description);
            String missing = p.detailed ? c.getString(R.string.photo_not_given) : "…";
            lines.add(c.getString(R.string.photo_location) + ": " + (p.location.isEmpty() ? missing : p.location));
            lines.add(c.getString(R.string.photo_by) + ": " + p.photographer + " · Unsplash");
            lines.add(c.getString(R.string.photo_camera) + ": " + (p.camera.isEmpty() ? missing : p.camera));
            if (!p.taken.isEmpty()) lines.add(c.getString(R.string.photo_taken) + ": " + p.taken);
            if (p.width > 0) lines.add(c.getString(R.string.photo_size) + ": " + p.width + " × " + p.height);
            if (!p.detailed) {
                final int at = index;
                details.execute(() -> {
                    OnlinePhotos.details(c, item.set, p);
                    handler.post(() -> {
                        if (p.detailed && at == index && infoShown()) fillInfo();
                    });
                });
            }
        }
        infoBody.setText(TextUtils.join("\n", lines));
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }
}
