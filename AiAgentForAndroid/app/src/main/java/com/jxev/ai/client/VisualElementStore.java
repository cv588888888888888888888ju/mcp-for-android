package com.jxev.ai.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 视觉元素模板存储（实验性，2026-11-12）。
 *
 * 落盘：{app files}/visual_elements/<name>.png（灰度模板）+ <name>.json（元数据）。
 * 模板是运行时数据 —— 不进 APK、不进仓库；删 App 数据即清空。
 *
 * 归一化口径：PNG 存 0..255 灰度（无符号），JSON 记 mean/std 供排查；
 * 匹配时的「零均值单位化」在 {@link VisualElementMatcher} 里按需做（不落盘，避免浮点精度损失）。
 */
public final class VisualElementStore {
    private static final String TAG = "VisualElementStore";
    private static final String DIR_NAME = "visual_elements";

    /** 默认阈值（百分比）。低于它判 found=false。 */
    public static final int DEFAULT_THRESHOLD = 70;
    /** 模板边长上限：1080x2400 屏幕上再大就不是「元素」而是整屏了，也是 A2 的时限前提。 */
    public static final int MAX_SIDE = 200;
    /** 模板边长下限：再小没有可辨识特征。 */
    public static final int MIN_SIDE = 8;

    private VisualElementStore() {
    }

