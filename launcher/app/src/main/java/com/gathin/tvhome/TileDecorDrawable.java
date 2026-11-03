package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

/**
 * A static ornament drawn over a tile as its foreground, picked by name from a user-editable pack: snow_cap, frost,
 * blossom, sun, leaves, heart, hearts, sparkles, lantern, flag_band, cobweb, marigold, candle, or a caller bitmap
 * (image). Unknown names draw nothing. Every shape and shader is built once per size in onBoundsChange, so draw() only issues
 * a few fills and never allocates; each motif carries a soft shadow or rim so it reads on white and black banners.
 */
final class TileDecorDrawable extends Drawable {

    private static final Path LEAF = SeasonShowerView.leafPath(), HEART = SeasonShowerView.heartPath(),
            SPARK = SeasonShowerView.sparkPath();
    private static final float[] COS5 = new float[5], SIN5 = new float[5];
    private static final float[] COS12 = new float[12], SIN12 = new float[12];

    static {
        for (int k = 0; k < 5; k++) {
            COS5[k] = (float) Math.cos(Math.toRadians(-90 + 72 * k));
            SIN5[k] = (float) Math.sin(Math.toRadians(-90 + 72 * k));
        }
        for (int k = 0; k < 12; k++) {
            COS12[k] = (float) Math.cos(Math.toRadians(30 * k));
            SIN12[k] = (float) Math.sin(Math.toRadians(30 * k));
        }
    }


    private final String ornament;
    private final int[] colors;
    private final Bitmap image;
    private final boolean imageLeft;
    /** corner "top": the picture spans the tile's top edge (snow caps, frost, flag bands). */
    private final boolean imageTop;
    private final Rect imageDst = new Rect();
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final long seed;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Per-theme geometry. Snow: p1 cap. Branch: p1. Web: p1. Lantern: p1 body, p2 ribs, p3 caps, p4 cord+tassel,
    // p5 gloss. Candle: p1 stick, p2 shaded side, p3 wax, p4 outer flame, p5 inner flame.
    private final Path p1 = new Path(), p2 = new Path(), p3 = new Path(), p4 = new Path(), p5 = new Path();
    private final float[] drips = new float[9];   // x, y, radius of up to three snow drips
    private int dripCount;
    private Shader glint;

