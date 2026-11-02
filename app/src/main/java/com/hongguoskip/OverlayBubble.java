package com.hongguoskip;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * “记为广告”悬浮球（对应原版 OverlayBubble，含缺陷 6 修复）。
 *
 * WindowManager TYPE_APPLICATION_OVERLAY，左上角 x=8dp、y=屏高 30%，仅红果前台且开关开启时显示。
 * 与原版的差异（交互重设计，防看剧误触）：
 *  - 单击 ＝ 仅测试上滑（不再“学习整屏+滑走”一步到位）；
 *  - 长按 ＝ 把当前页记为广告（学习特征 + 抑制自动滑动 12s，不自动滑走）。
 */
public class OverlayBubble {

    /** 交互回调（由 AdSkipService 实现） */
    public interface Callback {
        void onTestSwipe();

        void onMarkAd();
    }

    private final Context context;
    private final WindowManager wm;
    private final Callback cb;
    private View view;
    private boolean added;

    public OverlayBubble(Context context, Callback cb) {
        this.context = context.getApplicationContext();
        this.cb = cb;
        this.wm = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 显示悬浮球（需已授予悬浮窗权限；重复调用安全） */
    public void show() {
        if (added || wm == null) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        if (view == null) {
            view = buildView();
        }
        float density = context.getResources().getDisplayMetrics().density;
        int x = (int) (8 * density);                                  // 左上角 x=8dp
        int y = (int) (0.30 * context.getResources().getDisplayMetrics().heightPixels); // y=屏高30%
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = x;
        lp.y = y;
        try {
            wm.addView(view, lp);
            added = true;
            Diag.log("BUBBLE", "shown");
        } catch (Throwable t) {
            Diag.log("BUBBLE", "show fail: " + t);
        }
    }

    public void hide() {
        if (!added || wm == null) {
            return;
        }
        try {
            wm.removeView(view);
        } catch (Throwable ignored) {
        }
        added = false;
    }

    public boolean isShown() {
        return added;
    }

    private View buildView() {
        TextView v = new TextView(context);
        v.setText("跳广");
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        int pad = (int) (context.getResources().getDisplayMetrics().density * 14);
        v.setPadding(pad, pad, pad, pad);
        v.setBackgroundResource(R.drawable.bubble_bg);
        v.setContentDescription("跳广告悬浮球：单击测试上滑，长按记为广告");
        // 缺陷6修复：单击仅测试滑动，长按才标记学习
        v.setOnClickListener(b -> cb.onTestSwipe());
        v.setOnLongClickListener(b -> {
            cb.onMarkAd();
            return true;
        });
        return v;
    }
}
