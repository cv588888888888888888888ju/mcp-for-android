package com.jxev.ai.client;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 视觉模板匹配器（实验性，2026-11-12）。
 *
 * 算法：灰度 + 零均值归一化互相关（NCC），分母用积分图（O(1) 求窗口和/平方和），
 * 搜索走 8x → 4x → 2x → 1x 金字塔：粗层全屏扫，细层只在上一层峰值 ±3px 内精修。
 *
 * 为什么不是纯积分图：积分图只能给出窗口的 sum/sumSq（NCC 分母），
 * 分子 sum(T*I) 仍是相关运算，所以粗层全扫 + 细层局部精修是让 1080x2400 单帧进 1s 内的关键。
 */
public final class VisualElementMatcher {
    private static final String TAG = "VisualElementMatcher";

    /** 粗层保留的峰数（beam）。 */
    private static final int TOP_K = 3;
    /** 细层精修窗口半径（该层像素）。 */
    private static final int REFINE_RADIUS = 3;
    /** 粗层进候选的最低相关度（低于它连候选都不算）。 */
    private static final double MIN_CANDIDATE_NCC = 0.40;
    /** 第二候选与第一候选的最小分差（百分点）：小于它判「无法确定」，不返回 found。 */
    private static final int AMBIGUITY_MARGIN = 5;

    private VisualElementMatcher() {
    }

    public static final class Result {
        public boolean found;
        /** 0..100，四舍五入的 NCC 百分比。 */
        public int score;
        /** 空间上远离最佳位置的第二候选分数（0..100）；没有则 0。 */
        public int score2;
        /** true = 过阈值但第二候选太接近，故意不判 found（避免点错元素）。 */
        public boolean ambiguous;
        /** 粗层里 >= 阈值的候选位置个数（S3 场景：同款图标出现多处）。 */
        public int candidates;
        public int x;
        public int y;
        public int width;
        public int height;
        public long latencyMs;
        /** 实际用的最粗层因子（排查用）。 */
        public int coarseFactor = 1;
        public String note = "";

        public int centerX() {
            return x + width / 2;
        }

        public int centerY() {
            return y + height / 2;
        }
    }

