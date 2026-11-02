package com.hongguoskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 核心无障碍服务（对应原版 AdSkipService）。
 *
 * 主循环：事件驱动 + 轮询兜底——onAccessibilityEvent 每次事件把 300ms poller 重新排队，
 * 事件密集时靠事件驱动，安静时靠轮询兜底。
 *
 * 判定与防抖参数依据 docs/原app逆向分析.md 第 6/7 节（修复版参数，重写必须继承）：
 *  - 缺陷1：正片提示“上滑继续看短剧”（不含“观看”）→ 本轮禁滑 + 电商通道短路（-99）；
 *  - 缺陷2：ecomCombo 阈值 3 → 5；
 *  - 缺陷3：信号稳定时长 1500 → 2500ms；
 *  - 缺陷4：关闭自动学习，仅保留悬浮球长按手动标记；
 *  - 缺陷5：marker/ecom/learned 命中前先做白名单文本过滤；
 *  - 重写要点1：learned/ecom 只能作辅助信号，不得独立满足 adSignal+topSignal——
 *    触发条件额外要求 marker 必须在场。
 */
public class AdSkipService extends AccessibilityService implements OverlayBubble.Callback {

    /** 监控目标：红果短剧（硬编码，对齐原版） */
    public static final String TARGET_PACKAGE = "com.phoenix.read";

    // —— 主循环与采集 ——
    private static final long POLL_INTERVAL_MS = 300;
    private static final int MAX_DEPTH = 30;          // 节点树递归深度上限
    private static final int MAX_BOUNDED = 80;        // 带坐标可见文本上限
    private static final int MAX_LOOSE = 120;         // 松散文本上限（不过滤可见性）
    private static final int MAX_TEXT_LEN = 60;       // 超长文本无判定价值
    private static final int MIN_INTERSECT_PX = 8;    // 与屏幕交集 ≥8px 才算可见

    // —— 防抖状态机（修复版参数）——
    private static final long SIGNAL_STABLE_MS = 2500;      // 缺陷3：1500 → 2500
    private static final int MAX_SWIPES_PER_SESSION = 3;    // 每场广告最多滑 3 次
    private static final long FIRST_SWIPE_DELAY_MS = 900;   // 首滑间隔 ≥900ms
    private static final long RE_SWIPE_INTERVAL_MS = 2500;  // 补滑 ≥2.5s
    private static final long MARK_SUPPRESS_MS = 12000;     // 手动标记后抑制 12s
    private static final long SESSION_RESET_MS = 1000;      // 广告信号消失 1s 重置会话
    private static final long COUNTDOWN_GRACE_MS = 2000;    // 倒计时读秒 +2s 缓冲
    private static final long WHITELIST_WATCH_MS = 5000;    // 连续 5s 无信号 → 白名单
    private static final long WHITELIST_COOLDOWN_MS = 30000;
    private static final long DIAG_FRAME_MS = 1200;         // 诊断帧 1.2s 一帧

    private static final int ECOM_THRESHOLD = 5;            // 缺陷2：3 → 5

    /** 倒计时正则：“X秒后”（可滑动/可跳过类文案） */
    private static final Pattern COUNTDOWN_RE = Pattern.compile("(\\d{1,2})\\s*秒后");

    /** 运行实例（MainActivity 的“测试上滑”入口用） */
    private static volatile AdSkipService running;

    public static AdSkipService running() {
        return running;
    }

    private Handler handler;
    private OverlayBubble bubble;

