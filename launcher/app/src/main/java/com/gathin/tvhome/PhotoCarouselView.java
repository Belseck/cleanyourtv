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
 * screensaver and the system screensaver. The photo's credit (author, source, licence, place) stays in a corner.
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
    private final Runnable tick = () -> step(1);
    private final PhotoHistory history = new PhotoHistory();

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
        int first = Math.max(0, Math.min(start, items.size() - 1));
        history.push(items.get(first).file.getPath());
        show(first, false);
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

    /**
     * Left goes back through the photos already shown, in the order they were shown. Right, and the timer, go
     * forward through them again, then on to a new photo: any other at random when shuffled, else the next in
     * the list. Nothing earlier than the first photo: Left stays.
     */
    private void step(int dir) {
        if (items.size() < 2) return;
        String key = dir < 0 ? history.back(this::listed) : history.forward(this::listed);
        if (key != null) {
            show(indexOf(key), true);
            return;
        }
        if (dir < 0) return;
        int n = items.size(), now = Math.max(0, index), next;   // index is -1 until the first photo is up
        if (shuffle) {
            next = random.nextInt(n - 1);
            if (next >= now) next++;   // never the same photo twice in a row
        } else {
            next = (now + 1) % n;
        }
        history.push(items.get(next).file.getPath());
        show(next, true);
    }

    private boolean listed(String path) {
        return indexOf(path) >= 0;
    }

    private int indexOf(String path) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).file.getPath().equals(path)) return i;
        return -1;
    }

    /** Arrows: Left/Right change the photo, Up shows its details, Down hides them. Other keys are not taken. */
    boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) step(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
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

    /** "Photo: who · source · licence · where   ▲ Details": the place joins once it is known. */
    private String creditText(PhotoSets.Item item) {
        String hint = "▲ " + getContext().getString(R.string.photo_info);
        if (item.photo == null) return hint;
        String where = item.photo.location.isEmpty() ? "" : " · " + item.photo.location;
        return getContext().getString(R.string.photo_credit, item.photo.photographer) + source(item.photo) + where
                + "    " + hint;
    }

    /** " · Unsplash" or " · Wikimedia Commons · CC BY-SA 4.0": the licence, when the source names one. */
    private static String source(OnlinePhotos.Photo p) {
        return " \u00B7 " + p.source + (p.license.isEmpty() ? "" : " \u00B7 " + p.license);
    }

    /** Asks for the place (and with Unsplash the camera) of the photo now on screen, once per photo, off the main thread. */
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
            lines.add(c.getString(R.string.photo_by) + ": " + p.photographer + source(p));
            lines.add(c.getString(R.string.photo_camera) + ": " + (p.camera.isEmpty() ? missing : p.camera));
            // Commons gives the day it was taken; Unsplash only the day it was published.
            int dateLabel = OnlinePhotos.COMMONS_NAME.equals(p.source) ? R.string.photo_shot : R.string.photo_taken;
            if (!p.taken.isEmpty()) lines.add(c.getString(dateLabel) + ": " + p.taken);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(item.file.getPath(), o);
            String shown = o.outWidth > 0 ? o.outWidth + " × " + o.outHeight : "";
            String original = p.width > 0 ? p.width + " × " + p.height : "";
            // The file on the TV is screen-sized; the original is what the photographer uploaded.
            if (!shown.isEmpty()) lines.add(c.getString(R.string.photo_size) + ": " + shown
                    + (original.isEmpty() || original.equals(shown) ? "" : " (" + c.getString(R.string.photo_original, original) + ")"));
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
