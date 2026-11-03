package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * A transparent full-screen overlay that plays one short burst of seasonal particles when Home comes up: snow,
 * sakura petals, autumn leaves, hearts, confetti, or sparks that rise instead of fall. Same contract as
 * {@link PetalShowerView}: no focus, clicks or accessibility, nothing allocated per frame, and the callback runs
 * once the burst is over so the caller can remove the view.
 */
final class SeasonShowerView extends View {

    static final int SNOW = 0, SAKURA = 1, LEAF = 2, HEART = 3, CONFETTI = 4, SPARK = 5;
    // Internal kind for the caller's sprite; not a palette index.
    private static final int IMAGE = 6;
    private static final double TAU = 2 * Math.PI;

    private static final int[][] PALETTES = {
            {0xFFFFFFFF, 0xFFF2F8FF, 0xFFDCEBFF},
            {0xFFFFC6D9, 0xFFFFB0CB, 0xFFFFDDE8, 0xFFFF9FBF},
            {0xFFD2691E, 0xFFE08A1E, 0xFFB23A1B, 0xFFE6B422, 0xFF8C4A1A},
            {0xFFE0123F, 0xFFFF4D6D, 0xFFFF8FA3, 0xFFC9184A},
            {0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF9F0A, 0xFFFF2D92},
            {0xFFFFF3C4, 0xFFFFE08A, 0xFFFFFFFF, 0xFFFFD27A}};

    private final float density;
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vein = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path sakura = sakuraPath(), leaf = leafPath(), heart = heartPath(), spark = sparkPath();

    private final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    // Owned by the caller: never recycled here, and dropped in finish() so it can be collected.
    /** The caller's pictures; each particle draws one, picked at random (its index is kept in color[]). */
    private Bitmap[] sprites;

    private int kind, count;
    private float[] xFrac = new float[0], delay = new float[0], life = new float[0], size = new float[0],
            swayAmp = new float[0], swayFreq = new float[0], swayPhase = new float[0], rot0 = new float[0],
            spin = new float[0], depth = new float[0], flipFreq = new float[0], flipPhase = new float[0];
    private int[] color = new int[0], back = new int[0];

    private boolean playing;
    private long start, duration;
    private Runnable onDone;
    private final Runnable finish = this::finish;

