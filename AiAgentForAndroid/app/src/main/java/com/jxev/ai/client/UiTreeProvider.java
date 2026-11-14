package com.jxev.ai.client;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 进程内无障碍树读取（2026-11-12 新增）。
 *
 * 背景：原实现走 `adb execShell("uiautomator dump")`，实测单次 2.77s（Pixel 系 2-3s 量级），
 * 且依赖 adb 通道、WebView/Compose 文本经常抓不到。这里改为直接读
 * AccessibilityService 的节点树，延迟降到几十毫秒，且不依赖 adb。
 *
 * 约束：需要 KeepAliveAccessibilityService 处于「已启用 + canRetrieveWindowContent=true」。
 * 未就绪时所有调用方必须回退到 uiautomator dump（见 AdbActionExecutor）。
 */
public final class UiTreeProvider {
    private static final String TAG = "UiTreeProvider";
    private static final int MAX_NODES = 2000;
    private static final int MAX_DEPTH = 40;

    private static volatile AccessibilityService service;
    /** 最近一次 dump 的包名（无 adb 通道时给 get_current_app 用）。 */
    private static volatile String lastPackage = "";
    /** 最近一次滚动探测结果（行为判据；结构信号 fwd 不可靠，见 docs/INTERFACE.md §10）。 */
    private static volatile ScrollProbe lastProbe;
    /** 探测时的前台包名：换应用后旧结论作废。 */
    private static volatile String lastProbePackage = "";

    private UiTreeProvider() {
    }

    public static void attach(AccessibilityService s) {
        service = s;
        Log.i(TAG, "无障碍树已接入");
    }

    public static void detach(AccessibilityService s) {
        if (service == s) {
            service = null;
            Log.i(TAG, "无障碍树已断开");
        }
    }

