package com.hongguoskip;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 三类关键词库（对应原版 WordStore）：
 *  - MARKER(0) 强标记词：直接广告证据（配合上半屏位置约束构成判定主力通道）
 *  - ECOM(1)   电商词：计数 ≥ 阈值（5，缺陷2修复）才可作辅助信号
 *  - WEAK(2)   弱信号词：仅辅助（白名单观察的守门条件等）
 * SharedPreferences(word_lists_v1) 持久化，为空时回落默认。
 */
public final class WordStore {

    public static final int TYPE_MARKER = 0;
    public static final int TYPE_ECOM = 1;
    public static final int TYPE_WEAK = 2;
    public static final String[] TYPE_NAMES = {"强标记词", "电商词", "弱信号词"};

    private static final String PREFS = "word_lists_v1";
    private static final String KEY_PREFIX = "words_";

    /** 默认强标记词（14 个，对齐原版） */
    static final String[] DEFAULT_MARKER = {
            "查看详情", "点击进入游戏", "点击进入直播间", "立即下载", "领券购买", "进店逛逛",
            "立即购买", "立即抢购", "点击下载", "免费下载", "立即领取", "点击领取", "马上抢", "去领取",
    };

    /** 默认电商词（38 个） */
    static final String[] DEFAULT_ECOM = {
            "下单", "抢购", "补贴", "限时", "优惠", "包邮", "百亿补贴", "券后", "运费险", "满减",
            "折扣", "秒杀", "特惠", "旗舰店", "店铺", "加入购物车", "购物车", "到手价", "优惠价", "立减",
            "直降", "省钱", "返利", "优惠券", "领券", "活动价", "拼团", "团购", "爆款", "热卖",
            "限量", "抢购价", "立即下单", "支付", "付款", "特价", "促销", "好物",
    };

    /** 默认弱信号词（38 个） */
    static final String[] DEFAULT_WEAK = {
            "下载", "红包", "福利", "详情", "不感兴趣", "跳转", "安装", "打开", "领取", "点击",
            "进入", "查看", "参与", "活动", "推广", "赞助", "直播", "游戏", "试玩", "专属",
            "特权", "免费", "赠送", "礼包", "金币", "提现", "赚钱", "收益", "返现", "立赚",
            "邀请好友", "分享", "看广告", "玩游戏", "中奖", "抽奖", "优惠券领取", "立即查看",
    };

    // 进程内缓存：服务每 300ms 取词，避免重复 JSON 解析
    private static final List<String>[] CACHE = new List[3];
    private static final String[] CACHE_JSON = new String[3];

    private WordStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 取某类词库（有序去重；用户未自定义时回落默认） */
    public static List<String> get(Context c, int type) {
        String key = KEY_PREFIX + type;
        String json = sp(c).getString(key, null);
        if (CACHE[type] != null && eq(CACHE_JSON[type], json)) {
            return CACHE[type];
        }
        List<String> out = new ArrayList<>();
        if (json != null) {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    String w = a.optString(i, "").trim();
                    if (!w.isEmpty() && !out.contains(w)) {
                        out.add(w);
                    }
                }
            } catch (JSONException ignored) {
            }
        }
        if (out.isEmpty()) {
            out.addAll(Arrays.asList(defaults(type)));
        }
        CACHE[type] = out;
        CACHE_JSON[type] = json;
        return out;
    }

    /** 添加词（去重）。返回是否成功。 */
    public static boolean add(Context c, int type, String word) {
        String w = word == null ? "" : word.trim();
        if (w.isEmpty() || w.length() > 20) {
            return false;
        }
        List<String> list = new ArrayList<>(get(c, type));
        if (list.contains(w)) {
            return false;
        }
        list.add(w);
        save(c, type, list);
        return true;
    }

    public static void remove(Context c, int type, String word) {
        List<String> list = new ArrayList<>(get(c, type));
        if (list.remove(word)) {
            save(c, type, list);
        }
    }

    /** 恢复该类默认词库 */
    public static void reset(Context c, int type) {
        sp(c).edit().remove(KEY_PREFIX + type).apply();
        CACHE[type] = null;
        CACHE_JSON[type] = null;
    }

    private static void save(Context c, int type, List<String> list) {
        JSONArray a = new JSONArray();
        for (String w : list) {
            a.put(w);
        }
        String json = a.toString();
        sp(c).edit().putString(KEY_PREFIX + type, json).apply();
        CACHE[type] = new ArrayList<>(list);
        CACHE_JSON[type] = json;
    }

    private static String[] defaults(int type) {
        switch (type) {
            case TYPE_ECOM:
                return DEFAULT_ECOM;
            case TYPE_WEAK:
                return DEFAULT_WEAK;
            default:
                return DEFAULT_MARKER;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