    // —— 会话状态机 ——
    private long signalSince;      // adSignal && topSignal 持续起点（稳定计时）
    private long stableSince;      // stable 达成时刻
    private long lastSwipeAt;
    private long adWindowEnd;      // 倒计时窗口结束时刻（读秒×1000+2000）
    private long suppressUntil;    // 手动标记后抑制
    private long adLastSeenAt;     // 最近一次广告信号时刻
    private long noSignalSince = -1;              // 无信号观察起点（白名单）
    private long lastWhitelistAt = -WHITELIST_COOLDOWN_MS;
    private long lastDiagFrameAt;
    private int adSwipeCount;      // 本场广告已滑次数
    private boolean inTarget;      // 红果是否前台（离场时重置会话）

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            try {
                evaluate();
            } catch (Throwable t) {
                Diag.log("SVC", "evaluate error: " + t);
            }
            if (handler != null) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    // ———————————————————————— 生命周期 ————————————————————————

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        bubble = new OverlayBubble(this, this);
        Diag.init(this);
        AdLearner.get(this);   // 预加载特征库
        running = this;
        Diag.log("SVC", "created, target=" + TARGET_PACKAGE);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (handler != null) {
            handler.removeCallbacks(poller);
            handler.post(poller);
        }
        Diag.log("SVC", "connected, polling started");
    }

    @Override
    public void onDestroy() {
        running = null;
        if (handler != null) {
            handler.removeCallbacks(poller);
            handler = null;
        }
        if (bubble != null) {
            bubble.hide();
        }
        Diag.log("SVC", "destroyed");
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = null;
        if (bubble != null) {
            bubble.hide();
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 事件驱动 + 轮询兜底：每次事件把 poller 重新排队（立即评估）
        if (handler != null && Prefs.enabled(this)) {
            handler.removeCallbacks(poller);
            handler.post(poller);
        }
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    // ———————————————————————— 悬浮球回调 ————————————————————————

    @Override
    public void onTestSwipe() {
        testSwipe();
    }

    @Override
    public void onMarkAd() {
        markCurrentAd();
    }

    // ———————————————————————— 主循环 ————————————————————————

    private void evaluate() {
        if (!Prefs.enabled(this)) {
            leaveTargetIfNeeded();
            bubble.hide();
            return;
        }
        long now = SystemClock.elapsedRealtime();

        // 1) 采集红果界面文本；非红果前台 → 隐藏悬浮球直接返回
        Snapshot snap = collect();
        if (snap == null) {
            leaveTargetIfNeeded();
            bubble.hide();
            return;
        }
        enterTargetIfNeeded();   // 回到红果：清掉离场前的稳定计时/会话计数，防止旧状态立即误滑
        if (Prefs.overlay(this)) {
            bubble.show();
        } else {
            bubble.hide();
        }

        AdLearner learner = AdLearner.get(this);
        List<String> wMarker = WordStore.get(this, WordStore.TYPE_MARKER);
        List<String> wEcom = WordStore.get(this, WordStore.TYPE_ECOM);
        List<String> wWeak = WordStore.get(this, WordStore.TYPE_WEAK);

        // 2) 倒计时 / hint / 正片提示（松散文本，不做可见性过滤）
        int countdownSec = -1;
        boolean hint = false;         // “还在倒计时不能滑”
        boolean positiveHint = false; // 正片翻集提示（无“观看”）→ 本轮禁滑
        for (String raw : snap.loose) {
            String t = raw.replace('划', '滑');   // 对抗错别字变体
            Matcher m = COUNTDOWN_RE.matcher(t);
            if (m.find()) {
                try {
                    int sec = Integer.parseInt(m.group(1).trim());
                    if (countdownSec < 0 || sec < countdownSec) {
                        countdownSec = sec;      // 取最小读秒
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            boolean swipeWord = t.contains("上滑") || t.contains("滑动") || t.contains("继续");
            if ((t.contains("秒后") && swipeWord) || t.contains("倒计时结束后即可滑动")) {
                hint = true;
            }
            // 缺陷1语义化：广告页提示带“观看”（“上滑继续观看短剧”）；
            // 正片提示是“上滑继续看短剧”（无“观看”）→ 禁滑并短路电商通道
            if (t.contains("上滑继续看") && !t.contains("观看")) {
                positiveHint = true;
            }
        }

        // 3) 五路信号（缺陷5：命中前先做白名单文本过滤）
        Set<String> distinct = new LinkedHashSet<>();
        for (ScreenText st : snap.bounded) {
            distinct.add(st.text);
        }
        distinct.addAll(snap.loose);

        boolean markerHit = false;    // 强标记词或“广告”角标（无位置约束）
        int ecomScore = 0;
        int weakCount = 0;
        Set<String> features = new LinkedHashSet<>();
        for (String raw : distinct) {
            String norm = AdLearner.normalize(raw);
            if (AdLearner.isLearnable(norm)) {
                features.add(norm);
            }
            if (learner.isWhitelisted(norm)) {
                continue;   // 缺陷5：白名单文本不作任何通道证据
            }
            if (isAdLabel(raw) || containsAny(raw, wMarker)) {
                markerHit = true;
            }
            ecomScore += countHits(raw, wEcom);
            if (containsAny(raw, wWeak)) {
                weakCount++;
            }
        }

        boolean topMarkerHit = false; // 上半屏位置约束（rect.top < screenH/2）
        int weakTop = 0;              // 上半屏弱词数（≥2 计入 minTop 辅助观测）
        for (ScreenText st : snap.bounded) {
            if (st.rect.top >= snap.screenH / 2) {
                continue;
            }
            if (learner.isWhitelisted(AdLearner.normalize(st.text))) {
                continue;
            }
            if (isAdLabel(st.text) || containsAny(st.text, wMarker)) {
                topMarkerHit = true;
            }
            if (containsAny(st.text, wWeak)) {
                weakTop++;
            }
        }

        int learnedCount = learner.matchCount(features);
        boolean learnedHit = learnedCount >= AdLearner.MATCH_MIN;
        if (positiveHint) {
            ecomScore = -99;          // 缺陷1：正片短路（电商通道整体失效）
        }
        boolean ecomHit = ecomScore >= ECOM_THRESHOLD;   // 缺陷2：阈值 5

        boolean adSignal = markerHit || learnedHit || ecomHit;
        boolean topSignal = topMarkerHit || learnedHit || ecomHit;

        // 4) 会话边界：广告信号消失 1s 重置会话计数
        if (adSignal) {
            adLastSeenAt = now;
        } else if (adLastSeenAt > 0 && now - adLastSeenAt > SESSION_RESET_MS) {
            resetSession();
            adLastSeenAt = 0;
        }

        // 5) 倒计时窗口：读秒×1000+2000 内不滑
        if (countdownSec >= 0) {
            long end = now + countdownSec * 1000L + COUNTDOWN_GRACE_MS;
            if (end > adWindowEnd) {
                adWindowEnd = end;
            }
        }
        boolean inCountdown = now < adWindowEnd;

        // 6) 稳定计时（防抖，缺陷3：2500ms）
        if (adSignal && topSignal) {
            if (signalSince == 0) {
                signalSince = now;
            }
        } else {
            signalSince = 0;
        }
        boolean stable = signalSince > 0 && now - signalSince >= SIGNAL_STABLE_MS;
        if (stable && stableSince == 0) {
            stableSince = now;
        } else if (!stable) {
            stableSince = 0;
        }

        // 7) 滑动触发（重写要点1：marker 必须在场，learned/ecom 不得独立满足）
        boolean spacingOk = adSwipeCount == 0
                ? now - stableSince >= FIRST_SWIPE_DELAY_MS
                : now - lastSwipeAt >= RE_SWIPE_INTERVAL_MS;
        boolean canSwipe = !hint
                && !positiveHint
                && !inCountdown
                && adSwipeCount < MAX_SWIPES_PER_SESSION
                && now >= suppressUntil
                && markerHit
                && adSignal && topSignal && stable
                && spacingOk;
        if (canSwipe) {
            Diag.log("SWIPE", "auto trigger: mk=" + b(markerHit) + " topM=" + b(topMarkerHit)
                    + " lrn=" + learnedCount + " ecom=" + ecomScore
                    + " stable=" + (now - signalSince) + "ms #" + (adSwipeCount + 1));
            performSwipe();
            adSwipeCount++;
            lastSwipeAt = now;
        }

        // 8) 白名单自学习：连续 5s 无任何广告信号（且弱词 <2）→ 整屏特征入库（30s 冷却）
        if (!adSignal && weakCount < 2) {
            if (noSignalSince < 0) {
                noSignalSince = now;
            } else if (now - noSignalSince >= WHITELIST_WATCH_MS
                    && now - lastWhitelistAt >= WHITELIST_COOLDOWN_MS) {
                learner.addWhitelist(features);
                lastWhitelistAt = now;
                noSignalSince = now;   // 冷却后重新观察
                Diag.log("LEARN", "whitelist +" + features.size() + " features");
            }
        } else {
            noSignalSince = -1;
        }

        // 9) 诊断帧（1.2s 一帧，格式尽量兼容原版便于对照）
        if (now - lastDiagFrameAt >= DIAG_FRAME_MS) {
            lastDiagFrameAt = now;
            diagFrame(snap, countdownSec, markerHit, topMarkerHit, learnedCount,
                    ecomScore, hint, positiveHint, stable, weakCount, weakTop);
        }
    }

    // ———————————————————————— 界面文本采集 ————————————————————————

    /** 带坐标文本 */
    private static class ScreenText {
        final String text;
        final Rect rect;

        ScreenText(String text, Rect rect) {
            this.text = text;
            this.rect = rect;
        }
    }

    /** 一帧采集结果 */
    private static class Snapshot {
        final List<ScreenText> bounded = new ArrayList<>();  // 带坐标可见节点（≤80）
        final List<String> loose = new ArrayList<>();        // 松散文本（≤120）
        final Set<String> viewIds = new LinkedHashSet<>();   // 采集到的 viewId
        int screenH = 1080;
    }

    /**
     * 取活动窗口根节点（包名须为红果）；拿不到或包名不符时降级遍历 getWindows()（多窗口场景）。
     * 无红果窗口返回 null。
     */
    private Snapshot collect() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Snapshot s = new Snapshot();
        s.screenH = dm.heightPixels;

        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && TARGET_PACKAGE.equals(pkg(active))) {
            roots.add(active);
        }
        if (roots.isEmpty()) {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && TARGET_PACKAGE.equals(pkg(r))) {
                        roots.add(r);
                    }
                }
            }
        }
        if (roots.isEmpty()) {
            return null;
        }
        for (AccessibilityNodeInfo r : roots) {
            walk(r, 0, s, dm);
        }
        return s;
    }

    private void walk(AccessibilityNodeInfo node, int depth, Snapshot s, DisplayMetrics dm) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        collectText(node.getText(), node, s, dm);
        collectText(node.getContentDescription(), node, s, dm);
        if (s.viewIds.size() < 40) {
            CharSequence vid = node.getViewIdIdentifier();
            if (vid != null && vid.length() > 0) {
                s.viewIds.add(vid.toString());
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), depth + 1, s, dm);
        }
    }

    private void collectText(CharSequence cs, AccessibilityNodeInfo node, Snapshot s, DisplayMetrics dm) {
        if (cs == null) {
            return;
        }
        String t = cs.toString().trim();
        if (t.isEmpty() || t.length() > MAX_TEXT_LEN) {
            return;
        }
        if (s.loose.size() < MAX_LOOSE) {
            s.loose.add(t);   // 松散文本：不做可见性过滤，供倒计时/hint 识别
        }
        if (s.bounded.size() < MAX_BOUNDED) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            Rect inter = new Rect(r);
            if (inter.intersect(0, 0, dm.widthPixels, dm.heightPixels)
                    && (inter.width() >= MIN_INTERSECT_PX || inter.height() >= MIN_INTERSECT_PX)) {
                s.bounded.add(new ScreenText(t, r));
            }
        }
    }

    private static String pkg(AccessibilityNodeInfo n) {
        CharSequence p = n.getPackageName();
        return p == null ? null : p.toString();
    }

    // ———————————————————————— 判定辅助 ————————————————————————

    /**
     * “广告”角标判定：文本以“广告”开头，其余字符全为标点/符号/空白
     * （角标节点文本常为 “广告,” / “广告·” 之类）。
     */
    static boolean isAdLabel(String t) {
        if (t == null) {
            return false;
        }
        String s = t.trim();
        if (s.equals("广告")) {
            return true;
        }
        return s.startsWith("广告") && AdLearner.normalize(s.substring(2)).isEmpty();
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static int countHits(String text, List<String> words) {
        int n = 0;
        for (String w : words) {
            if (text.contains(w)) {
                n++;
            }
        }
        return n;
    }

    private static String b(boolean v) {
        return v ? "1" : "0";
    }

    private void resetSession() {
        if (adSwipeCount != 0 || signalSince != 0) {
            Diag.log("SVC", "session reset (swiped=" + adSwipeCount + ")");
        }
        adSwipeCount = 0;
        signalSince = 0;
        stableSince = 0;
        adWindowEnd = 0;
    }

    private void enterTargetIfNeeded() {
        if (!inTarget) {
            inTarget = true;
            resetSession();
            noSignalSince = -1;
            adLastSeenAt = 0;
        }
    }

    private void leaveTargetIfNeeded() {
        if (inTarget) {
            inTarget = false;
            resetSession();
            noSignalSince = -1;
        }
    }

    // ———————————————————————— 手势与人工动作 ————————————————————————

    /**
     * 模拟上滑：起点 75% 屏高、终点 25% 屏高，时长 260ms（对齐原版）。
     */
    private void performSwipe() {
        try {
            DisplayMetrics m = getResources().getDisplayMetrics();
            Path path = new Path();
            path.moveTo(m.widthPixels / 2f, 0.75f * m.heightPixels);
            path.lineTo(m.widthPixels / 2f, 0.25f * m.heightPixels);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 260))
                    .build();
            boolean ok = dispatchGesture(g, null, null);
            Diag.log("SWIPE", "dispatch=" + ok);
        } catch (Throwable t) {
            Diag.log("SWIPE", "error: " + t);
        }
    }

    /** 悬浮球单击 / 主界面“测试上滑”：仅模拟一次上滑，不影响会话状态 */
    public void testSwipe() {
        Diag.log("SWIPE", "manual test swipe");
        performSwipe();
    }

    /**
     * 悬浮球长按：把当前整屏文本记入广告特征库（仅人工通道，缺陷4），
     * 并抑制自动滑动 12s。与滑动解耦（缺陷6）：不自动滑走。
     */
    public void markCurrentAd() {
        long now = SystemClock.elapsedRealtime();
        suppressUntil = now + MARK_SUPPRESS_MS;
        Snapshot snap = collect();
        if (snap == null) {
            toast("红果不在前台，未记录");
            Diag.log("MARK", "skip: target not foreground");
            return;
        }
        Collection<String> allTexts = new ArrayList<>(snap.loose);
        boolean ok = AdLearner.get(this).recordAd(allTexts);
        Diag.log("MARK", "manual record=" + ok + " texts=" + snap.loose.size());
        toast(ok ? "已记为广告（自动滑动抑制 12 秒）" : "本页无可学习特征");
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ———————————————————————— 诊断 ————————————————————————

    /** 每帧判定快照：pkg/读秒/标记/学习/电商/提示/稳定/已滑/ids/文本摘要 */
    private void diagFrame(Snapshot s, int cd, boolean mk, boolean topM, int lrn,
                           int ecom, boolean hint, boolean pos, boolean stable, int wk, int wTop) {
        StringBuilder txt = new StringBuilder();
        for (String t : s.loose) {
            if (txt.length() > 0) {
                txt.append('/');
            }
            txt.append(t, 0, Math.min(t.length(), 12));
            if (txt.length() > 90) {
                break;
            }
        }
        Diag.log("EVAL", String.format(Locale.US,
                "pkg=%s cd=%d mk=%s topM=%s lrn=%d ecom=%d hint=%s pos=%s stable=%s sw=%d/%d "
                        + "wk=%d wTop=%d ids=%d txt=%.90s",
                TARGET_PACKAGE, cd, b(mk), b(topM), lrn, ecom, b(hint), b(pos), b(stable),
                adSwipeCount, MAX_SWIPES_PER_SESSION, wk, wTop, s.viewIds.size(),
                txt.toString()));
    }
}
