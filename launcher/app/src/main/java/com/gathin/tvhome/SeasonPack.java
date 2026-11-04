package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A user-editable set of seasonal looks (seasons/seasons.json index plus seasons/<folder>/theme.json). {@link #parse} never throws: a bad entry is skipped and
 * reported in {@link #errors}, so a typo in the file cannot take Home down.
 */
final class SeasonPack {

    static final int MAX_SEASONS = 64, MAX_ID = 64, MAX_NAME = 80, MAX_COLORS = 16;
    private static final List<String> PARTICLES = Arrays.asList(
            "snow", "sakura", "leaf", "heart", "confetti", "spark", "bats", "marigold", "image", "none");
    private static final List<String> ORNAMENTS = Arrays.asList(
            "snow_cap", "frost", "blossom", "sun", "leaves", "heart", "hearts", "sparkles", "lantern", "flag_band",
            "cobweb", "marigold", "candle", "image", "none");
    private static final List<String> SKINS = Arrays.asList("halloween");
    private static final Pattern FOLDER = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern COLOR = Pattern.compile("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");

    static final class Season {
        final String id;
        /** The pack subfolder holding theme.json and the fixed-name images; equal to {@link #id}. */
        final String folder;
        final Map<String, String> names;
        final String nameRes;
        final int priority;
        final SeasonRule rule;
        final int backgroundOpacity;
        /** Built-in animated pill icons: "halloween" or "" for none. */
        final String skin;
        final String particles;
        final int[] particleColors;
        final int particleCount;
        final int particleSeconds;
        final String ornament;
        final int[] ornamentColors;
        final String ornamentCorner;
        final int accent;
        /** background/ rotation: seconds per picture and "sequential" or "shuffle"; set after construction. */
        int backgroundInterval = 300;
        String backgroundOrder = "sequential";
        /** photos.queries: search terms for the theme's internet photos (empty: its English name). */
        List<String> photoQueries = Collections.emptyList();

        Season(String folder, Map<String, String> names, String nameRes, int priority, SeasonRule rule,
               int backgroundOpacity, String skin, String particles, int[] particleColors, int particleCount,
               int particleSeconds, String ornament, int[] ornamentColors, String ornamentCorner, int accent) {
            this.id = folder;
            this.folder = folder;
            this.names = names;
            this.nameRes = nameRes;
            this.priority = priority;
            this.rule = rule;
            this.backgroundOpacity = backgroundOpacity;
            this.skin = skin;
            this.particles = particles;
            this.particleColors = particleColors;
            this.particleCount = particleCount;
            this.particleSeconds = particleSeconds;
            this.ornament = ornament;
            this.ornamentColors = ornamentColors;
            this.ornamentCorner = ornamentCorner;
            this.accent = accent;
        }

        /** A season with every optional field at its default; for tests. */
        static Season of(String id, int priority, SeasonRule rule) {
            return new Season(id, Collections.<String, String>emptyMap(), "", priority, rule, 35, "", "none",
                    new int[0], 0, 0, "none", new int[0], "top-right", 0);
        }
    }

    final List<Season> seasons;
    final List<String> errors;

    SeasonPack(List<Season> seasons, List<String> errors) {
        this.seasons = Collections.unmodifiableList(seasons);
        this.errors = Collections.unmodifiableList(errors);
    }

    /** The matching season with the highest priority; the first listed wins a tie; null if none matches. */
    Season resolve(LocalDate d) {
        Season best = null;
        for (Season s : seasons) {
            if (s.rule.matches(d) && (best == null || s.priority > best.priority)) best = s;
        }
        return best;
    }

    /** Exact tag ("es-419"), then language ("es"), then "en", then the first entry, else "". */
    static String localizedName(Map<String, String> names, Locale l) {
        if (names == null || names.isEmpty()) return "";
        String v = names.get(l.toLanguageTag());
        if (v == null) v = names.get(l.getLanguage());
        if (v == null) v = names.get("en");
        return v != null ? v : names.values().iterator().next();
    }

    /**
     * @param indexJson seasons/seasons.json (folder, dates, priority per season)
     * @param themeJson returns the text of {@code <folder>/theme.json}, or null/"" when the folder has none
     */
    static SeasonPack parse(String indexJson, Function<String, String> themeJson) {
        List<Season> seasons = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(indexJson).optJSONArray("seasons");
            if (arr == null) {
                errors.add("seasons.json has no \"seasons\" array");
            } else {
                parseSeasons(arr, themeJson, seasons, errors);
            }
        } catch (Exception | StackOverflowError x) {
            seasons.clear();
            errors.add("seasons.json is unreadable: " + x.getMessage());
        }
        return new SeasonPack(seasons, errors);
    }

    private static void parseSeasons(JSONArray arr, Function<String, String> themeJson, List<Season> out,
                                     List<String> errors) {
        java.util.Set<String> folders = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            if (i >= MAX_SEASONS) {
                errors.add("more than " + MAX_SEASONS + " seasons: the rest is ignored");
                break;
            }
            JSONObject o = arr.optJSONObject(i);
            String label = "season #" + i;
            try {
                if (o == null) throw new IllegalArgumentException("not an object");
                Object fv = o.opt("folder");
                if (fv instanceof String && !((String) fv).isEmpty()) label += " (" + clip((String) fv) + ")";
                String folder = str(o, "folder", "");
                if (!validFolder(folder)) {
                    throw new IllegalArgumentException("\"folder\" must be a bare name (letters, digits, - _ .), max "
                            + MAX_ID);
                }
                if (!folders.add(folder)) throw new IllegalArgumentException("duplicate folder");
                String text = themeJson == null ? null : themeJson.apply(folder);
                JSONObject theme;
                try {
                    theme = text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
                } catch (Exception x) {
                    throw new IllegalArgumentException("theme.json is unreadable: " + x.getMessage());
                }
                out.add(parseSeason(folder, o, theme));
            } catch (RuntimeException x) {
                errors.add(label + ": " + x.getMessage());
            }
        }
    }

    /** Folders are opened from the pack directory, so only bare names pass. */
    static boolean validFolder(String f) {
        return f != null && !f.isEmpty() && f.length() <= MAX_ID && f.charAt(0) != '.' && FOLDER.matcher(f).matches();
    }

    /** Index entry: folder, dates, priority. Theme: name, name_res, background_opacity, accent, skin, particles{}, tiles{}. */
    static Season parseSeason(String folder, JSONObject index, JSONObject o) throws IllegalArgumentException {
        Map<String, String> names = new LinkedHashMap<>();
        Object nv = o.opt("name");
        if (nv instanceof String) {
            names.put("en", clipName((String) nv));
        } else if (nv instanceof JSONObject) {
            JSONObject no = (JSONObject) nv;
            for (Iterator<String> it = no.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = no.opt(k);
                if (!(v instanceof String)) throw new IllegalArgumentException("name \"" + k + "\" must be text");
                names.put(k, clipName((String) v));
            }
        } else if (nv != null && nv != JSONObject.NULL) {
            throw new IllegalArgumentException("\"name\" must be an object of language tags");
        }
        if (names.isEmpty()) names.put("en", clipName(folder));

        SeasonRule rule = SeasonRule.fromJson(index.optJSONObject("dates"));
        JSONObject p = obj(o, "particles");
        String particles = kind(p, PARTICLES, "particles");
        JSONObject r = obj(o, "tiles");
        String ornament = str(r, "ornament", "none");
        if (!ORNAMENTS.contains(ornament)) throw new IllegalArgumentException("unknown tiles ornament \"" + clip(ornament) + "\"");
        String corner = str(r, "corner", "top-right");
        if (!corner.equals("top-left") && !corner.equals("top-right") && !corner.equals("top")) {
            throw new IllegalArgumentException("ornament corner must be top-left, top-right or top");
        }
        String order = str(o, "background_order", "sequential");
        if (!order.equals("sequential") && !order.equals("shuffle")) {
            throw new IllegalArgumentException("background_order must be sequential or shuffle");
        }
        String skin = str(o, "skin", "");
        if (!skin.isEmpty() && !SKINS.contains(skin)) throw new IllegalArgumentException("unknown skin \"" + clip(skin) + "\"");
        String accent = str(o, "accent", "");
        Season season = new Season(folder, names, str(o, "name_res", ""), clamp(num(index, "priority", 0), -1000, 1000),
                rule, clamp(num(o, "background_opacity", 35), 0, 100), skin, particles, colors(p),
                clamp(num(p, "count", 0), 0, 150), clamp(num(p, "seconds", 0), 0, 15), ornament, colors(r), corner,
                accent.isEmpty() ? 0 : color(accent));
        season.backgroundInterval = clamp(num(o, "background_interval", 300), 10, 86400);
        season.backgroundOrder = order;
        season.photoQueries = queries(obj(o, "photos"));
        return season;
    }

    /** photos.queries: up to 8 search terms of at most 80 characters each. */
    static List<String> queries(JSONObject photos) {
        Object v = photos.opt("queries");
        if (v == null || v == JSONObject.NULL) return Collections.emptyList();
        if (!(v instanceof JSONArray)) throw new IllegalArgumentException("photos.queries must be a list of text");
        JSONArray arr = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length() && out.size() < 8; i++) {
            Object q = arr.opt(i);
            if (!(q instanceof String)) throw new IllegalArgumentException("photos.queries must be a list of text");
            String t = ((String) q).trim();
            if (!t.isEmpty()) out.add(t.length() > 80 ? t.substring(0, 80) : t);
        }
        return Collections.unmodifiableList(out);
    }

    private static JSONObject obj(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return new JSONObject();
        if (!(v instanceof JSONObject)) throw new IllegalArgumentException("\"" + key + "\" must be an object");
        return (JSONObject) v;
    }

    private static String kind(JSONObject o, List<String> allowed, String what) {
        String k = str(o, "kind", "none");
        if (!allowed.contains(k)) throw new IllegalArgumentException("unknown " + what + " kind \"" + clip(k) + "\"");
        return k;
    }

    private static String str(JSONObject o, String key, String def) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return def;
        if (!(v instanceof String)) throw new IllegalArgumentException("\"" + key + "\" must be text");
        return (String) v;
    }

    private static int num(JSONObject o, String key, int def) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return def;
        if (!(v instanceof Number)) throw new IllegalArgumentException("\"" + key + "\" must be a number");
        double d = ((Number) v).doubleValue();
        return d >= Integer.MAX_VALUE ? Integer.MAX_VALUE : d <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) d;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int[] colors(JSONObject o) {
        Object v = o.opt("colors");
        if (v == null || v == JSONObject.NULL) return new int[0];
        if (!(v instanceof JSONArray)) throw new IllegalArgumentException("\"colors\" must be a list");
        JSONArray a = (JSONArray) v;
        if (a.length() > MAX_COLORS) throw new IllegalArgumentException("more than " + MAX_COLORS + " colors");
        int[] out = new int[a.length()];
        for (int i = 0; i < out.length; i++) {
            Object c = a.opt(i);
            if (!(c instanceof String)) throw new IllegalArgumentException("colors must be \"#RRGGBB\" text");
            out[i] = color((String) c);
        }
        return out;
    }

    static int color(String s) {
        if (!COLOR.matcher(s).matches()) {
            throw new IllegalArgumentException("bad colour \"" + clip(s) + "\" (use #RRGGBB or #AARRGGBB)");
        }
        long v = Long.parseLong(s.substring(1), 16);
        return (int) (s.length() == 7 ? 0xFF000000L | v : v);
    }

    private static String clip(String s) {
        return s.length() > 32 ? s.substring(0, 32) + "..." : s;
    }

    private static String clipName(String s) {
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }
}
