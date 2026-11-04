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
 * Background photos found on Unsplash by search terms, kept on the TV: files/online/&lt;set&gt;/ holds the JPEGs
 * (1920x1080) and index.json with each photo's credit and details. A set is fetched again when its terms change
 * or it is older than {@link #MAX_AGE_MS}; until then, and without a network, the photos on disk are used.
 * The Unsplash access key comes from local.properties at build time (unsplash.access_key) and is never committed.
 */
final class OnlinePhotos {

    static final class Photo {
        String id = "", file = "", description = "", location = "", photographer = "", camera = "", taken = "",
                page = "";
        int width, height;
        /** Whether location and camera were asked for already (they are fetched one photo at a time). */
        boolean detailed;

        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject().put("id", id).put("file", file).put("description", description)
                    .put("location", location).put("photographer", photographer).put("camera", camera)
                    .put("taken", taken).put("page", page).put("width", width).put("height", height)
                    .put("detailed", detailed);
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
            return p;
        }
    }

    static final long MAX_AGE_MS = 3 * 24 * 3600_000L;
    private static final String API = "https://api.unsplash.com";
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
    static void refresh(Context c, String set, List<String> queries, int count, Runnable done) {
        Context app = c.getApplicationContext();
        if (!hasKey(app) || queries.isEmpty() || !online(app)) return;
        String terms = String.join("|", queries);
        JSONObject index = readIndex(app, set);
        boolean fresh = terms.equals(index.optString("terms"))
                && System.currentTimeMillis() - index.optLong("fetched") < MAX_AGE_MS
                && !load(app, set).isEmpty();
        if (fresh || !RUNNING.add(set)) return;
        NET.execute(() -> {
            try {
                if (fetch(app, set, queries, terms, count) && done != null) new Handler(Looper.getMainLooper()).post(done);
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

    /** Search results interleaved across the terms, light photos last; downloads what is not on disk yet. */
    private static boolean fetch(Context c, String set, List<String> queries, String terms, int count) throws Exception {
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
                (luminance(o.optString("color")) > 0.72 ? light : picked).add(o);
            }
            if (!any) break;
        }
        picked.addAll(light);   // too-bright pictures only when the dark ones run out
        File dir = dir(c, set);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder " + dir);
        List<Photo> old = load(c, set);
        JSONArray out = new JSONArray();
        Set<String> keep = new HashSet<>();
        for (JSONObject o : picked) {
            if (out.length() >= count) break;
            Photo p = fromSearch(o);
            File f = new File(dir, p.file);
            if (!f.isFile()) {
                String raw = o.getJSONObject("urls").getString("raw");
                try {
                    download(raw + (raw.contains("?") ? "&" : "?") + "w=1920&h=1080&fit=crop&crop=entropy&q=78&fm=jpg", f);
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
        // Details come one request per photo: leave room in the hour for the searches.
        if (p.detailed || !hasKey(c) || !online(c) || remaining < 12) return p;
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
        http.setRequestProperty("Authorization", "Client-ID " + c.getString(R.string.unsplash_key));
        http.setRequestProperty("Accept-Version", "v1");
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
