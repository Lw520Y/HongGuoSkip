package com.hongguoskip;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 广告特征自学习（对应原版 AdLearner，含缺陷 4/5 修复）。
 *
 * 存储：files/ad_learner.json = {"ads":[[特征..],..],"whitelist":[..]}
 *  - ads：≤50 类广告 × 每类 ≤40 特征；与已有类重叠度 ≥50%（common*2 >= features.size()）归并
 *  - whitelist：≤400 条，连续 5s 无广告信号界面的整屏特征（30s 冷却）
 *
 * 与原版的差异（docs/原app逆向分析.md 第 6/7 节）：
 *  - 缺陷4：移除“自动滑动后整屏学习”（误学污染源），仅保留悬浮球长按手动标记；
 *  - 缺陷5：白名单不只作用于 learned 通道，还提供 isWhitelisted() 供服务端
 *    在 marker/ecom 命中前做文本级过滤；
 *  - 手动标记与滑动手势解耦：recordAd 不再触发滑动。
 */
public final class AdLearner {

    public static final int MAX_CLASSES = 50;
    public static final int MAX_FEATURES = 40;
    public static final int MAX_WHITELIST = 400;
    /** 与某类广告特征交集 ≥2 即判 learned 命中 */
    public static final int MATCH_MIN = 2;

    /** 泛型词黑名单（原版 8 个短语）：到处出现的提示语，不作为学习特征 */
    private static final String[] GENERIC = {
            "上滑继续", "秒后", "倒计时", "可滑动", "结束后即可", "继续观看", "观看短剧", "不感兴趣",
    };

    /** 归一化剔除：数字/空白/标点/货币符（¥￥$ 等，见原版字符集） */
    private static final Pattern STRIP = Pattern.compile(
            "[0-9\\s\\u00A0¥￥$＄.,，。、;；:：!！?？~`·+\\-—–|/\\\\()（）\\[\\]【】{}<>《》\"“”‘’'’*&%#@$^_…]");

    private static volatile AdLearner sInstance;

    private final File file;
    private final List<List<String>> ads = new ArrayList<>();
    private final LinkedHashSet<String> whitelist = new LinkedHashSet<>();

    private AdLearner(Context c) {
        file = new File(c.getApplicationContext().getFilesDir(), "ad_learner.json");
        load();
    }

    /** 单例（同进程内 MainActivity 与 Service 共享同一份内存态） */
    public static AdLearner get(Context c) {
        if (sInstance == null) {
            synchronized (AdLearner.class) {
                if (sInstance == null) {
                    sInstance = new AdLearner(c);
                }
            }
        }
        return sInstance;
    }

    // ———————————————————————— 归一化 ————————————————————————

    /** 剔除数字/空白/标点/货币符，截断到 24 字符 */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = STRIP.matcher(s).replaceAll("");
        return t.length() > 24 ? t.substring(0, 24) : t;
    }

    /** 可学习：长度 ≥2 且不含泛型提示语 */
    public static boolean isLearnable(String normalized) {
        if (normalized == null || normalized.length() < 2) {
            return false;
        }
        for (String g : GENERIC) {
            if (normalized.contains(g)) {
                return false;
            }
        }
        return true;
    }

    /** 屏幕文本 → 去重归一化特征集 */
    public static Set<String> featuresOf(Collection<String> rawTexts) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : rawTexts) {
            String n = normalize(raw);
            if (isLearnable(n)) {
                out.add(n);
            }
        }
        return out;
    }

    // ———————————————————————— 学习（仅人工通道） ————————————————————————

    /**
     * 手动标记当前页为广告：与已有类重叠 ≥50% 归并，否则新建类。
     *
     * @return 是否已记录（无可学习特征或库满则 false）
     */
    public synchronized boolean recordAd(Collection<String> rawTexts) {
        Set<String> feats = featuresOf(rawTexts);
        if (feats.isEmpty()) {
            return false;
        }
        List<String> incoming = new ArrayList<>(feats);
        for (List<String> cls : ads) {
            int common = 0;
            for (String f : incoming) {
                if (cls.contains(f)) {
                    common++;
                }
            }
            if (common * 2 >= incoming.size()) {   // 重叠度 ≥50% → 归并入该类
                for (String f : incoming) {
                    if (!cls.contains(f)) {
                        cls.add(f);
                    }
                }
                while (cls.size() > MAX_FEATURES) {
                    cls.remove(0);
                }
                save();
                return true;
            }
        }
        if (ads.size() >= MAX_CLASSES) {
            return false;
        }
        ads.add(new ArrayList<>(incoming));
        save();
        return true;
    }

    /** 当前特征与各类广告的最大交集数（白名单特征不计入） */
    public synchronized int matchCount(Collection<String> features) {
        int best = 0;
        for (List<String> cls : ads) {
            int c = 0;
            for (String f : features) {
                if (!whitelist.contains(f) && cls.contains(f)) {
                    c++;
                }
            }
            if (c > best) {
                best = c;
            }
        }
        return best;
    }

    // ———————————————————————— 白名单（防误学/防误判） ————————————————————————

    /** 连续 5s 无广告信号界面的整屏特征加入白名单 */
    public synchronized void addWhitelist(Collection<String> features) {
        boolean changed = false;
        for (String f : features) {
            if (!whitelist.contains(f)) {
                whitelist.add(f);
                changed = true;
            }
        }
        while (whitelist.size() > MAX_WHITELIST) {
            String oldest = whitelist.iterator().next();
            whitelist.remove(oldest);
        }
        if (changed) {
            save();
        }
    }

    /** 文本级白名单判定（缺陷5：marker/ecom 命中前过滤） */
    public synchronized boolean isWhitelisted(String normalized) {
        return normalized != null && normalized.length() >= 2 && whitelist.contains(normalized);
    }

    /** 清除学习记录（特征库 + 白名单） */
    public synchronized void clearAll() {
        ads.clear();
        whitelist.clear();
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public synchronized int classCount() {
        return ads.size();
    }

    public synchronized int whitelistCount() {
        return whitelist.size();
    }

    // ———————————————————————— 持久化 ————————————————————————

    private void load() {
        if (!file.exists()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray("ads");
            if (a != null) {
                for (int i = 0; i < a.length() && ads.size() < MAX_CLASSES; i++) {
                    JSONArray cls = a.optJSONArray(i);
                    if (cls == null) {
                        continue;
                    }
                    List<String> list = new ArrayList<>();
                    for (int j = 0; j < cls.length() && list.size() < MAX_FEATURES; j++) {
                        String f = cls.optString(j);
                        if (!f.isEmpty()) {
                            list.add(f);
                        }
                    }
                    if (!list.isEmpty()) {
                        ads.add(list);
                    }
                }
            }
            JSONArray w = o.optJSONArray("whitelist");
            if (w != null) {
                for (int i = 0; i < w.length() && whitelist.size() < MAX_WHITELIST; i++) {
                    String f = w.optString(i);
                    if (!f.isEmpty()) {
                        whitelist.add(f);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            JSONArray a = new JSONArray();
            for (List<String> cls : ads) {
                JSONArray c = new JSONArray();
                for (String f : cls) {
                    c.put(f);
                }
                a.put(c);
            }
            o.put("ads", a);
            JSONArray w = new JSONArray();
            for (String f : whitelist) {
                w.put(f);
            }
            o.put("whitelist", w);
            writeBytes(file, o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f, false)) {
            fos.write(data);
            fos.getFD().sync();
        }
    }
}
