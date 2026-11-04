package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background photos found by search terms, kept on the TV: files/online/&lt;set&gt;/ holds the JPEGs (1920 px wide)
 * and index.json with each photo's credit and details. A set is fetched again when its terms or its source change,
 * or when it is older than {@link #MAX_AGE_MS}; until then, and without a network, the photos on disk are used.
 * The source is Unsplash when the build has an access key (unsplash.access_key in local.properties, never
 * committed), else Wikimedia Commons, which needs no key; places for Commons photos come from OpenStreetMap.
 */
final class OnlinePhotos {

    static final class Photo {
        String id = "", file = "", description = "", location = "", photographer = "", camera = "", taken = "",
                page = "", source = "Unsplash", license = "";
        int width, height;
        /** Where it was taken, for the place name; NaN when the photo has no coordinates. */
        double lat = Double.NaN, lon = Double.NaN;
        /** Whether location and camera were asked for already (they are fetched one photo at a time). */
        boolean detailed;

        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject().put("id", id).put("file", file).put("description", description)
                    .put("location", location).put("photographer", photographer).put("camera", camera)
                    .put("taken", taken).put("page", page).put("width", width).put("height", height)
                    .put("detailed", detailed).put("source", source).put("license", license)
                    .put("lat", Double.isNaN(lat) ? JSONObject.NULL : lat).put("lon", Double.isNaN(lon) ? JSONObject.NULL : lon);
        }

        static Photo fromJson(JSONObject o) {
            Photo p = new Photo();
            p.id = o.optString("id");
            p.file = o.optString("file");
            p.description = o.optString("description");
            p.location = o.optString("location");
            p.photographer = o.optString("photographer");
            p.camera = o.optString("camera");
            p.taken = o.optString("taken");
            p.page = o.optString("page");
            p.width = o.optInt("width");
            p.height = o.optInt("height");
            p.detailed = o.optBoolean("detailed");
            p.source = o.optString("source", "Unsplash");
            p.license = o.optString("license");
            p.lat = o.optDouble("lat", Double.NaN);
            p.lon = o.optDouble("lon", Double.NaN);
            return p;
        }
    }

    static final long MAX_AGE_MS = 3 * 24 * 3600_000L;
    private static final String API = "https://api.unsplash.com";
    private static final String COMMONS = "https://commons.wikimedia.org/w/api.php";
    private static final String NOMINATIM = "https://nominatim.openstreetmap.org/reverse";
    /** Wikimedia and OpenStreetMap ask every client to name itself. */
    private static final String AGENT = "TVHomeLauncher/" + "3.3" + " (Android TV launcher)";
    static final String COMMONS_NAME = "Wikimedia Commons";
    private static final String TAG = "TvHomePhotos";
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    /** Requests left this hour, as Unsplash last said (50 an hour on a demo key). */
    private static volatile int remaining = 50;
    private static final Set<String> RUNNING = Collections.synchronizedSet(new HashSet<>());

    private OnlinePhotos() {
    }

    static boolean hasKey(Context c) {
        return !c.getString(R.string.unsplash_key).isEmpty();
    }

    /** Bare names only: a set name becomes a folder. */
    static String setName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static File dir(Context c, String set) {
        return new File(new File(c.getFilesDir(), "online"), setName(set));
    }

    /** The photos of a set whose files are on disk, in the order they were found. */
    static List<Photo> load(Context c, String set) {
        List<Photo> out = new ArrayList<>();
        JSONObject index = readIndex(c, set);
        JSONArray arr = index.optJSONArray("photos");
        File dir = dir(c, set);
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Photo p = Photo.fromJson(o);
            if (new File(dir, p.file).isFile()) out.add(p);
        }
        return out;
    }

    static File file(Context c, String set, Photo p) {
        return new File(dir(c, set), p.file);
    }

    /**
     * Fetches the set again in the background when its terms changed or it is stale, then runs {@code done} on the
     * main thread (only when something new was saved). Does nothing without a key or a network.
     */
    static void refresh(Context c, String set, List<String> queries, List<String> exclude, int count, Runnable done) {
        Context app = c.getApplicationContext();
        if (queries.isEmpty() || !online(app)) return;
        // The source is part of the terms: adding a key later brings Unsplash photos in place of the Commons ones.
        int[] screen = screen(app);
        String terms = (hasKey(app) ? "unsplash|" : "commons|") + screen[0] + "x" + screen[1] + "|"
                + String.join("|", queries) + "|-" + String.join("|-", exclude);
        JSONObject index = readIndex(app, set);
        boolean fresh = terms.equals(index.optString("terms"))
                && System.currentTimeMillis() - index.optLong("fetched") < MAX_AGE_MS
                && !load(app, set).isEmpty();
        if (fresh || !RUNNING.add(set)) return;
        NET.execute(() -> {
            try {
                if (fetch(app, set, queries, exclude, terms, count) && done != null) {
                    new Handler(Looper.getMainLooper()).post(done);
                }
            } catch (Exception e) {
                Log.w(TAG, "fetch " + set + ": " + e);
            } finally {
                RUNNING.remove(set);
            }
        });
    }

    private static boolean online(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null;
    }

    private static boolean fetch(Context c, String set, List<String> queries, List<String> exclude, String terms, int count)
            throws Exception {
        return hasKey(c) ? fetchUnsplash(c, set, queries, exclude, terms, count)
                : fetchCommons(c, set, queries, exclude, terms, count);
    }

    /** The screen in pixels, landscape: photos are fetched at this size, no larger. */
    static int[] screen(Context c) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return new int[]{Math.max(dm.widthPixels, dm.heightPixels), Math.min(dm.widthPixels, dm.heightPixels)};
    }

    /** Whether a photo's own words name something the theme keeps out (another season, a holiday). */
    static boolean excluded(String text, List<String> exclude) {
        String t = " " + text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        for (String w : exclude) if (t.contains(" " + w + " ")) return true;
        return false;
    }

    /** Search results interleaved across the terms, light photos last; downloads what is not on disk yet. */
    private static boolean fetchUnsplash(Context c, String set, List<String> queries, List<String> exclude, String terms,
                                         int count) throws Exception {
        int[] screen = screen(c);
        int per = Math.min(30, Math.max(4, (count + queries.size() - 1) / queries.size() + 2));
        List<List<JSONObject>> lists = new ArrayList<>();
        for (String q : queries) {
            JSONObject res = new JSONObject(get(c, API + "/search/photos?per_page=" + per
                    + "&orientation=landscape&content_filter=high&query=" + URLEncoder.encode(q, "UTF-8")));
            JSONArray arr = res.optJSONArray("results");
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) list.add(arr.getJSONObject(i));
            lists.add(list);
        }
        List<JSONObject> picked = new ArrayList<>(), light = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int round = 0; picked.size() + light.size() < count * 2 && round < 30; round++) {
            boolean any = false;
            for (List<JSONObject> list : lists) {
                if (round >= list.size()) continue;
                any = true;
                JSONObject o = list.get(round);
                if (!seen.add(o.optString("id"))) continue;
                if (o.optInt("width") < screen[0] || o.optInt("height") < screen[1]) continue;   // no upscaling
                if (excluded(o.optString("alt_description") + " " + o.optString("description") + " "
                        + o.optString("slug") + " " + o.optString("seo_meta_description"), exclude)) continue;
                (luminance(o.optString("color")) > 0.72 ? light : picked).add(o);
            }
            if (!any) break;
        }
        picked.addAll(light);   // too-bright pictures only when the dark ones run out
        List<Photo> photos = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (JSONObject o : picked) {
            String raw = o.getJSONObject("urls").getString("raw");
            photos.add(fromSearch(o));
            // Cropped by Unsplash to exactly the screen: nothing larger is downloaded or decoded.
            urls.add(raw + (raw.contains("?") ? "&" : "?") + "w=" + screen[0] + "&h=" + screen[1]
                    + "&fit=crop&crop=entropy&q=80&fm=jpg");
        }
        return save(c, set, terms, count, photos, urls);
    }

    /**
     * Wikimedia Commons, no key: photos (JPEG, landscape, at least 1600 px wide) found by each term, at most three by
     * one author per term so a single uploader's series does not fill the set, scaled to 1920 px by Commons itself.
     */
    private static boolean fetchCommons(Context c, String set, List<String> queries, List<String> exclude, String terms,
                                        int count) throws Exception {
        int[] screen = screen(c);
        // Commons serves set widths only; the smallest of them that still covers the screen.
        int width = screen[0] <= 1280 ? 1280 : screen[0] <= 1920 ? 1920 : 3840;
        List<List<Photo>> lists = new ArrayList<>();
        List<List<String>> urlLists = new ArrayList<>();
        for (String q : queries) {
            JSONObject res = new JSONObject(get(c, COMMONS + "?action=query&format=json&generator=search"
                    + "&gsrnamespace=6&gsrlimit=40&prop=imageinfo&iiprop=url|size|mime|extmetadata&iiurlwidth=" + width
                    + "&iiextmetadatafilter=ImageDescription|Artist|LicenseShortName|DateTimeOriginal|GPSLatitude|GPSLongitude"
                    + "&gsrsearch=" + URLEncoder.encode(q + " filetype:bitmap filew:>" + (screen[0] - 1)
                    + " fileh:>" + (screen[1] - 1), "UTF-8")));
            JSONObject query = res.optJSONObject("query");
            JSONObject pages = query == null ? null : query.optJSONObject("pages");
            List<JSONObject> found = new ArrayList<>();
            if (pages != null) for (java.util.Iterator<String> it = pages.keys(); it.hasNext(); ) found.add(pages.getJSONObject(it.next()));
            found.sort((a, b) -> Integer.compare(a.optInt("index"), b.optInt("index")));   // the search's own ranking
            List<Photo> list = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            java.util.Map<String, Integer> perAuthor = new java.util.HashMap<>();
            for (JSONObject page : found) {
                JSONArray infos = page.optJSONArray("imageinfo");
                JSONObject ii = infos == null ? null : infos.optJSONObject(0);
                if (ii == null || !"image/jpeg".equals(ii.optString("mime"))) continue;
                int w = ii.optInt("width"), h = ii.optInt("height");
                if (w < screen[0] || h < screen[1] || w < h * 1.3 || w > h * 2.4) continue;
                String url = ii.optString("thumburl", "");
                if (url.isEmpty()) continue;
                JSONObject m = ii.optJSONObject("extmetadata");
                Photo p = new Photo();
                p.source = COMMONS_NAME;
                p.id = "c" + page.optLong("pageid");
                p.file = setName(p.id) + ".jpg";
                p.photographer = meta(m, "Artist");
                Integer n = perAuthor.get(p.photographer);
                if (n != null && n >= 3) continue;
                perAuthor.put(p.photographer, n == null ? 1 : n + 1);
                if (excluded(page.optString("title") + " " + meta(m, "ImageDescription"), exclude)) continue;
                p.description = firstText(meta(m, "ImageDescription").replaceFirst("^500px provided description:\\s*", ""), "");
                if (p.description.length() > 200) p.description = p.description.substring(0, 197) + "\u2026";
                p.license = meta(m, "LicenseShortName");
                String taken = meta(m, "DateTimeOriginal");
                p.taken = taken.matches("\\d{4}[-:]\\d{2}[-:]\\d{2}.*") ? taken.substring(0, 10).replace(':', '-') : "";
                p.page = ii.optString("descriptionurl");
                p.width = w;
                p.height = h;
                try {
                    p.lat = Double.parseDouble(meta(m, "GPSLatitude"));
                    p.lon = Double.parseDouble(meta(m, "GPSLongitude"));
                } catch (NumberFormatException e) {
                    p.lat = p.lon = Double.NaN;
                }
                p.detailed = Double.isNaN(p.lat);   // with coordinates, the place name is asked for on screen
                list.add(p);
                urls.add(url);
            }
            lists.add(list);
            urlLists.add(urls);
        }
        List<Photo> photos = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int round = 0; round < 40; round++) {
            boolean any = false;
            for (int i = 0; i < lists.size(); i++) {
                if (round >= lists.get(i).size()) continue;
                any = true;
                Photo p = lists.get(i).get(round);
                if (!seen.add(p.id)) continue;
                photos.add(p);
                urls.add(urlLists.get(i).get(round));
            }
            if (!any) break;
        }
        return save(c, set, terms, count, photos, urls);
    }

    /** A text field of Commons' extmetadata without its HTML, or "". */
    private static String meta(JSONObject m, String key) {
        JSONObject f = m == null ? null : m.optJSONObject(key);
        String v = f == null ? "" : f.optString("value");
        return android.text.Html.fromHtml(v, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    /** Downloads the photos not on disk yet, up to {@code count}, and writes the set's index. */
    private static boolean save(Context c, String set, String terms, int count, List<Photo> photos, List<String> urls)
            throws Exception {
        File dir = dir(c, set);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder " + dir);
        List<Photo> old = load(c, set);
        JSONArray out = new JSONArray();
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < photos.size() && out.length() < count; i++) {
            Photo p = photos.get(i);
            File f = new File(dir, p.file);
            if (!f.isFile()) {
                try {
                    download(urls.get(i), f);
                } catch (IOException e) {
                    Log.w(TAG, "download " + p.id + ": " + e);
                    continue;
                }
            }
            for (Photo before : old) if (before.id.equals(p.id) && before.detailed) p = before;
            keep.add(p.file);
            out.put(p.toJson());
        }
        if (out.length() == 0) return false;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.getName().endsWith(".jpg") && !keep.contains(f.getName()) && !f.delete()) Log.w(TAG, "kept " + f);
        }
        writeIndex(c, set, new JSONObject().put("terms", terms).put("fetched", System.currentTimeMillis()).put("photos", out));
        return true;
    }

    private static Photo fromSearch(JSONObject o) {
        Photo p = new Photo();
        p.id = o.optString("id");
        p.file = setName(p.id) + ".jpg";
        p.description = firstText(o.optString("description"), o.optString("alt_description"));
        JSONObject user = o.optJSONObject("user");
        p.photographer = user == null ? "" : user.optString("name");
        JSONObject links = o.optJSONObject("links");
        p.page = links == null ? "" : links.optString("html");
        p.width = o.optInt("width");
        p.height = o.optInt("height");
        p.taken = o.optString("created_at").length() >= 10 ? o.optString("created_at").substring(0, 10) : "";
        return p;
    }

    /**
     * Where the photo was taken and with what camera: one request per photo, made the first time its details are
     * shown, then kept in the index. Blocking: call it off the main thread. Returns the photo, filled when it could.
     */
    static Photo details(Context c, String set, Photo p) {
        if (p.detailed || !online(c)) return p;
        if (COMMONS_NAME.equals(p.source)) return placeName(c, set, p);
        // Details come one request per photo: leave room in the hour for the searches.
        if (!hasKey(c) || remaining < 12) return p;
        try {
            JSONObject o = new JSONObject(get(c, API + "/photos/" + URLEncoder.encode(p.id, "UTF-8")));
            JSONObject loc = o.optJSONObject("location");
            if (loc != null) {
                String name = clean(loc.optString("name"));
                if (name.isEmpty()) {
                    List<String> parts = new ArrayList<>();
                    for (String k : new String[]{"city", "country"}) if (!clean(loc.optString(k)).isEmpty()) parts.add(clean(loc.optString(k)));
                    name = String.join(", ", parts);
                }
                p.location = name;
            }
            JSONObject exif = o.optJSONObject("exif");
            if (exif != null) {
                List<String> parts = new ArrayList<>();
                String model = clean(exif.optString("name"));
                if (model.isEmpty()) model = (clean(exif.optString("make")) + " " + clean(exif.optString("model"))).trim();
                if (!model.isEmpty()) parts.add(model);
                if (!clean(exif.optString("focal_length")).isEmpty()) parts.add(clean(exif.optString("focal_length")) + " mm");
                if (!clean(exif.optString("aperture")).isEmpty()) parts.add("f/" + clean(exif.optString("aperture")));
                if (!clean(exif.optString("exposure_time")).isEmpty()) parts.add(clean(exif.optString("exposure_time")) + " s");
                if (exif.optInt("iso") > 0) parts.add("ISO " + exif.optInt("iso"));
                p.camera = String.join(" · ", parts);
            }
            p.description = firstText(o.optString("description"), p.description);
            p.detailed = true;
            JSONObject index = readIndex(c, set);
            JSONArray arr = index.optJSONArray("photos");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                if (p.id.equals(arr.getJSONObject(i).optString("id"))) arr.put(i, p.toJson());
            }
            writeIndex(c, set, index);
        } catch (Exception e) {
            Log.w(TAG, "details " + p.id + ": " + e);
        }
        return p;
    }

    /**
     * The place of a Commons photo from its coordinates, through OpenStreetMap's reverse lookup (one request per
     * photo, kept in the index; their policy allows at most one a second, which showing photos never comes near).
     */
    private static Photo placeName(Context c, String set, Photo p) {
        try {
            String lang = c.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
            JSONObject o = new JSONObject(get(c, NOMINATIM + "?format=jsonv2&zoom=10&lat=" + p.lat + "&lon=" + p.lon
                    + "&accept-language=" + URLEncoder.encode(lang, "UTF-8")));
            JSONObject a = o.optJSONObject("address");
            List<String> parts = new ArrayList<>();
            if (a != null) {
                for (String k : new String[]{"city", "town", "village", "municipality", "county", "state", "country"}) {
                    String v = clean(a.optString(k));
                    if (!v.isEmpty() && !parts.contains(v) && parts.size() < 3) parts.add(v);
                }
            }
            p.location = String.join(", ", parts);
            p.detailed = true;
            rewrite(c, set, p);
        } catch (Exception e) {
            Log.w(TAG, "place " + p.id + ": " + e);
        }
        return p;
    }

    private static void rewrite(Context c, String set, Photo p) throws Exception {
        JSONObject index = readIndex(c, set);
        JSONArray arr = index.optJSONArray("photos");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            if (p.id.equals(arr.getJSONObject(i).optString("id"))) arr.put(i, p.toJson());
        }
        writeIndex(c, set, index);
    }

    /** The API turns JSON null into the text "null" through optString. */
    private static String clean(String s) {
        return s == null || "null".equals(s) ? "" : s.trim();
    }

    private static String firstText(String a, String b) {
        String x = clean(a);
        if (x.isEmpty()) x = clean(b);
        return x.isEmpty() ? "" : Character.toUpperCase(x.charAt(0)) + x.substring(1);
    }

    /** Relative luminance of "#RRGGBB", 0 dark to 1 light; 0 when unreadable. */
    static double luminance(String hex) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) return 0;
        int v = Integer.parseInt(hex.substring(1), 16);
        return (0.2126 * ((v >> 16) & 255) + 0.7152 * ((v >> 8) & 255) + 0.0722 * (v & 255)) / 255.0;
    }

    private static String get(Context c, String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(20_000);
        http.setRequestProperty("User-Agent", AGENT);
        if (url.startsWith(API)) {
            http.setRequestProperty("Authorization", "Client-ID " + c.getString(R.string.unsplash_key));
            http.setRequestProperty("Accept-Version", "v1");
        }
        try {
            int code = http.getResponseCode();
            String left = http.getHeaderField("X-RateLimit-Remaining");
            if (left != null && left.matches("\\d+")) remaining = Integer.parseInt(left);
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = http.getInputStream()) {
                return new String(readAll(in, 2 * 1024 * 1024), StandardCharsets.UTF_8);
            }
        } finally {
            http.disconnect();
        }
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(30_000);
        http.setRequestProperty("User-Agent", AGENT);
        File part = new File(to.getPath() + ".part");
        try {
            if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > 4L * 1024 * 1024) throw new IOException("over 4 MB");
                    out.write(buf, 0, n);
                }
            }
            if (!part.renameTo(to)) throw new IOException("rename " + part);
        } finally {
            http.disconnect();
            if (part.exists() && !part.delete()) Log.w(TAG, "left " + part);
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("response too large");
        }
        return out.toByteArray();
    }

    private static JSONObject readIndex(Context c, String set) {
        File f = new File(dir(c, set), "index.json");
        try {
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static synchronized void writeIndex(Context c, String set, JSONObject index) throws IOException {
        File dir = dir(c, set), tmp = new File(dir, "index.json.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(index.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir, "index.json"))) throw new IOException("rename index");
    }
}