    /** 一条已注册的视觉元素。 */
    public static final class Element {
        public String name = "";
        public String pkg = "";
        public int threshold = DEFAULT_THRESHOLD;
        public int width;
        public int height;
        /** 灰度均值/标准差（0..255），仅作排查用。 */
        public int mean;
        public int std;
        public long createdAt;
        public int matchCount;
        /** 灰度像素，长度 width*height，取值 0..255。 */
        public int[] gray;
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR_NAME);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 名字即文件名：只允许 [A-Za-z0-9_.-]，1..32 字符，禁止 .. 与路径分隔符。 */
    public static boolean validName(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.length() < 1 || n.length() > 32) return false;
        if (n.contains("..") || n.contains("/") || n.contains("\\")) return false;
        return n.matches("[A-Za-z0-9_.-]+");
    }

    public static File pngFile(Context ctx, String name) {
        return new File(dir(ctx), name + ".png");
    }

    public static File metaFile(Context ctx, String name) {
        return new File(dir(ctx), name + ".json");
    }

    public static boolean exists(Context ctx, String name) {
        return validName(name) && pngFile(ctx, name).isFile() && metaFile(ctx, name).isFile();
    }

    /** 灰度 → ARGB Bitmap（PNG 无损，读回逐像素相等）。 */
    public static Bitmap toBitmap(int[] gray, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int g = gray[i] & 0xFF;
            px[i] = Color.argb(255, g, g, g);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        return bmp;
    }

    /** Bitmap → 灰度（Rec.601 luma）。 */
    public static int[] toGray(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] gray = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000;
        }
        return gray;
    }

    public static void stats(Element e) {
        if (e.gray == null || e.gray.length == 0) return;
        long sum = 0;
        for (int v : e.gray) sum += v;
        double mean = (double) sum / e.gray.length;
        double acc = 0;
        for (int v : e.gray) {
            double d = v - mean;
            acc += d * d;
        }
        e.mean = (int) Math.round(mean);
        e.std = (int) Math.round(Math.sqrt(acc / e.gray.length));
    }

    /**
     * 写盘：PNG + JSON。覆盖同名（重复注册 = 更新）。
     * 返回落盘目录路径；失败抛 IOException。
     */
    public static String save(Context ctx, Element e) throws Exception {
        if (!validName(e.name)) {
            throw new IllegalArgumentException("name 非法（只允许 [A-Za-z0-9_.-]，1..32 字符）");
        }
        if (e.gray == null || e.width <= 0 || e.height <= 0
                || e.gray.length != e.width * e.height) {
            throw new IllegalArgumentException("模板像素数据不完整");
        }
        if (e.width > MAX_SIDE || e.height > MAX_SIDE) {
            throw new IllegalArgumentException("模板超过 " + MAX_SIDE + "x" + MAX_SIDE
                    + "（当前 " + e.width + "x" + e.height + "），请缩小 bbox");
        }
        if (e.width < MIN_SIDE || e.height < MIN_SIDE) {
            throw new IllegalArgumentException("模板小于 " + MIN_SIDE + "x" + MIN_SIDE
                    + "（当前 " + e.width + "x" + e.height + "），请放大 bbox");
        }
        stats(e);
        if (e.createdAt <= 0) e.createdAt = System.currentTimeMillis();

        File d = dir(ctx);
        Bitmap bmp = toBitmap(e.gray, e.width, e.height);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bmp.recycle();
        File png = new File(d, e.name + ".png");
        FileOutputStream pngOut = new FileOutputStream(png);
        try {
            pngOut.write(bos.toByteArray());
        } finally {
            pngOut.close();
        }

        JSONObject meta = new JSONObject();
        meta.put("name", e.name);
        meta.put("package", e.pkg == null ? "" : e.pkg);
        meta.put("threshold", e.threshold);
        meta.put("width", e.width);
        meta.put("height", e.height);
        meta.put("mean", e.mean);
        meta.put("std", e.std);
        meta.put("createdAt", e.createdAt);
        meta.put("matchCount", e.matchCount);
        meta.put("format", "gray8-png");
        File json = new File(d, e.name + ".json");
        FileOutputStream jsonOut = new FileOutputStream(json);
        try {
            jsonOut.write(meta.toString(2).getBytes("UTF-8"));
        } finally {
            jsonOut.close();
        }
        Log.i(TAG, "模板已落盘: " + png.getName() + " " + e.width + "x" + e.height
                + " pkg=" + e.pkg + " threshold=" + e.threshold);
        return d.getAbsolutePath();
    }

    /** 读一条；不存在返回 null。 */
    public static Element load(Context ctx, String name) {
        if (!validName(name) || !exists(ctx, name)) {
            return null;
        }
        try {
            JSONObject meta = new JSONObject(readText(metaFile(ctx, name)));
            byte[] pngBytes = readBytes(pngFile(ctx, name));
            Bitmap bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.length);
            if (bmp == null) {
                Log.w(TAG, "模板 PNG 解码失败: " + name);
                return null;
            }
            Element e = new Element();
            e.name = meta.optString("name", name);
            e.pkg = meta.optString("package", "");
            e.threshold = meta.optInt("threshold", DEFAULT_THRESHOLD);
            e.width = bmp.getWidth();
            e.height = bmp.getHeight();
            e.mean = meta.optInt("mean", 0);
            e.std = meta.optInt("std", 0);
            e.createdAt = meta.optLong("createdAt", 0L);
            e.matchCount = meta.optInt("matchCount", 0);
            e.gray = toGray(bmp);
            bmp.recycle();
            return e;
        } catch (Exception ex) {
            Log.w(TAG, "读取模板失败: " + name, ex);
            return null;
        }
    }

    /** 已注册名字列表（按名字排序）。 */
    public static List<String> list(Context ctx) {
        List<String> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".png") && exists(ctx, n.substring(0, n.length() - 4))) {
                out.add(n.substring(0, n.length() - 4));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 记录一次命中（写回 JSON，best-effort）。 */
    public static void bumpMatchCount(Context ctx, String name) {
        Element e = load(ctx, name);
        if (e == null) return;
        e.matchCount++;
        try {
            save(ctx, e);
        } catch (Exception ignored) {
        }
    }

    public static JSONArray listAsJson(Context ctx) {
        JSONArray arr = new JSONArray();
        for (String n : list(ctx)) {
            Element e = load(ctx, n);
            if (e == null) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("name", e.name);
                o.put("package", e.pkg);
                o.put("threshold", e.threshold);
                o.put("width", e.width);
                o.put("height", e.height);
                o.put("mean", e.mean);
                o.put("std", e.std);
                o.put("createdAt", e.createdAt);
                o.put("matchCount", e.matchCount);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        return arr;
    }

    private static String readText(File f) throws Exception {
        byte[] b = readBytes(f);
        return new String(b, "UTF-8");
    }

    private static byte[] readBytes(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