    /** 服务是否可用（已连接且能读窗口内容）。 */
    public static boolean isReady() {
        AccessibilityService s = service;
        if (s == null) {
            return false;
        }
        try {
            return s.getRootInActiveWindow() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 序列化当前界面树。返回 null 表示不可用。 */
    public static JSONObject dump() {
        AccessibilityService s = service;
        if (s == null) {
            return null;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = s.getRootInActiveWindow();
            if (root == null) {
                root = focusedWindowRoot(s);
            }
            if (root == null) {
                return null;
            }
            int[] budget = new int[]{MAX_NODES};
            JSONObject out = new JSONObject();
            out.put("source", "ax");
            CharSequence pkg = root.getPackageName();
            lastPackage = pkg == null ? "" : pkg.toString();
            out.put("package", lastPackage);
            out.put("nodes", serialize(root, 0, budget));
            out.put("truncated", budget[0] <= 0);
            boolean[] sc = new boolean[3];
            scanScroll(out.optJSONArray("nodes"), sc);
            out.put("scrollable", sc[0]);
            out.put("canScrollForward", sc[1]);
            out.put("canScrollBackward", sc[2]);
            // canScrollFurther：结构信号只作剪枝，真正判据是「滚一次界面动没动」（scrollProbe）。
            // 同页探测过就用探测结论，否则退回结构信号并标注来源。
            ScrollProbe lp = lastProbe;
            boolean probeFresh = lp != null && lastProbePackage.equals(lastPackage);
            out.put("canScrollFurther", probeFresh ? lp.moved : sc[1]);
            out.put("canScrollFurtherSource", probeFresh ? "probe" : "structure");
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "dump 失败: " + t);
            return null;
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
    }

    private static AccessibilityNodeInfo focusedWindowRoot(AccessibilityService s) {
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                if (w.isFocused() || w.isActive()) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) {
                        return r;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONArray serialize(AccessibilityNodeInfo node, int depth, int[] budget) {
        JSONArray arr = new JSONArray();
        if (node == null || depth > MAX_DEPTH || budget[0] <= 0) {
            return arr;
        }
        budget[0]--;
        try {
            JSONObject o = new JSONObject();
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            CharSequence cls = node.getClassName();
            String rid = node.getViewIdResourceName();
            if (text != null && text.length() > 0) o.put("t", text.toString());
            if (desc != null && desc.length() > 0) o.put("d", desc.toString());
            if (cls != null) o.put("c", cls.toString());
            if (rid != null && !rid.isEmpty()) o.put("id", rid);
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            JSONArray b = new JSONArray();
            b.put(r.left).put(r.top).put(r.right).put(r.bottom);
            o.put("b", b);
            if (node.isClickable()) o.put("clk", true);
            if (node.isScrollable()) {
                o.put("scr", true);
                boolean fwd = false;
                boolean bwd = false;
                for (AccessibilityNodeInfo.AccessibilityAction a : node.getActionList()) {
                    if (a.getId() == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) fwd = true;
                    if (a.getId() == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) bwd = true;
                }
                o.put("fwd", fwd);
                o.put("bwd", bwd);
            }
            if (node.isEditable()) o.put("edit", true);
            if (!node.isEnabled()) o.put("dis", true);
            if (!node.isVisibleToUser()) o.put("invis", true);
            int n = node.getChildCount();
            if (n > 0) {
                JSONArray kids = new JSONArray();
                for (int i = 0; i < n; i++) {
                    AccessibilityNodeInfo c = node.getChild(i);
                    if (c == null) continue;
                    JSONArray sub = serialize(c, depth + 1, budget);
                    for (int j = 0; j < sub.length(); j++) {
                        kids.put(sub.opt(j));
                    }
                    c.recycle();
                    if (budget[0] <= 0) break;
                }
                if (kids.length() > 0) o.put("kids", kids);
            }
            arr.put(o);
        } catch (Throwable t) {
            Log.w(TAG, "serialize 节点失败: " + t);
        }
        return arr;
    }

    /** 用于点击后验证界面是否变化：取所有文本的轻量指纹。 */
    public static String snapshot() {
        JSONObject d = dump();
        if (d == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        collectText(d.optJSONArray("nodes"), sb);
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    /**
     * 滚动探测用的几何指纹：文本 + 每个节点的 bounds.top。
     *
     * 为什么不能只用 snapshot()：列表滚动时若所有条目仍在树里（RecyclerView 预加载），
     * 文本集合完全不变，文本指纹会误判 moved=false（实测 2026-11-12：我的设备页连滚 4 次，
     * 文本指纹每次相同，截图却每次都在动）。bounds 一定跟着滚动变。
     */
    public static String snapshotGeom() {
        JSONObject d = dump();
        if (d == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        collectTextAndBounds(d.optJSONArray("nodes"), sb);
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    private static void collectTextAndBounds(JSONArray nodes, StringBuilder sb) {
        if (nodes == null) return;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject o = nodes.optJSONObject(i);
            if (o == null) continue;
            if (o.has("t")) sb.append(o.optString("t"));
            if (o.has("d")) sb.append(o.optString("d"));
            JSONArray b = o.optJSONArray("b");
            if (b != null) sb.append('@').append(b.optInt(1)).append(',');
            sb.append('|');
            collectTextAndBounds(o.optJSONArray("kids"), sb);
        }
    }

    private static void collectText(JSONArray nodes, StringBuilder sb) {
        if (nodes == null) return;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject o = nodes.optJSONObject(i);
            if (o == null) continue;
            if (o.has("t")) sb.append(o.optString("t")).append('|');
            if (o.has("d")) sb.append(o.optString("d")).append('|');
            collectText(o.optJSONArray("kids"), sb);
        }
    }

    /**
     * 滚动探测结果（行为判据，2026-11-12 新增）。
     *
     * 为什么不能只看结构信号：RecyclerView 到底后常仍保留 ACTION_SCROLL_FORWARD，
     * 而且 AccessibilityNodeInfo 根本没有 scrollX/scrollY，所以「还能不能滚」只能靠
     * 「滚一次 + 前后界面指纹比对」这个行为判据。
     */
    public static final class ScrollProbe {
        public final boolean moved;
        public final boolean canForward;
        public final boolean canBackward;
        public final String before;
        public final String after;
        public final long latencyMs;
        /** 从滑完到指纹连续两次一致（界面停稳）所花的时间；长截图拼接要靠它避免拍到动画中间帧。 */
        public final long settleMs;
        public final String how;

        ScrollProbe(boolean moved, boolean canForward, boolean canBackward,
                    String before, String after, long latencyMs, long settleMs, String how) {
            this.moved = moved;
            this.canForward = canForward;
            this.canBackward = canBackward;
            this.before = before;
            this.after = after;
            this.latencyMs = latencyMs;
            this.settleMs = settleMs;
            this.how = how;
        }
    }

    public static ScrollProbe lastProbe() {
        return lastProbe;
    }

    /**
     * 滚一次并判断界面是否真的动了。forward=true 向上滑（看后面的内容）。
     * 只用于实验性长截图/滚动定位，不要塞进 click 主路径。
     */
    public static ScrollProbe scrollProbe(boolean forward) {
        long t0 = System.currentTimeMillis();
        String before = snapshotGeom();
        ScrollState st = scrollState();
        AccessibilityService s = service;
        boolean dispatched = false;
        if (s != null) {
            try {
                android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
                int cx = dm.widthPixels / 2;
                int yTop = (int) (dm.heightPixels * 0.30);
                int yBottom = (int) (dm.heightPixels * 0.70);
                // 700ms 慢滑：400ms 会带出 fling，单次能滚 2000+ px，相邻帧只剩一百多行重叠，
                // 拼接容易失败（实测 2026-11-12）。慢滑让单次位移稳定落在屏幕高度的 30~40%。
                dispatched = dispatchSwipe(cx, forward ? yBottom : yTop, cx, forward ? yTop : yBottom, 700);
            } catch (Throwable t) {
                Log.w(TAG, "scrollProbe 手势失败", t);
            }
        }
        // 等界面停稳：连续两次指纹一致才算停（最多 2s）。滚动动画中间帧会让拼接错位。
        long settleStart = System.currentTimeMillis();
        sleepQuietly(300);
        String after = snapshotGeom();
        long deadline = settleStart + 2000;
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(120);
            String now = snapshotGeom();
            if (now != null && now.equals(after)) break;
            after = now;
        }
        long settleMs = System.currentTimeMillis() - settleStart;
        boolean moved = before != null && after != null && !before.equals(after);
        ScrollProbe p = new ScrollProbe(moved, st.canForward, st.canBackward,
                before, after, System.currentTimeMillis() - t0, settleMs, dispatched ? "gesture" : "none");
        lastProbe = p;
        lastProbePackage = lastPackage;
        Log.i(TAG, "scrollProbe: moved=" + moved + " fwd=" + st.canForward
                + " settle=" + settleMs + "ms total=" + p.latencyMs + "ms");
        return p;
    }

    /** 页面滚动状态：能不能滚、还能不能继续前/后滚（无障碍 action 列表只说明方向可用）。 */
    public static final class ScrollState {
        public final boolean scrollable;
        public final boolean canForward;
        public final boolean canBackward;

        ScrollState(boolean scrollable, boolean canForward, boolean canBackward) {
            this.scrollable = scrollable;
            this.canForward = canForward;
            this.canBackward = canBackward;
        }
    }

    public static ScrollState scrollState() {
        JSONObject d = dump();
        if (d == null) {
            return new ScrollState(false, false, false);
        }
        boolean[] sc = new boolean[3];
        scanScroll(d.optJSONArray("nodes"), sc);
        return new ScrollState(sc[0], sc[1], sc[2]);
    }

    private static void scanScroll(JSONArray nodes, boolean[] acc) {
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject o = nodes.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (o.optBoolean("scr", false)) {
                acc[0] = true;
                if (o.optBoolean("fwd", false)) acc[1] = true;
                if (o.optBoolean("bwd", false)) acc[2] = true;
            }
            scanScroll(o.optJSONArray("kids"), acc);
        }
    }

    /** 一次匹配结果。 */
    public static final class Match {
        public final String text;
        public final String desc;
        public final String id;
        public final String cls;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;
        public final boolean clickable;
        /** 节点自身或最近的可点击祖先能接手势（ACTION_CLICK 就是打到祖先上的）。 */
        public final boolean actionable;
        /** 节点自身或最近的可滚动祖先可滚动（元素在不在一个能滚的列表/容器里）。 */
        public final boolean scrollable;
        public final boolean exact;
        public final String source;

        Match(String text, String desc, String id, String cls, int l, int t, int r, int b,
              boolean clickable, boolean exact, String source) {
            this(text, desc, id, cls, l, t, r, b, clickable, clickable, false, exact, source);
        }

        Match(String text, String desc, String id, String cls, int l, int t, int r, int b,
              boolean clickable, boolean actionable, boolean exact, String source) {
            this(text, desc, id, cls, l, t, r, b, clickable, actionable, false, exact, source);
        }

        Match(String text, String desc, String id, String cls, int l, int t, int r, int b,
              boolean clickable, boolean actionable, boolean scrollable, boolean exact, String source) {
            this.text = text == null ? "" : text;
            this.desc = desc == null ? "" : desc;
            this.id = id == null ? "" : id;
            this.cls = cls == null ? "" : cls;
            this.left = l;
            this.top = t;
            this.right = r;
            this.bottom = b;
            this.clickable = clickable;
            this.actionable = actionable;
            this.scrollable = scrollable;
            this.exact = exact;
            this.source = source;
        }

        public int centerX() {
            return (left + right) / 2;
        }

        public int centerY() {
            return (top + bottom) / 2;
        }

        public boolean hasArea() {
            return right > left && bottom > top;
        }

        public String label() {
            if (!text.isEmpty()) return text;
            if (!desc.isEmpty()) return desc;
            return id;
        }
    }

    /**
     * 在无障碍树里按 id/text/desc 找元素。
     * 匹配顺序：精确 → 包含；同分优先 clickable，其次面积小的（更具体）。
     */
    public static Match find(String id, String text, String desc) {
        if (service == null || (id.isEmpty() && text.isEmpty() && desc.isEmpty())) {
            return null;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null) return null;
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            int[] budget = new int[]{MAX_NODES};
            collectNodes(root, nodes, 0, budget);
            Match bestExact = null;
            Match bestContains = null;
            for (AccessibilityNodeInfo n : nodes) {
                try {
                    String nt = n.getText() == null ? "" : n.getText().toString();
                    String nd = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
                    String nid = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
                    boolean exact = true;
                    boolean hit = true;
                    if (!id.isEmpty()) {
                        if (!nid.equals(id)) {
                            hit = nid.contains(id);
                            exact = exact && nid.equals(id);
                        }
                    }
                    if (hit && !text.isEmpty()) {
                        boolean eq = nt.equals(text);
                        boolean ct = nt.contains(text);
                        if (!eq && !ct) {
                            hit = false;
                        }
                        exact = exact && eq;
                    }
                    if (hit && !desc.isEmpty()) {
                        boolean eq = nd.equals(desc);
                        boolean cd = nd.contains(desc);
                        if (!eq && !cd) {
                            hit = false;
                        }
                        exact = exact && eq;
                    }
                    if (!hit) continue;
                    if (!n.isVisibleToUser()) continue;
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    if (r.right <= r.left || r.bottom <= r.top) continue;
                    Match m = new Match(nt, nd, nid,
                            n.getClassName() == null ? "" : n.getClassName().toString(),
                            r.left, r.top, r.right, r.bottom, n.isClickable(),
                            hasClickableAncestor(n, 3), hasScrollableAncestor(n, 3), exact, "ax");
                    if (exact) {
                        if (bestExact == null || better(m, bestExact)) bestExact = m;
                    } else {
                        if (bestContains == null || better(m, bestContains)) bestContains = m;
                    }
                } catch (Throwable ignored) {
                }
            }
            for (AccessibilityNodeInfo n : nodes) {
                n.recycle();
            }
            return bestExact != null ? bestExact : bestContains;
        } catch (Throwable t) {
            Log.w(TAG, "find 失败: " + t);
            return null;
        }
    }

    /**
     * 直接用无障碍动作点击（不注入手势，因此不需要 adb/adbkey）。
     *
     * 顺序：命中节点 → 自身可点击则 ACTION_CLICK；否则向上找最多 3 层可点击祖先
     * （很多列表项的文字节点 clickable=false，真正可点的是父容器）。
     *
     * @return true 表示无障碍动作已被受理；false 表示没找到节点或动作被拒（调用方应回退手势注入）
     */
    public static boolean performClick(String id, String text, String desc, boolean longPress) {
        AccessibilityService s = service;
        if (s == null || (id.isEmpty() && text.isEmpty() && desc.isEmpty())) {
            return false;
        }
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        AccessibilityNodeInfo best = null;
        boolean bestExact = false;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            int[] budget = new int[]{MAX_NODES};
            collectNodes(root, nodes, 0, budget);
            for (AccessibilityNodeInfo n : nodes) {
                int score = matchScore(n, id, text, desc);
                if (score < 0 || !n.isVisibleToUser()) {
                    continue;
                }
                boolean exact = score == 1;
                if (best == null || (exact && !bestExact)) {
                    best = n;
                    bestExact = exact;
                } else if (exact == bestExact && n.isClickable() && !best.isClickable()) {
                    best = n;
                }
            }
            if (best == null) {
                return false;
            }
            int action = longPress
                    ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                    : AccessibilityNodeInfo.ACTION_CLICK;
            AccessibilityNodeInfo target = best;
            for (int depth = 0; depth < 4 && target != null; depth++) {
                if (target.isClickable() && target.isEnabled()) {
                    if (target.performAction(action)) {
                        return true;
                    }
                }
                target = target.getParent();
            }
            return best.performAction(action);
        } catch (Throwable t) {
            Log.w(TAG, "performClick 失败: " + t);
            return false;
        } finally {
            for (AccessibilityNodeInfo n : nodes) {
                try {
                    n.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** -1 不匹配；0 包含匹配；1 精确匹配。 */
    private static int matchScore(AccessibilityNodeInfo n, String id, String text, String desc) {
        String nt = n.getText() == null ? "" : n.getText().toString();
        String nd = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
        String nid = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
        boolean exact = true;
        if (!id.isEmpty()) {
            if (!nid.equals(id) && !nid.contains(id)) return -1;
            exact = exact && nid.equals(id);
        }
        if (!text.isEmpty()) {
            if (!nt.equals(text) && !nt.contains(text)) return -1;
            exact = exact && nt.equals(text);
        }
        if (!desc.isEmpty()) {
            if (!nd.equals(desc) && !nd.contains(desc)) return -1;
            exact = exact && nd.equals(desc);
        }
        return exact ? 1 : 0;
    }

    /** 节点自身或最多 maxUp 层祖先是否可滚动（元素在不在可滚动的列表里）。 */
    private static boolean hasScrollableAncestor(AccessibilityNodeInfo n, int maxUp) {
        AccessibilityNodeInfo cur = n;
        try {
            for (int i = 0; i < maxUp && cur != null; i++) {
                if (cur.isScrollable()) {
                    return true;
                }
                cur = cur.getParent();
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 节点自身或最多 maxUp 层祖先是否可点击（列表项文字节点常靠父容器接手势）。 */
    private static boolean hasClickableAncestor(AccessibilityNodeInfo n, int maxUp) {
        AccessibilityNodeInfo cur = n;
        try {
            for (int i = 0; i < maxUp && cur != null; i++) {
                if (cur.isClickable()) {
                    return true;
                }
                cur = cur.getParent();
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean better(Match a, Match b) {
        if (a.actionable != b.actionable) return a.actionable;
        long areaA = (long) (a.right - a.left) * (a.bottom - a.top);
        long areaB = (long) (b.right - b.left) * (b.bottom - b.top);
        return areaA < areaB;
    }

    // ============ 无 adb 通道时的动作原语（2026-11-12） ============

    /** 给当前聚焦的可编辑节点设置文本（ACTION_SET_TEXT，不依赖 adb input text）。 */
    public static boolean setText(String text) {
        AccessibilityService s = service;
        if (s == null) {
            return false;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = s.getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            AccessibilityNodeInfo target = findEditable(root, 0);
            if (target == null) {
                return false;
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Throwable t) {
            Log.w(TAG, "setText 失败", t);
            return false;
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > MAX_DEPTH) {
            return null;
        }
        if (n.isEditable()) {
            return n;
        }
        if (n.isFocused()) {
            // 聚焦容器里优先找可编辑子节点，找不到就继续遍历
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo found = findEditable(n.getChild(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(n.getChild(i), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 最近一次 dump 的前台包名。 */
    public static String currentPackage() {
        return lastPackage == null ? "" : lastPackage;
    }

    /** 全局动作：GLOBAL_ACTION_BACK/HOME/RECENTS 等（不依赖 adb keyevent）。 */
    public static boolean performGlobalAction(int action) {
        AccessibilityService s = service;
        if (s == null) {
            return false;
        }
        try {
            return s.performGlobalAction(action);
        } catch (Throwable t) {
            Log.w(TAG, "performGlobalAction 失败: " + action, t);
            return false;
        }
    }

    /** 手势滑动（dispatchGesture，API 24+）。 */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean dispatchSwipe(float x1, float y1, float x2, float y2, long durationMs) {
        AccessibilityService s = service;
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, Math.max(1L, durationMs));
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);
            return s.dispatchGesture(builder.build(), null, null);
        } catch (Throwable t) {
            Log.w(TAG, "dispatchSwipe 失败", t);
            return false;
        }
    }

    /**
     * 无障碍服务截屏（API 30+）。返回 null 表示不可用/超时。
     * 注意：FLAG_SECURE 窗口会返回全黑或失败，调用方需自行声明。
     */
    public static Bitmap screenshot(long timeoutMs) {
        AccessibilityService s = service;
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] out = new Bitmap[1];
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            HardwareBuffer buffer = result.getHardwareBuffer();
                            try {
                                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                                if (hw != null) {
                                    out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "screenshot 解码失败", t);
                            } finally {
                                if (buffer != null) {
                                    buffer.close();
                                }
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Log.w(TAG, "screenshot 失败: errorCode=" + errorCode);
                            latch.countDown();
                        }
                    });
            latch.await(Math.max(100L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "screenshot 调用异常", t);
        }
        return out[0];
    }

    private static void collectNodes(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out,
                                     int depth, int[] budget) {
        if (n == null || depth > MAX_DEPTH || budget[0] <= 0) return;
        budget[0]--;
        out.add(n);
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo child = n.getChild(i);
            if (child == null) continue;
            collectNodes(child, out, depth + 1, budget);
        }
    }
}