    /**
     * {@code colors} (ARGB, may be null/empty) override the main fills, see each look; {@code image} is only used by
     * "image" and is never recycled here; {@code corner} is "top-left" or "top-right" (default); {@code seed} varies
     * the snow's outline per tile.
     */
    TileDecorDrawable(String ornament, int[] colors, Bitmap image, String corner, long seed) {
        this.ornament = ornament == null ? "" : ornament;
        this.colors = colors == null ? new int[0] : colors;
        this.image = image;
        this.imageLeft = "top-left".equals(corner);
        this.imageTop = "top".equals(corner);
        this.seed = seed;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    private int col(int i, int def) {
        return i < colors.length ? colors[i] : def;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        p1.reset();
        p2.reset();
        p3.reset();
        p4.reset();
        p5.reset();
        glint = null;
        dripCount = 0;
        float w = b.width(), h = b.height(), s = h * 0.544f;   // s: size of a corner motif
        RectF r = new RectF();
        switch (ornament) {
            case "snow_cap":
                buildSnow(w, h, h * 0.16f, true, new Random(seed));
                break;
            case "frost":
                buildSnow(w, h, h * 0.1f, false, new Random(seed));
                break;
            case "sun":
                glint = new RadialGradient(w, 0, h * 0.9f, 0x80FFC24A, 0x00FFC24A, Shader.TileMode.CLAMP);
                break;
            case "blossom":
                p1.moveTo(0, s * 0.2f);
                p1.cubicTo(s * 0.4f, s * 0.15f, s * 0.8f, s * 0.6f, s * 1.9f, s * 0.35f);
                break;
            case "cobweb":
                buildWeb(h * 0.45f);
                break;
            case "lantern":
                buildLantern(r, w - h * 0.26f, h * 0.363f);
                break;
            case "candle":
                glint = new RadialGradient(w - h * 0.17f, h * 0.122f, h * 0.224f, 0x77FFB347, 0x00FFB347,
                        Shader.TileMode.CLAMP);
                buildCandle(r, w - h * 0.17f, h * 0.408f);
                break;
            case "image":
                buildImage(w, h);
                break;
            default:
                break;
        }
    }

    /** Fits the bitmap into a corner box of 45% of the tile height and 35% of its width, keeping its aspect ratio. */
    private void buildImage(float w, float h) {
        imageDst.setEmpty();
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return;
        if (imageTop) {
            // Full width, height from the picture's own aspect, capped so a tall picture cannot cover the logo.
            int dh = Math.min(Math.round(w * image.getHeight() / image.getWidth()), Math.round(h * 0.4f));
            imageDst.set(0, 0, Math.round(w), Math.max(1, dh));
            return;
        }
        float k = Math.min(h * 0.45f / image.getHeight(), w * 0.35f / image.getWidth());
        int dw = Math.max(1, Math.round(image.getWidth() * k)), dh = Math.max(1, Math.round(image.getHeight() * k));
        int inset = Math.round(h * 0.04f);
        int left = imageLeft ? inset : Math.round(w) - inset - dw;
        imageDst.set(left, inset, left + dw, inset + dh);
    }

    /** A wavy snow pile along the top edge, depth {@code d}, with a few round drips hanging from it when deep. */
    private void buildSnow(float w, float h, float d, boolean withDrips, Random r) {
        Path p = p1;
        p.moveTo(0, 0);
        p.lineTo(w, 0);
        p.lineTo(w, d * 0.8f);
        int bumps = 5 + r.nextInt(3);
        float step = w / bumps;
        for (int i = bumps - 1; i >= 0; i--) {
            float x0 = step * i, mid = x0 + step / 2;
            float low = d * (0.75f + 0.45f * r.nextFloat());
            float edge = d * (0.7f + 0.2f * r.nextFloat());
            if (withDrips && dripCount < 3 && (i == 1 || i == bumps - 2 || i == bumps / 2) && (r.nextBoolean() || i == bumps / 2)) {
                float rr = Math.min(step * 0.11f, d * 0.28f), len = d * 0.69f;
                len = Math.min(len, h * 0.32f - rr * 1.3f - low);   // keep the tip clear of the logo
                p.quadTo(mid + rr * 2.4f, low, mid + rr, low + len);
                p.cubicTo(mid + rr, low + len + rr * 1.7f, mid - rr, low + len + rr * 1.7f, mid - rr, low + len);
                p.quadTo(mid - rr * 2.4f, low, x0, edge);
                drips[dripCount * 3] = mid;
                drips[dripCount * 3 + 1] = low + len;
                drips[dripCount * 3 + 2] = rr;
                dripCount++;
            } else {
                p.quadTo(mid, low * 1.25f, x0, edge);
            }
        }
        p.close();
    }

    /** A quarter cobweb in the top-left corner: five spokes and four sagging rings. */
    private void buildWeb(float r) {
        Path p = p1;
        float[] ring = {0.26f, 0.46f, 0.68f, 0.92f};
        for (int k = 0; k <= 4; k++) {
            double a = Math.toRadians(22.5 * k);
            p.moveTo(0, 0);
            p.lineTo((float) Math.cos(a) * r, (float) Math.sin(a) * r);
        }
        for (float f : ring) {
            float rr = r * f;
            p.moveTo(rr, 0);
            for (int k = 1; k <= 4; k++) {
                double a = Math.toRadians(22.5 * k), am = Math.toRadians(22.5 * k - 11.25);
                p.quadTo((float) Math.cos(am) * rr * 0.7f, (float) Math.sin(am) * rr * 0.7f,
                        (float) Math.cos(a) * rr, (float) Math.sin(a) * rr);
            }
        }
    }

    private void buildLantern(RectF r, float x, float l) {
        r.set(x - l * 0.44f, l * 0.22f, x + l * 0.44f, l * 0.82f);
        p1.addOval(r, Path.Direction.CW);
        r.set(x - l * 0.26f, l * 0.22f, x + l * 0.26f, l * 0.82f);
        p2.addOval(r, Path.Direction.CW);
        p2.moveTo(x, l * 0.22f);
        p2.lineTo(x, l * 0.82f);
        r.set(x - l * 0.26f, l * 0.17f, x + l * 0.26f, l * 0.25f);
        p3.addRoundRect(r, l * 0.03f, l * 0.03f, Path.Direction.CW);
        r.set(x - l * 0.26f, l * 0.79f, x + l * 0.26f, l * 0.87f);
        p3.addRoundRect(r, l * 0.03f, l * 0.03f, Path.Direction.CW);
        p4.moveTo(x, 0);
        p4.lineTo(x, l * 0.17f);
        p4.moveTo(x, l * 0.87f);
        p4.lineTo(x, l * 1.0f);
        p4.moveTo(x, l * 1.0f);
        p4.lineTo(x - l * 0.07f, l * 1.22f);
        p4.moveTo(x, l * 1.0f);
        p4.lineTo(x, l * 1.24f);
        p4.moveTo(x, l * 1.0f);
        p4.lineTo(x + l * 0.07f, l * 1.22f);
        r.set(x - l * 0.32f, l * 0.32f, x - l * 0.2f, l * 0.5f);
        p5.addOval(r, Path.Direction.CW);
    }

    private void buildCandle(RectF r, float x, float s) {
        r.set(x - s * 0.16f, s * 0.5f, x + s * 0.16f, s * 1.1f);
        p1.addRoundRect(r, s * 0.04f, s * 0.04f, Path.Direction.CW);
        r.set(x + s * 0.04f, s * 0.5f, x + s * 0.16f, s * 1.1f);
        p2.addRoundRect(r, s * 0.04f, s * 0.04f, Path.Direction.CW);
        r.set(x - s * 0.16f, s * 0.47f, x + s * 0.16f, s * 0.56f);
        p3.addOval(r, Path.Direction.CW);
        r.set(x - s * 0.13f, s * 0.52f, x - s * 0.07f, s * 0.78f);
        p3.addRoundRect(r, s * 0.025f, s * 0.025f, Path.Direction.CW);
        flame(p4, x, s * 0.48f, s * 0.42f, s * 0.14f);
        flame(p5, x, s * 0.47f, s * 0.22f, s * 0.07f);
    }

    private static void flame(Path p, float x, float base, float hgt, float wid) {
        p.moveTo(x, base - hgt);
        p.cubicTo(x + wid * 0.9f, base - hgt * 0.4f, x + wid, base, x, base);
        p.cubicTo(x - wid, base, x - wid * 0.9f, base - hgt * 0.4f, x, base - hgt);
        p.close();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width(), h = b.height(), s = h * 0.544f;   // s: size of a corner motif
        c.save();
        c.translate(b.left, b.top);
        switch (ornament) {
            case "snow_cap":
                snow(c, h, col(0, 0xFFFFFFFF));
                for (int i = 0; i < dripCount; i++) {
                    float x = drips[i * 3], y = drips[i * 3 + 1], rr = drips[i * 3 + 2];
                    // a bluish crescent under the tip with a white highlight on top gives the drip volume
                    fill.setColor(0xFFD3E1F3);
                    c.drawCircle(x, y + rr * 0.2f, rr * 0.95f, fill);
                    fill.setColor(0xFFFFFFFF);
                    c.drawCircle(x - rr * 0.12f, y, rr * 0.78f, fill);
                }
                break;
            case "frost":
                snow(c, h * 0.6f, col(0, 0xF2F4F9FF));
                break;
            case "sun":
                fill.setShader(glint);
                c.drawRect(0, 0, w, h, fill);
                fill.setShader(null);
                motif(c, SPARK, w - s * 0.45f, s * 0.4f, s * 0.85f, 0f, 0xFFFFD54A, 0xFFD98200, 0);
                motif(c, SPARK, w - s * 1.05f, s * 0.25f, s * 0.38f, 0f, 0xFFFFE9A8, 0xFFD98200, 0);
                break;
            case "blossom":
                float sw = h * 0.05f;
                line.setStrokeWidth(sw * 1.9f);
                line.setColor(0x55000000);
                c.drawPath(p1, line);
                line.setStrokeWidth(sw);
                line.setColor(0xFF93603E);
                c.drawPath(p1, line);
                line.setStrokeWidth(sw * 0.3f);
                line.setColor(0xFFB98562);
                c.drawPath(p1, line);
                blossom(c, s * 0.36f, s * 0.38f, s * 0.38f, col(0, 0xFFF58DB1), col(1, 0xFFFFDCE9));
                blossom(c, s * 0.88f, s * 0.5f, s * 0.28f, col(0, 0xFFF9A3C1), col(1, 0xFFFFE4EE));
                blossom(c, s * 1.12f, s * 0.28f, s * 0.2f, col(0, 0xFFF47FA8), col(1, 0xFFFFD3E3));
                break;
            case "leaves":
                motif(c, LEAF, w - s * 0.4f, s * 0.4f, s * 0.7f, 35f, col(0, 0xFFE8731C), 0xFF9A3B05, 2);
                motif(c, LEAF, w - s * 1.0f, s * 0.28f, s * 0.52f, -40f, col(1, 0xFFC4381F), 0xFF7A1B0C, 2);
                break;
            case "heart":
                motif(c, HEART, w - s * 0.48f, s * 0.37f, s * 0.8f, 12f, col(0, 0xFFE8284A), 0xFF9E1030, 1);
                break;
            case "hearts":
                motif(c, HEART, w - s * 0.48f, s * 0.37f, s * 0.8f, 12f, col(0, 0xFFE8284A), 0xFF9E1030, 1);
                motif(c, HEART, w - s * 1.1f, s * 0.3f, s * 0.5f, -15f, col(1, 0xFFFFC928), 0xFFB57A00, 1);
                break;
            case "sparkles":
                motif(c, SPARK, w - s * 0.45f, s * 0.4f, s * 0.8f, 0f, col(0, 0xFFFFD45C), 0xFFB8860B, 0);
                motif(c, SPARK, w - s * 1.0f, s * 0.25f, s * 0.4f, 0f, col(1, 0xFFFFF1C2), 0xFFC9A227, 0);
                motif(c, SPARK, w - s * 0.2f, s * 0.7f, s * 0.22f, 0f, col(0, 0xFFFFE58A), 0xFFB8860B, 0);
                break;
            case "lantern":
                lantern(c, h * 0.363f);
                break;
            case "flag_band":
                // Top-to-bottom stripes in tiles.colors, equal heights; nothing without colours.
                int[] stripes = colors;
                if (stripes.length == 0) break;
                float band = h * 0.09f, y = 0;
                fill.setColor(0x44000000);
                c.drawRect(0, band, w, band * 1.4f, fill);
                for (int i = 0; i < stripes.length; i++) {
                    float part = 1f / stripes.length;
                    fill.setColor(stripes[i]);
                    c.drawRect(0, y, w, y + band * part, fill);
                    y += band * part;
                }
                break;
            case "cobweb":
                float webStroke = Math.max(1f, h * 0.45f * 0.022f);
                line.setStrokeWidth(webStroke * 1.6f);
                line.setColor(0x66000000);
                c.translate(webStroke * 0.5f, webStroke * 0.7f);
                c.drawPath(p1, line);
                c.translate(-webStroke * 0.5f, -webStroke * 0.7f);
                line.setStrokeWidth(webStroke);
                line.setColor(0xE6FFFFFF);
                c.drawPath(p1, line);
                break;
            case "marigold":
                marigold(c, w - s * 0.45f, s * 0.42f, s * 0.38f);
                break;
            case "candle":
                candle(c, w - h * 0.17f, h * 0.408f);
                break;
            case "image":
                if (!imageDst.isEmpty() && !image.isRecycled()) c.drawBitmap(image, null, imageDst, imagePaint);
                break;
            default:
                break;
        }
        c.restore();
    }

    /** Snow cap drawn in layers: a drop shadow, a blue-grey underside, a pale body and the top colour. */
    private void snow(Canvas c, float h, int top) {
        layer(c, h * 0.05f, 0x38000000);
        layer(c, h * 0.03f, 0xFFAEC3DF);
        layer(c, h * 0.014f, 0xFFE2EBF7);
        layer(c, 0f, top);
    }

    private void layer(Canvas c, float dy, int color) {
        fill.setColor(color);
        c.translate(0, dy);
        c.drawPath(p1, fill);
        c.translate(0, -dy);
    }

    /** detail: 0 plain, 1 gloss dot (hearts), 2 centre vein (leaves). */
    private void motif(Canvas c, Path shape, float x, float y, float size, float angle, int color, int rim,
                       int detail) {
        fill.setColor(0x55000000);
        c.save();
        c.translate(x + size * 0.04f, y + size * 0.06f);
        c.rotate(angle);
        c.scale(size, size);
        c.drawPath(shape, fill);   // a soft drop shadow so the motif reads over light banners
        c.restore();
        c.save();
        c.translate(x, y);
        c.rotate(angle);
        c.scale(size, size);
        line.setColor(rim);
        line.setStrokeWidth(0.06f);
        c.drawPath(shape, line);   // a darker rim keeps the edge on dark banners too
        fill.setColor(color);
        c.drawPath(shape, fill);
        if (detail == 1) {
            fill.setColor(0x77FFFFFF);
            c.drawCircle(-0.22f, -0.14f, 0.06f, fill);
        } else if (detail == 2) {
            line.setStrokeWidth(0.03f);
            line.setColor((rim & 0x00FFFFFF) | 0xAA000000);
            c.drawLine(0f, 0.45f, 0f, -0.3f, line);
        }
        c.restore();
    }

    private void blossom(Canvas c, float x, float y, float r, int petal, int light) {
        fill.setColor(0x44000000);
        for (int k = 0; k < 5; k++) {
            c.drawCircle(x + COS5[k] * r * 0.5f + r * 0.05f, y + SIN5[k] * r * 0.5f + r * 0.09f, r * 0.45f, fill);
        }
        fill.setColor(petal);
        for (int k = 0; k < 5; k++) {
            c.drawCircle(x + COS5[k] * r * 0.5f, y + SIN5[k] * r * 0.5f, r * 0.45f, fill);
        }
        fill.setColor(light);   // the lighter inner half of each petal is the two-tone
        for (int k = 0; k < 5; k++) {
            c.drawCircle(x + COS5[k] * r * 0.38f, y + SIN5[k] * r * 0.38f, r * 0.24f, fill);
        }
        fill.setColor(0xFFD81B60);
        c.drawCircle(x, y, r * 0.17f, fill);
        fill.setColor(0xFFFFD54F);
        c.drawCircle(x, y, r * 0.07f, fill);
    }

    private void lantern(Canvas c, float l) {
        fill.setColor(0x55000000);
        c.translate(l * 0.05f, l * 0.07f);
        c.drawPath(p1, fill);
        c.drawPath(p3, fill);
        c.translate(-l * 0.05f, -l * 0.07f);
        fill.setColor(col(0, 0xFFD7261E));
        c.drawPath(p1, fill);
        line.setStrokeWidth(l * 0.03f);
        line.setColor(0x99701008);
        c.drawPath(p2, line);
        fill.setColor(0x55FFFFFF);
        c.drawPath(p5, fill);
        fill.setColor(col(1, 0xFFFFC94A));
        c.drawPath(p3, fill);
        line.setStrokeWidth(l * 0.05f);
        line.setColor(col(1, 0xFFFFC94A));
        c.drawPath(p4, line);
    }

    private void marigold(Canvas c, float x, float y, float r) {
        fill.setColor(0x55000000);
        c.drawCircle(x + r * 0.06f, y + r * 0.1f, r * 1.05f, fill);
        fill.setColor(0xFFB04A00);
        for (int k = 0; k < 12; k++) {
            c.drawCircle(x + COS12[k] * r * 0.66f, y + SIN12[k] * r * 0.66f, r * 0.4f, fill);
        }
        fill.setColor(col(0, 0xFFE8650A));
        for (int k = 0; k < 12; k++) {
            c.drawCircle(x + COS12[k] * r * 0.66f, y + SIN12[k] * r * 0.66f, r * 0.34f, fill);
        }
        fill.setColor(col(1, 0xFFFF9A1A));
        for (int k = 0; k < 12; k += 3) {
            c.drawCircle(x + COS12[k] * r * 0.38f, y + SIN12[k] * r * 0.38f, r * 0.26f, fill);
            c.drawCircle(x + COS12[k + 1] * r * 0.38f, y + SIN12[k + 1] * r * 0.38f, r * 0.26f, fill);
        }
        fill.setColor(0xFFFFC53D);
        for (int k = 0; k < 5; k++) {
            c.drawCircle(x + COS5[k] * r * 0.2f, y + SIN5[k] * r * 0.2f, r * 0.2f, fill);
        }
        fill.setColor(0xFFFFE08A);
        c.drawCircle(x, y, r * 0.1f, fill);
    }

    private void candle(Canvas c, float x, float s) {
        fill.setShader(glint);
        c.drawCircle(x, s * 0.3f, s * 0.55f, fill);
        fill.setShader(null);
        fill.setColor(0x55000000);
        c.translate(s * 0.04f, s * 0.05f);
        c.drawPath(p1, fill);
        c.translate(-s * 0.04f, -s * 0.05f);
        fill.setColor(col(0, 0xFFFFF4E0));
        c.drawPath(p1, fill);
        fill.setColor(0xFFE6CFA3);
        c.drawPath(p2, fill);
        fill.setColor(0xFFFFFAEB);
        c.drawPath(p3, fill);
        line.setColor(0xFF3A2A20);
        line.setStrokeWidth(s * 0.03f);
        c.drawLine(x, s * 0.42f, x, s * 0.52f, line);
        fill.setColor(col(1, 0xFFFF9A1A));
        c.drawPath(p4, fill);
        fill.setColor(0xFFFFE27A);
        c.drawPath(p5, fill);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        imagePaint.setAlpha(alpha);
        line.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        imagePaint.setColorFilter(cf);
        line.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
