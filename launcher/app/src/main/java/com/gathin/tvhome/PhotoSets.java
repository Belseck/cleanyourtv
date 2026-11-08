package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Which photos the carousels show. A theme with internet photos on uses its own set, found with the theme's
 * photos.queries. The screensaver puts the active theme first (its name plus its queries) and the screensaver
 * topics from Launcher settings after; with no theme today, only those topics, or a varied default.
 * Home records the active theme in the settings file, since the screensaver runs without Home.
 */
final class PhotoSets {

    static final int THEME_COUNT = 24, SAVER_THEME_COUNT = 16, SAVER_GENERAL_COUNT = 16;
    static final String ONLINE_PREFIX = "photos_online_";
    static final String SAVER_TOPICS = "saver_topics", SAVER_DAYNIGHT = "saver_daynight";
    static final String INTERVAL = "photo_interval";
    /** With no theme: "" for the wallpaper, "online" for internet photos, or a picture's path on the TV. */
    static final String HOME_BACKGROUND = "home_background";
    /** The no-theme internet background's photos, found with the photo topics. */
    static final String HOME_SET = "home-general";
    /** The quick panel's suggestions, as search terms: holidays, seasons, then nature and more. */
    static final String[][] SUGGESTED = {
            {"christmas lights", "halloween pumpkins", "day of the dead", "valentines day", "new year fireworks",
                    "easter eggs", "lunar new year lanterns"},
            {"spring blossoms", "summer beach", "autumn foliage", "winter snow"},
            {"mountain landscape", "ocean waves", "misty forest", "waterfall", "desert dunes", "northern lights",
                    "starry night sky", "city skyline at night", "lake reflection", "flower field", "wildlife",
                    "architecture", "aerial view"}};
    /** Choices for how often a carousel changes photo, in seconds. */
    static final int[] INTERVALS = {10, 20, 30, 60, 120, 300, 600, 1800};
    private static final String ACTIVE_FOLDER = "saver_theme_folder", ACTIVE_NAME = "saver_theme_name",
            ACTIVE_QUERIES = "saver_theme_queries", ACTIVE_DAY = "saver_theme_day";
    static final List<String> DEFAULT_TOPICS = Arrays.asList("mountain landscape", "ocean waves", "misty forest",
            "city skyline at night", "starry night sky", "waterfall", "desert dunes", "northern lights");

    /** One picture of a carousel: a file, with its Unsplash details when it came from the internet. */
    static final class Item {
        final File file;
        final OnlinePhotos.Photo photo;
        final String set;

        Item(File file, OnlinePhotos.Photo photo, String set) {
            this.file = file;
            this.photo = photo;
            this.set = set;
        }
    }

    private PhotoSets() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static boolean themeOnline(Context c, String folder) {
        return prefs(c).getBoolean(ONLINE_PREFIX + folder, false);
    }

    static void setThemeOnline(Context c, String folder, boolean on) {
        prefs(c).edit().putBoolean(ONLINE_PREFIX + folder, on).apply();
    }

    static String themeSet(String folder) {
        return "theme-" + folder;
    }

    /**
     * Words that mark a photo as belonging to another time of year. A theme keeps out every holiday whose words its
     * own search terms do not use, and a season (its terms name one) also the other seasons, so autumn gets no
     * Christmas trees or snow. A holiday keeps the seasons, since Halloween photos are full of autumn leaves.
     */
    private static final String[][] HOLIDAYS = {
            {"christmas", "xmas", "santa", "reindeer", "snowman", "nativity", "ornament", "ornaments"},
            {"halloween", "pumpkin", "pumpkins", "jack", "witch", "skeleton", "spooky", "haunted"},
            {"valentine", "valentines"},
            {"fireworks", "firework", "new year", "champagne"},
            {"easter", "bunny"},
            {"lunar", "chinese new year"},
            {"muertos", "dead", "catrina"}};
    private static final String[][] SEASONS = {
            {"winter", "snow", "snowy", "frozen", "ice", "icy", "frost"},
            {"spring", "blossom", "blossoms", "tulip", "tulips", "cherry"},
            {"summer", "beach", "tropical", "palm", "sunflower"},
            {"autumn", "fall foliage", "fallen leaves", "foliage", "fall leaves"}};

    static List<String> excludeFor(List<String> queries) {
        String own = " " + String.join(" ", queries).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        List<String> out = new ArrayList<>();
        boolean season = false;
        for (String[] group : SEASONS) if (uses(own, group)) season = true;
        for (String[] group : HOLIDAYS) if (!uses(own, group)) out.addAll(Arrays.asList(group));
        if (season) for (String[] group : SEASONS) if (!uses(own, group)) out.addAll(Arrays.asList(group));
        return out;
    }