    SeasonShowerView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        vein.setStyle(Paint.Style.STROKE);
        vein.setStrokeWidth(0.035f);
        vein.setStrokeCap(Paint.Cap.ROUND);
        vein.setColor(0xFF000000);
    }

    boolean isPlaying() {
        return playing;
    }

    /** Default palette for a kind, when the theme gives no colours. Shared: callers must not modify it. */
    static int[] palette(int kind) {
        return PALETTES[kind];
    }

    /** Suggested particle count for a kind. */
    static int count(int kind) {
        switch (kind) {
            case SNOW: return 100;
            case SAKURA: return 45;
            case LEAF: return 36;
            case HEART: return 34;
            case CONFETTI: return 70;
            default: return 36;
        }
    }

    /** Suggested burst length in milliseconds. */
    static long durationMs(int kind) {
        return kind == SNOW ? 7000 : kind == HEART || kind == LEAF ? 6500 : 6000;
    }

    /** Maps a theme name to a kind here, or -1 when another view plays it (bats, marigold, image, none). */
    static int kindOf(String name) {
        if (name == null) return -1;
        switch (name) {
            case "snow": return SNOW;
            case "sakura": return SAKURA;
            case "leaf": return LEAF;
            case "heart": return HEART;
            case "confetti": return CONFETTI;
            case "spark": return SPARK;
            default: return -1;
        }
    }

    /** Like {@link #play} but every particle is {@code sprite} (caller-owned, at most 128 px). */
    void playImage(Bitmap sprite, int n, long durationMs, Runnable done) {
        playImages(new Bitmap[]{sprite}, n, durationMs, done);
    }

    /** Like {@link #play} but each particle is one of {@code pictures}, at random (caller-owned, never recycled). */
    void playImages(Bitmap[] pictures, int n, long durationMs, Runnable done) {
        if (playing) {
            removeCallbacks(finish);
            finish();
        }
        java.util.List<Bitmap> usable = new java.util.ArrayList<>();
        if (pictures != null) for (Bitmap b : pictures) if (b != null && !b.isRecycled()) usable.add(b);
        if (usable.isEmpty()) {
            if (done != null) done.run();
            return;
        }
        this.sprites = usable.toArray(new Bitmap[0]);
        kind = IMAGE;
        n = Math.max(0, Math.min(120, n));
        ensureCapacity(n);
        count = n;
        duration = Math.max(1, durationMs);
        onDone = done;
        for (int i = 0; i < n; i++) {
            depth[i] = rnd.nextFloat();
            xFrac[i] = (i + rnd.nextFloat()) / n;
            delay[i] = rnd.nextFloat() * 0.4f;
            // Small (far) sprites fall slower, so they get the longer life.
            life[i] = (1f - delay[i]) * (1f - 0.35f * depth[i]);
            size[i] = 0.5f + 0.5f * depth[i];
            color[i] = rnd.nextInt(sprites.length);
            swayAmp[i] = 10f + rnd.nextFloat() * 18f;
            swayFreq[i] = 0.25f + rnd.nextFloat() * 0.4f;
            swayPhase[i] = rnd.nextFloat() * (float) TAU;
        }
        launch();
    }

    private void launch() {
        playing = true;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        start = SystemClock.uptimeMillis();
        postDelayed(finish, duration);
        postInvalidateOnAnimation();
    }

    /** Plays {@code n} particles of {@code kind} in {@code palette} for {@code durationMs}, then runs {@code done}. */
    void play(int kind, int[] palette, int n, long durationMs, Runnable done) {
        if (playing) {
            removeCallbacks(finish);
            finish();
        }
        this.kind = kind;
        n = Math.max(0, n);
        ensureCapacity(n);
        count = n;
        duration = Math.max(1, durationMs);
        onDone = done;
        float minSize = kind == SNOW ? 2f : kind == SPARK ? 6f : kind == HEART ? 10f : 9f;
        float sizeRange = kind == SNOW ? 4.5f : kind == SPARK ? 6f : kind == HEART ? 10f : 6f;
        for (int i = 0; i < n; i++) {
            float d = rnd.nextFloat();
            // Mostly far flakes and a sparse foreground.
            depth[i] = kind == SNOW ? d * d : d;
            xFrac[i] = (i + rnd.nextFloat()) / n;
            delay[i] = rnd.nextFloat() * 0.4f;
            float room = 1f - delay[i];
            // Near snow crosses the screen sooner (faster); the rest pick any life that still ends in time.
            life[i] = kind == SNOW ? room * (1f - 0.45f * depth[i]) : 0.55f + rnd.nextFloat() * (room - 0.55f);
            size[i] = minSize + (kind == SNOW ? depth[i] : rnd.nextFloat()) * sizeRange;
            swayAmp[i] = (kind == CONFETTI ? 8f : kind == SNOW ? 5f : 14f)
                    + rnd.nextFloat() * (kind == SNOW ? 10f : 22f);
            swayFreq[i] = 0.25f + rnd.nextFloat() * 0.5f;
            swayPhase[i] = rnd.nextFloat() * (float) TAU;
            rot0[i] = kind == LEAF ? (rnd.nextFloat() - 0.5f) * 60f : rnd.nextFloat() * 360f;
            spin[i] = (60f + rnd.nextFloat() * 140f) * (rnd.nextBoolean() ? 1f : -1f);
            flipFreq[i] = (kind == LEAF ? 0.25f : kind == SPARK ? 0.9f : 0.4f)
                    + rnd.nextFloat() * (kind == SPARK ? 0.8f : 0.9f);
            flipPhase[i] = rnd.nextFloat() * (float) TAU;
            color[i] = palette[rnd.nextInt(palette.length)];
            back[i] = dim(color[i], 0.7f);
        }
        launch();
    }

    private void finish() {
        if (!playing) return;
        playing = false;
        sprites = null;
        setLayerType(LAYER_TYPE_NONE, null);
        invalidate();
        Runnable done = onDone;
        onDone = null;
        if (done != null) done.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (playing) {
            removeCallbacks(finish);
            finish();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!playing) return;
        long now = SystemClock.uptimeMillis();
        float elapsed = (now - start) / (float) duration, secs = (now - start) / 1000f;
        float w = getWidth(), h = getHeight();
        for (int i = 0; i < count; i++) {
            float p = (elapsed - delay[i]) / life[i];
            if (p <= 0f || p >= 1f) continue;
            float len = size[i] * density, z = depth[i];
            float fade = Math.min(Math.min(1f, p / 0.12f), Math.min(1f, (1f - p) / (kind == SNOW ? 0.15f : 0.25f)));
            fade = fade * fade * (3f - 2f * fade);
            double sway = TAU * swayFreq[i] * secs + swayPhase[i];
            float swayS = (float) Math.sin(sway);
            float x = xFrac[i] * w + swayAmp[i] * density * swayS;
            float y;
            if (kind == IMAGE) {
                Bitmap sprite = sprites[color[i]];
                float sw = sprite.getWidth(), sh = sprite.getHeight();
                // 14-32 dp on screen whatever the picture's own resolution; size[] carries the depth.
                float s = (14f + 18f * (size[i] - 0.5f) * 2f) * density / Math.max(sw, sh);
                y = -sh * s + (h + sh * s * 2f) * p;
                matrix.setTranslate(-sw * 0.5f, -sh * 0.5f);
                matrix.postScale(s, s);
                matrix.postRotate(25f * (float) Math.cos(sway));
                matrix.postTranslate(x, y);
                spritePaint.setAlpha((int) (fade * (0.45f + 0.55f * z) * 255f));
                c.drawBitmap(sprite, matrix, spritePaint);
                continue;
            }
            if (kind == SNOW) {
                // Parallax: near flakes fall faster (shorter life), swing wider and drift further with the breeze.
                x += density * secs * (3f + 9f * z) + density * 8f * z * swayS - w * 0.05f;
                y = -len * 2f + (h + len * 4f) * p;
                paint.setColor(color[i]);
                float a = fade * (0.35f + 0.65f * z);
                if (z > 0.3f) {
                    // Soft edge on the big flakes: a faint wide disc under a brighter core.
                    paint.setAlpha((int) (a * 80f));
                    c.drawCircle(x, y, len * 0.5f, paint);
                    paint.setAlpha((int) (a * 235f));
                    c.drawCircle(x, y, len * 0.3f, paint);
                } else {
                    paint.setAlpha((int) (a * 200f));
                    c.drawCircle(x, y, len * 0.4f, paint);
                }
                continue;
            }
            float flip = (float) Math.cos(TAU * flipFreq[i] * secs + flipPhase[i]);
            float sx = Math.abs(flip) < 0.08f ? (flip < 0f ? -0.08f : 0.08f) : flip;
            c.save();
            if (kind == SPARK) {
                // Embers decelerate as they climb and twinkle out of phase with each other.
                y = h * (0.97f - 0.8f * p * (2f - p));
                float tw = 0.5f + 0.5f * (float) Math.sin(TAU * flipFreq[i] * 4f * secs + flipPhase[i]);
                tw *= tw;
                paint.setColor(color[i]);
                c.translate(x, y);
                paint.setAlpha((int) (fade * (0.1f + 0.2f * tw) * 255f));
                c.drawCircle(0, 0, len * 0.7f, paint);
                paint.setAlpha((int) (fade * (0.3f + 0.7f * tw) * 255f));
                c.rotate(rot0[i] + spin[i] * 0.2f * secs);
                float s = len * (0.55f + 0.45f * tw);
                c.scale(s, s);
                c.drawPath(spark, paint);
            } else if (kind == HEART) {
                // Hearts rise like released balloons, rocking and breathing slightly, each with a faint halo.
                y = h + len - (h + len * 2f) * p;
                float s = len * (1f + 0.05f * (float) Math.sin(secs * 3f + swayPhase[i]));
                paint.setColor(color[i]);
                c.translate(x, y);
                c.rotate(10f * swayS);
                c.scale(s * 1.4f, s * 1.4f);
                paint.setAlpha((int) (fade * 40f));
                c.drawPath(heart, paint);
                c.scale(1f / 1.4f, 1f / 1.4f);
                paint.setAlpha((int) (fade * 255f));
                c.drawPath(heart, paint);
            } else {
                float travel = (p + p * p) * 0.5f;
                y = -len * 2f + (h + len * 4f) * travel;
                paint.setColor(flip < 0f ? back[i] : color[i]);
                paint.setAlpha((int) (fade * 255f));
                c.translate(x, y);
                if (kind == CONFETTI) {
                    c.rotate(rot0[i] + spin[i] * secs);
                    // The strip flips over about its length, so its width follows the flip and the back is darker.
                    float hw = len * 0.5f * Math.abs(sx);
                    c.drawRect(-hw, -len * 0.25f, hw, len * 0.25f, paint);
                } else if (kind == LEAF) {
                    // A pendulum: the leaf points into its sideways swing and turns over slowly as it goes.
                    c.rotate(rot0[i] + 42f * (float) Math.cos(sway));
                    c.scale(len * sx, len);
                    c.drawPath(leaf, paint);
                    vein.setAlpha((int) (fade * 70f));
                    c.drawLine(0f, -0.4f, 0f, 0.4f, vein);
                } else {
                    c.rotate(rot0[i] + spin[i] * secs + 22f * (float) Math.cos(sway));
                    c.scale(len * sx, len);
                    c.drawPath(sakura, paint);
                }
            }
            c.restore();
        }
        postInvalidateOnAnimation();
    }

    private static int dim(int c, float f) {
        return 0xFF000000 | ((int) (((c >> 16) & 0xFF) * f) << 16) | ((int) (((c >> 8) & 0xFF) * f) << 8)
                | (int) ((c & 0xFF) * f);
    }

    private void ensureCapacity(int n) {
        if (xFrac.length >= n) return;
        xFrac = new float[n];
        delay = new float[n];
        life = new float[n];
        size = new float[n];
        swayAmp = new float[n];
        swayFreq = new float[n];
        swayPhase = new float[n];
        rot0 = new float[n];
        spin = new float[n];
        depth = new float[n];
        flipFreq = new float[n];
        flipPhase = new float[n];
        color = new int[n];
        back = new int[n];
    }

    /** A cherry-blossom petal, 1 unit tall: a rounded oval with the notch at its tip. */
    static Path sakuraPath() {
        Path p = new Path();
        p.moveTo(0f, 0.5f);
        p.cubicTo(-0.42f, 0.25f, -0.38f, -0.4f, -0.1f, -0.5f);
        p.lineTo(0f, -0.36f);
        p.lineTo(0.1f, -0.5f);
        p.cubicTo(0.38f, -0.4f, 0.42f, 0.25f, 0f, 0.5f);
        p.close();
        return p;
    }

    /** An autumn leaf, 1 unit tall: pointed at both ends with a short stem. */
    static Path leafPath() {
        Path p = new Path();
        p.moveTo(0f, -0.5f);
        p.cubicTo(0.38f, -0.25f, 0.34f, 0.2f, 0f, 0.4f);
        p.cubicTo(-0.34f, 0.2f, -0.38f, -0.25f, 0f, -0.5f);
        p.close();
        p.addRect(-0.025f, 0.38f, 0.025f, 0.55f, Path.Direction.CW);
        return p;
    }

    /** A heart, 1 unit wide. */
    static Path heartPath() {
        Path p = new Path();
        p.moveTo(0f, 0.42f);
        p.cubicTo(-0.55f, 0.05f, -0.45f, -0.5f, 0f, -0.2f);
        p.cubicTo(0.45f, -0.5f, 0.55f, 0.05f, 0f, 0.42f);
        p.close();
        return p;
    }

    /** A four-pointed spark, 1 unit across. */
    static Path sparkPath() {
        Path p = new Path();
        p.moveTo(0f, -0.5f);
        p.quadTo(0.06f, -0.06f, 0.5f, 0f);
        p.quadTo(0.06f, 0.06f, 0f, 0.5f);
        p.quadTo(-0.06f, 0.06f, -0.5f, 0f);
        p.quadTo(-0.06f, -0.06f, 0f, -0.5f);
        p.close();
        return p;
    }
}
