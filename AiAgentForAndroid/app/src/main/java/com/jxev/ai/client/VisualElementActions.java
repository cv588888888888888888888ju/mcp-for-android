package com.jxev.ai.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视觉元素动作入口（实验性，2026-11-12）。
 *
 * 动作：element_register / element_find / element_click / element_list。
 * 由 ActionDispatcher 按 element_ 前缀分派（见 ActionDispatcher.dispatch）。
 *
 * 为什么点击不用 AdbActionExecutor.tap()：开源构建没有 assets/adbkey，
 * adb==null 时坐标点击会直接返回 tapped=false（见 AdbActionExecutor.tap 的 coord 分支），
 * 所以这里用无障碍手势（UiTreeProvider.dispatchSwipe 1px / 40ms）落地点击。
 */
public final class VisualElementActions {
    private static final String TAG = "VisualElementActions";
    /** 点击后等待界面稳定的时间（与 AdbActionExecutor.verifyAfterTap 的 350ms 对齐）。 */
    private static final long VERIFY_DELAY_MS = 350L;

    private VisualElementActions() {
    }

    /** ActionDispatcher 唯一入口；永不抛异常（异常转 success=false）。 */
    public static Map<String, Object> handle(Context ctx, String actionType, JSONObject params) {
        JSONObject p = params == null ? new JSONObject() : params;
        try {
            if ("element_register".equals(actionType)) {
                return register(ctx, p);
            }
            if ("element_find".equals(actionType)) {
                return find(ctx, p, false);
            }
            if ("element_click".equals(actionType)) {
                return find(ctx, p, true);
            }
            if ("element_list".equals(actionType)) {
                return list(ctx);
            }
            return error("未知视觉动作: " + actionType);
        } catch (Throwable t) {
            Log.e(TAG, "视觉动作异常: " + actionType, t);
            return error("视觉动作异常: " + t.getMessage());
        }
    }

    // ================== element_register ==================

    private static Map<String, Object> register(Context ctx, JSONObject p) {
        String name = p.optString("name", "").trim();
        if (!VisualElementStore.validName(name)) {
            return error("element_register: name 非法（只允许 [A-Za-z0-9_.-]，1..32 字符）");
        }
        int threshold = p.optInt("threshold", VisualElementStore.DEFAULT_THRESHOLD);
        threshold = Math.max(1, Math.min(100, threshold));

        int[] gray;
        int w;
        int h;
        String templateSource;

        String b64 = p.optString("image_base64", "").trim();
        if (!b64.isEmpty()) {
            int comma = b64.indexOf(',');
            if (b64.startsWith("data:") && comma > 0) {
                b64 = b64.substring(comma + 1);
            }
            byte[] bytes;
            try {
                bytes = Base64.decode(b64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                return error("element_register: image_base64 解码失败: " + e.getMessage());
            }
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) {
                return error("element_register: image_base64 不是可解码的图片");
            }
            w = bmp.getWidth();
            h = bmp.getHeight();
            gray = VisualElementStore.toGray(bmp);
            bmp.recycle();
            templateSource = "image_base64";
        } else if (p.has("bbox") || p.has("x")) {
            int x;
            int y;
            int bw;
            int bh;
            JSONObject bbox = p.optJSONObject("bbox");
            if (bbox != null) {
                x = bbox.optInt("x", -1);
                y = bbox.optInt("y", -1);
                bw = bbox.optInt("w", bbox.optInt("width", -1));
                bh = bbox.optInt("h", bbox.optInt("height", -1));
            } else {
                x = p.optInt("x", -1);
                y = p.optInt("y", -1);
                bw = p.optInt("w", p.optInt("width", -1));
                bh = p.optInt("h", p.optInt("height", -1));
            }
            if (x < 0 || y < 0 || bw <= 0 || bh <= 0) {
                return error("element_register: bbox 需要 x,y,w,h（或 image_base64）");
            }
            Bitmap shot = screenshotWithRetry();
            if (shot == null) {
                return error("element_register: 截屏不可用（无障碍截屏失败）");
            }
            int sw = shot.getWidth();
            int sh = shot.getHeight();
            int cx = Math.max(0, Math.min(sw, x));
            int cy = Math.max(0, Math.min(sh, y));
            int cw = Math.min(bw, sw - cx);
            int ch = Math.min(bh, sh - cy);
            if (cw <= 0 || ch <= 0) {
                shot.recycle();
                return error("element_register: bbox 越界（屏幕 " + sw + "x" + sh + "）");
            }
            Bitmap crop = Bitmap.createBitmap(shot, cx, cy, cw, ch);
            gray = VisualElementStore.toGray(crop);
            w = cw;
            h = ch;
            crop.recycle();
            shot.recycle();
            templateSource = "bbox";
        } else {
            return error("element_register: 需要 image_base64 或 bbox(x,y,w,h)");
        }

        String pkg = p.optString("package", "").trim();
        if (pkg.isEmpty()) {
            UiTreeProvider.dump();
            pkg = UiTreeProvider.currentPackage();
        }

        VisualElementStore.Element e = new VisualElementStore.Element();
        e.name = name;
        e.pkg = pkg;
        e.threshold = threshold;
        e.width = w;
        e.height = h;
        e.gray = gray;
        try {
            String path = VisualElementStore.save(ctx, e);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("source", "visual");
            out.put("name", e.name);
            out.put("package", e.pkg);
            out.put("threshold", e.threshold);
            out.put("width", e.width);
            out.put("height", e.height);
            out.put("mean", e.mean);
            out.put("std", e.std);
            out.put("templateSource", templateSource);
            out.put("dir", path);
            out.put("message", "模板已注册: " + e.name + "（" + e.width + "x" + e.height
                    + "，pkg=" + e.pkg + "，阈值=" + e.threshold + "）");
            return out;
        } catch (Exception ex) {
            return error("element_register: 落盘失败: " + ex.getMessage());
        }
    }