    private static boolean uses(String own, String[] group) {
        for (String w : group) if (own.contains(" " + w + " ")) return true;
        return false;
    }

    /** The theme's photos.queries, else its English name. */
    static List<String> themeQueries(SeasonPack.Season s) {
        if (!s.photoQueries.isEmpty()) return s.photoQueries;
        String name = SeasonPack.localizedName(s.names, Locale.ENGLISH);
        return Arrays.asList((name.isEmpty() ? s.folder : name) + " background");
    }

    static List<Item> items(Context c, String set) {
        List<Item> out = new ArrayList<>();
        for (OnlinePhotos.Photo p : OnlinePhotos.load(c, set)) out.add(new Item(OnlinePhotos.file(c, set, p), p, set));
        return out;
    }

    /** Home tells the screensaver which theme is on today, or none. */
    static void recordActive(Context c, SeasonPack.Season s) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (s == null) {
            e.remove(ACTIVE_FOLDER);
        } else {
            e.putString(ACTIVE_FOLDER, s.folder)
                    .putString(ACTIVE_NAME, SeasonPack.localizedName(s.names, Locale.ENGLISH))
                    .putString(ACTIVE_QUERIES, String.join("\n", s.photoQueries));
        }
        e.putString(ACTIVE_DAY, LocalDate.now().toString()).apply();
    }

    /** Seconds per photo in every carousel, from Launcher settings; 30 until it is set. */
    static int interval(Context c) {
        return prefs(c).getInt(INTERVAL, 30);
    }

    /** The theme Home last recorded, if it recorded it today. */
    private static String activeFolder(Context c) {
        SharedPreferences p = prefs(c);
        return LocalDate.now().toString().equals(p.getString(ACTIVE_DAY, "")) ? p.getString(ACTIVE_FOLDER, "") : "";
    }

    /** The photo topics, or a varied mix when none are chosen. */
    static List<String> topics(Context c) {
        List<String> t = saverTopics(c);
        return t.isEmpty() ? DEFAULT_TOPICS : t;
    }

    static void setTopics(Context c, List<String> topics) {
        prefs(c).edit().putString(SAVER_TOPICS, String.join(", ", topics)).apply();
    }

    static List<String> saverTopics(Context c) {
        List<String> out = new ArrayList<>();
        for (String t : prefs(c).getString(SAVER_TOPICS, "").split(",")) if (!t.trim().isEmpty()) out.add(t.trim());
        return out;
    }

    /** "day" or "night" at config.xml's place, or "" when the day-and-night switch is off. */
    private static String daypart(Context c) {
        if (!prefs(c).getBoolean(SAVER_DAYNIGHT, false)) return "";
        int[] sun = WallpaperMood.sun(LocalDate.now(), c.getString(R.string.latitude), c.getString(R.string.longitude),
                java.time.ZoneId.systemDefault());
        int minute = java.time.LocalTime.now().toSecondOfDay() / 60;
        return minute >= sun[0] && minute < sun[1] ? "day" : "night";
    }

    private static List<String> withDaypart(List<String> queries, String part) {
        if (part.isEmpty()) return queries;
        List<String> out = new ArrayList<>();
        for (String q : queries) out.add(q + ("day".equals(part) ? " daylight" : " night"));
        return out;
    }

    /**
     * The screensaver's photos: the active theme's set first, then the general topics. Starts a fetch of either
     * set when it is missing or stale; {@code refreshed} runs when new photos arrived.
     */
    static List<Item> saver(Context c, Runnable refreshed) {
        String part = daypart(c), suffix = part.isEmpty() ? "" : "-" + part;
        List<Item> out = new ArrayList<>();
        String folder = activeFolder(c);
        if (!folder.isEmpty()) {
            SharedPreferences p = prefs(c);
            List<String> queries = new ArrayList<>();
            String name = p.getString(ACTIVE_NAME, "");
            if (!name.isEmpty()) queries.add(name);
            for (String q : p.getString(ACTIVE_QUERIES, "").split("\n")) if (!q.trim().isEmpty()) queries.add(q.trim());
            String set = "saver-theme-" + folder + suffix;
            OnlinePhotos.refresh(c, set, withDaypart(queries, part), excludeFor(queries), SAVER_THEME_COUNT, refreshed);
            out.addAll(items(c, set));
        }
        List<String> topics = saverTopics(c);
        if (topics.isEmpty()) topics = DEFAULT_TOPICS;
        String set = "saver-general" + suffix;   // new topics replace the set's photos on the next fetch
        OnlinePhotos.refresh(c, set, withDaypart(topics, part), excludeFor(topics), SAVER_GENERAL_COUNT, refreshed);
        out.addAll(items(c, set));
        return out;
    }
}
