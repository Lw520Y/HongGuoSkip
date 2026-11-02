package com.hongguoskip;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences 键常量与简易读写（对应原版 Prefs）。 */
public final class Prefs {

    private static final String FILE = "settings";

    /** 总开关：关闭后服务不判定、不滑动、悬浮球隐藏 */
    public static final String KEY_ENABLED = "enabled";
    /** “记为广告”悬浮球开关 */
    public static final String KEY_OVERLAY = "overlay_enabled";

    private Prefs() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public static boolean overlay(Context c) {
        return sp(c).getBoolean(KEY_OVERLAY, false);
    }

    public static void setOverlay(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_OVERLAY, v).apply();
    }
}