    // ================== element_find / element_click ==================

    private static Map<String, Object> find(Context ctx, JSONObject p, boolean doClick) {
        long t0 = System.currentTimeMillis();
        String name = p.optString("name", "").trim();
        if (!VisualElementStore.validName(name)) {
            return error("name 非法（只允许 [A-Za-z0-9_.-]，1..32 字符）");
        }
        VisualElementStore.Element e = VisualElementStore.load(ctx, name);
        if (e == null) {
            return error("未注册的视觉元素: " + name + "（先 element_register）");
        }

        long tShot = System.currentTimeMillis();
        Bitmap shot = screenshotWithRetry();
        long shotMs = System.currentTimeMillis() - tShot;
        if (shot == null) {
            return error("截屏不可用（无障碍截屏失败）");
        }

        VisualElementMatcher.Result m =
                VisualElementMatcher.match(shot, e.gray, e.width, e.height, e.threshold);
        shot.recycle();

        // 前台包名：dump 一次刷新 lastPackage（7-20ms，AX 通道）
        UiTreeProvider.dump();
        String curPkg = UiTreeProvider.currentPackage();
        boolean pkgMatch = e.pkg.isEmpty() || curPkg.isEmpty() || e.pkg.equals(curPkg);
        boolean found = m.found && pkgMatch;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("source", "visual");
        out.put("name", e.name);
        out.put("found", found);
        out.put("score", m.score);
        out.put("score2", m.score2);
        out.put("ambiguous", m.ambiguous);
        out.put("threshold", e.threshold);
        out.put("x", m.x);
        out.put("y", m.y);
        out.put("width", m.width);
        out.put("height", m.height);
        out.put("centerX", m.centerX());
        out.put("centerY", m.centerY());
        out.put("candidates", m.candidates);
        out.put("package", curPkg);
        out.put("registeredPackage", e.pkg);
        out.put("packageMatch", pkgMatch);
        out.put("coarseFactor", m.coarseFactor);
        out.put("matchMs", m.latencyMs);
        out.put("screenshotMs", shotMs);
        out.put("totalMs", System.currentTimeMillis() - t0);

        String note = m.note;
        if (note == null || note.isEmpty()) {
            if (!m.found) {
                note = "分数低于阈值（" + m.score + " < " + e.threshold + "）";
            } else if (!pkgMatch) {
                note = "前台应用不是注册时的应用（" + curPkg + " != " + e.pkg + "）";
            } else {
                note = "命中";
            }
        }
        out.put("note", note);

        if (!doClick) {
            return out;
        }

        // ---- 点击 ----
        if (!found) {
            out.put("actionable", false);
            out.put("tapped", false);
            out.put("verified", false);
            out.put("target", e.name);
            out.put("note", note + "；未点击");
            out.put("totalMs", System.currentTimeMillis() - t0);
            return out;
        }
        int cx = m.centerX();
        int cy = m.centerY();
        String before = UiTreeProvider.snapshot();
        // 1px/40ms 手势 = 点击（零长度 Path 在手势 API 里不可靠）
        boolean tapped = UiTreeProvider.dispatchSwipe(cx, cy, cx + 1, cy, 40);
        sleep(VERIFY_DELAY_MS);
        String after = UiTreeProvider.snapshot();
        boolean verified = before != null && after != null && !before.equals(after);
        out.put("actionable", true);
        out.put("tapped", tapped);
        out.put("verified", verified);
        out.put("target", e.name);
        if (!tapped) {
            out.put("note", "已命中但手势派发失败（无障碍服务未就绪？）");
        } else if (before == null || after == null) {
            out.put("note", "已点击；界面指纹不可用，verified=false");
        }
        out.put("totalMs", System.currentTimeMillis() - t0);
        VisualElementStore.bumpMatchCount(ctx, e.name);
        return out;
    }

    // ================== element_list ==================

    private static Map<String, Object> list(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("source", "visual");
        out.put("elements", VisualElementStore.listAsJson(ctx));
        out.put("count", VisualElementStore.list(ctx).size());
        out.put("dir", VisualElementStore.dir(ctx).getAbsolutePath());
        return out;
    }

    // ================== 工具 ==================

    private static Map<String, Object> error(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("found", false);
        out.put("error", msg);
        return out;
    }

    /**
     * 无障碍 takeScreenshot 有约 1s 的框架级节流：连续调用（find 紧跟 click、连跑三次 find）
     * 第 2 次常直接失败。这里重试 3 次、间隔 550ms，覆盖 ~1.1s 窗口。
     */
    private static Bitmap screenshotWithRetry() {
        for (int i = 0; i < 3; i++) {
            Bitmap b = UiTreeProvider.screenshot(5000);
            if (b != null) {
                return b;
            }
            if (i < 2) {
                sleep(550L);
            }
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