    /**
     * 在整屏里找模板。
     *
     * @param screen    当前屏幕（ARGB_8888）
     * @param tmplGray  模板灰度像素（0..255）
     * @param threshold 阈值百分比（0..100），>= 它才算 found
     */
    public static Result match(Bitmap screen, int[] tmplGray, int tw, int th, int threshold) {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        if (screen == null) {
            r.note = "屏幕截图为空";
            return r;
        }
        if (tmplGray == null || tw <= 0 || th <= 0 || tmplGray.length != tw * th) {
            r.note = "模板像素不合法";
            return r;
        }
        int sw = screen.getWidth();
        int sh = screen.getHeight();
        if (tw > sw || th > sh) {
            r.note = "模板比屏幕大（" + tw + "x" + th + " vs " + sw + "x" + sh + "）";
            return r;
        }
        // 模板纹理太弱 → NCC 不可靠，直接判不可用（避免把纯色块匹配到任意纯色区）
        double[] tStat = templateStat(tmplGray, tw * th);
        if (tStat[1] < 2.0) {
            r.note = "模板缺乏纹理（std=" + String.format("%.1f", tStat[1]) + "），无法可靠匹配";
            r.latencyMs = System.currentTimeMillis() - t0;
            return r;
        }

        int[] gray = VisualElementStore.toGray(screen);

        // 金字塔层因子：8 → 4 → 2 → 1（模板在粗层不得小于 6px，否则退到更细的层）
        List<Integer> levels = new ArrayList<>();
        for (int f = 8; f >= 1; f /= 2) {
            if (tw / f >= 6 && th / f >= 6) {
                levels.add(f);
            }
        }
        if (levels.isEmpty()) {
            levels.add(1);
        }
        int coarsest = levels.get(0);
        r.coarseFactor = coarsest;

        double thrNcc = Math.max(0.0, Math.min(100, threshold)) / 100.0;

        // ---------- 粗层全扫 ----------
        int cw = sw / coarsest;
        int ch = sh / coarsest;
        int ctw = tw / coarsest;
        int cth = th / coarsest;
        int[] cImg = (coarsest == 1) ? gray : downscale(gray, sw, sh, coarsest);
        int[] cTmpl = (coarsest == 1) ? tmplGray : downscale(tmplGray, tw, th, coarsest);
        double[] cNorm2 = new double[1];
        float[] cT = normalize(cTmpl, ctw * cth, cNorm2);
        if (cNorm2[0] <= 1e-6) {
            r.note = "模板粗层归一化失败";
            r.latencyMs = System.currentTimeMillis() - t0;
            return r;
        }

        long[] iiC = integral(cImg, cw, ch);
        long[] iiC2 = integralSq(cImg, cw, ch);
        List<double[]> hits = new ArrayList<>(); // {score, x, y}
        int strideC = cw + 1;
        int nC = ctw * cth;
        int maxX = cw - ctw;
        int maxY = ch - cth;
        for (int y = 0; y <= maxY; y++) {
            for (int x = 0; x <= maxX; x++) {
                long s = rect(iiC, strideC, x, y, ctw, cth);
                long s2 = rect(iiC2, strideC, x, y, ctw, cth);
                double var = s2 - (double) s * s / nC;
                if (var <= nC) {
                    continue; // 极平窗口（std<=1）跳过：纯色区域没有可辨识特征
                }
                double num = 0;
                for (int v = 0; v < cth; v++) {
                    int ib = (y + v) * cw + x;
                    int tb = v * ctw;
                    for (int u = 0; u < ctw; u++) {
                        num += cT[tb + u] * cImg[ib + u];
                    }
                }
                double ncc = num / Math.sqrt(cNorm2[0] * var);
                if (ncc >= thrNcc) {
                    r.candidates++;
                }
                if (ncc >= MIN_CANDIDATE_NCC) {
                    hits.add(new double[]{ncc, x, y});
                }
            }
        }
        if (hits.isEmpty()) {
            r.score = 0;
            r.found = false;
            r.note = "粗层未找到候选（最高相关度 < " + MIN_CANDIDATE_NCC + "）";
            r.latencyMs = System.currentTimeMillis() - t0;
            return r;
        }
        // 按分数降序 + 非极大抑制（同款图标多处时保留分离的峰）
        hits.sort((a, b) -> Double.compare(b[0], a[0]));
        int sep = Math.max(4, Math.min(ctw, cth) / 2);
        List<double[]> peaks = new ArrayList<>();
        for (double[] h : hits) {
            boolean near = false;
            for (double[] p : peaks) {
                if (Math.abs(p[1] - h[1]) < sep && Math.abs(p[2] - h[2]) < sep) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                peaks.add(h);
                if (peaks.size() >= TOP_K) break;
            }
        }

        // ---------- 细层精修（beam → 每层留最优） ----------
        List<double[]> cur = peaks; // {score, x, y} at factor levels.get(i)
        int curFactor = coarsest;
        int bestX = (int) Math.round(cur.get(0)[1] * coarsest);
        int bestY = (int) Math.round(cur.get(0)[2] * coarsest);
        double bestNcc = cur.get(0)[0];
        for (int li = 1; li < levels.size(); li++) {
            int f = levels.get(li);
            double ratio = (double) curFactor / f;
            int[] imgF = (f == 1) ? gray : downscale(gray, sw, sh, f);
            int twF = tw / f;
            int thF = th / f;
            int[] tF = (f == 1) ? tmplGray : downscale(tmplGray, tw, th, f);
            double[] norm = new double[1];
            float[] tFn = normalize(tF, twF * thF, norm);
            if (norm[0] <= 1e-6) break;
            int w = sw / f;
            int h = sh / f;
            long[] ii = integral(imgF, w, h);
            long[] ii2 = integralSq(imgF, w, h);
            int stride = w + 1;
            int n = twF * thF;
            List<double[]> next = new ArrayList<>();
            for (double[] p : cur) {
                int px = (int) Math.round(p[1] * ratio);
                int py = (int) Math.round(p[2] * ratio);
                int x0 = Math.max(0, px - REFINE_RADIUS);
                int y0 = Math.max(0, py - REFINE_RADIUS);
                int x1 = Math.min(w - twF, px + REFINE_RADIUS);
                int y1 = Math.min(h - thF, py + REFINE_RADIUS);
                double localBest = -2;
                int lbx = px;
                int lby = py;
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        long s = rect(ii, stride, x, y, twF, thF);
                        long s2 = rect(ii2, stride, x, y, twF, thF);
                        double var = s2 - (double) s * s / n;
                        if (var <= 1e-6) continue;
                        double num = 0;
                        for (int v = 0; v < thF; v++) {
                            int ib = (y + v) * w + x;
                            int tb = v * twF;
                            for (int u = 0; u < twF; u++) {
                                num += tFn[tb + u] * imgF[ib + u];
                            }
                        }
                        double ncc = num / Math.sqrt(norm[0] * var);
                        if (ncc > localBest) {
                            localBest = ncc;
                            lbx = x;
                            lby = y;
                        }
                    }
                }
                next.add(new double[]{localBest, lbx, lby});
            }
            next.sort((a, b) -> Double.compare(b[0], a[0]));
            cur = next;
            curFactor = f;
            bestNcc = cur.get(0)[0];
            bestX = (int) Math.round(cur.get(0)[1]);
            bestY = (int) Math.round(cur.get(0)[2]);
        }

        r.score = (int) Math.round(Math.max(0.0, bestNcc) * 100);
        r.x = Math.max(0, Math.min(sw - tw, bestX));
        r.y = Math.max(0, Math.min(sh - th, bestY));
        r.width = tw;
        r.height = th;

        // 歧义检查：第二候选在空间上离最佳位置足够远、分数又咬得很近时，
        // 说明这屏上不止一个「像」的元素（实测 88x88 图标：蓝牙 vs 辅助功能 85 vs 85），
        // 此时宁可不判 found，也不要按高分乱点。
        int secondScore = 0;
        double minSep = Math.max(tw, th);
        for (int i = 1; i < cur.size(); i++) {
            double[] q = cur.get(i);
            if (Math.abs(q[1] - bestX) >= minSep || Math.abs(q[2] - bestY) >= minSep) {
                secondScore = (int) Math.round(Math.max(0.0, q[0]) * 100);
                break;
            }
        }
        r.score2 = secondScore;
        boolean passThreshold = r.score >= Math.max(0, Math.min(100, threshold));
        if (passThreshold && secondScore > 0 && r.score - secondScore < AMBIGUITY_MARGIN) {
            r.found = false;
            r.ambiguous = true;
            r.note = "多个候选分数接近（" + r.score + " vs " + r.score2 + "），无法确定";
        } else {
            r.found = passThreshold;
        }
        r.latencyMs = System.currentTimeMillis() - t0;
        Log.i(TAG, "match: score=" + r.score + " @" + r.x + "," + r.y
                + " candidates=" + r.candidates + " " + r.latencyMs + "ms");
        return r;
    }

    // ================== 内部工具 ==================

    private static double[] templateStat(int[] gray, int n) {
        double mean = 0;
        for (int v : gray) mean += v;
        mean /= n;
        double acc = 0;
        for (int v : gray) {
            double d = v - mean;
            acc += d * d;
        }
        return new double[]{mean, Math.sqrt(acc / n)};
    }

    /** 零均值单位化前的准备：返回零均值 float 模板，norm2 = Σt²。 */
    private static float[] normalize(int[] t, int n, double[] outNorm2) {
        double mean = 0;
        for (int i = 0; i < n; i++) mean += t[i];
        mean /= n;
        float[] out = new float[n];
        double s2 = 0;
        for (int i = 0; i < n; i++) {
            double d = t[i] - mean;
            out[i] = (float) d;
            s2 += d * d;
        }
        outNorm2[0] = s2;
        return out;
    }

    /** 整数倍盒式降采样（floor）。 */
    private static int[] downscale(int[] src, int w, int h, int f) {
        if (f <= 1) return src;
        int ow = w / f;
        int oh = h / f;
        int[] out = new int[ow * oh];
        int area = f * f;
        for (int y = 0; y < oh; y++) {
            int sy = y * f;
            for (int x = 0; x < ow; x++) {
                int sx = x * f;
                int sum = 0;
                for (int dy = 0; dy < f; dy++) {
                    int base = (sy + dy) * w + sx;
                    for (int dx = 0; dx < f; dx++) {
                        sum += src[base + dx];
                    }
                }
                out[y * ow + x] = sum / area;
            }
        }
        return out;
    }

    /** 积分图，stride = w+1。 */
    private static long[] integral(int[] img, int w, int h) {
        int stride = w + 1;
        long[] ii = new long[stride * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            int src = y * w;
            int dst = (y + 1) * stride;
            int up = y * stride;
            for (int x = 0; x < w; x++) {
                rowSum += img[src + x];
                ii[dst + x + 1] = ii[up + x + 1] + rowSum;
            }
        }
        return ii;
    }

    /** 平方积分图。 */
    private static long[] integralSq(int[] img, int w, int h) {
        int stride = w + 1;
        long[] ii = new long[stride * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            int src = y * w;
            int dst = (y + 1) * stride;
            int up = y * stride;
            for (int x = 0; x < w; x++) {
                int v = img[src + x];
                rowSum += (long) v * v;
                ii[dst + x + 1] = ii[up + x + 1] + rowSum;
            }
        }
        return ii;
    }

    private static long rect(long[] ii, int stride, int x, int y, int w, int h) {
        int x2 = x + w;
        int y2 = y + h;
        return ii[y2 * stride + x2] - ii[y * stride + x2] - ii[y2 * stride + x] + ii[y * stride + x];
    }
}
