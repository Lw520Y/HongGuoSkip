package com.hongguoskip;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面（对应原版 MainActivity）：总开关、悬浮窗开关、无障碍引导、
 * 电池白名单、测试上滑、复制诊断日志、清除学习记录、词库入口。
 */
public class MainActivity extends Activity {

    private TextView statusView;
    private Switch enabledSwitch;
    private Switch overlaySwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Diag.init(this);
        AdLearner.get(this);   // 预加载，供状态栏显示学习库统计

        statusView = findViewById(R.id.statusView);
        enabledSwitch = findViewById(R.id.enabledSwitch);
        overlaySwitch = findViewById(R.id.overlaySwitch);

        enabledSwitch.setOnCheckedChangeListener((b, v) -> {
            Prefs.setEnabled(this, v);
            refreshStatus();
        });
        overlaySwitch.setOnCheckedChangeListener((b, v) -> {
            Prefs.setOverlay(this, v);
            if (v && !Settings.canDrawOverlays(this)) {
                toast("请先授予悬浮窗权限");
            }
            refreshStatus();
        });

        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnOverlayPerm).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                toast("无法打开悬浮窗设置：" + t.getMessage());
            }
        });
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestBatteryWhitelist());
        findViewById(R.id.btnTestSwipe).setOnClickListener(v -> {
            AdSkipService svc = AdSkipService.running();
            if (svc == null) {
                toast("请先开启无障碍服务");
            } else {
                svc.testSwipe();
                toast("已分发上滑手势（请切到红果观察）");
            }
        });
        findViewById(R.id.btnWords).setOnClickListener(v ->
                startActivity(new Intent(this, WordEditActivity.class)));
        findViewById(R.id.btnCopyDiag).setOnClickListener(v -> copyDiag());
        findViewById(R.id.btnClearLearn).setOnClickListener(v -> {
            AdLearner.get(this).clearAll();
            toast("已清除学习记录（特征库 + 白名单）");
            refreshStatus();
        });

        Diag.log("MAIN", "MainActivity launched");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean accOn = isAccessibilityEnabled();
        boolean serviceRunning = AdSkipService.running() != null;
        boolean overlayPerm = Settings.canDrawOverlays(this);
        boolean hongguoInstalled = isHongGuoInstalled();
        AdLearner learner = AdLearner.get(this);

        String acc = accOn
                ? (serviceRunning ? "运行中（轮询红果）" : "已授权（服务未运行）")
                : "未开启";
        statusView.setText(String.format(
                "无障碍服务：%s\n悬浮窗权限：%s\n红果短剧：%s\n总开关：%s　悬浮球：%s\n学习库：%d 类广告特征 · 白名单 %d 条",
                acc,
                overlayPerm ? "已授权" : "未授权",
                hongguoInstalled ? "已安装" : "未检测到（包名 " + AdSkipService.TARGET_PACKAGE + "）",
                Prefs.enabled(this) ? "开" : "关",
                Prefs.overlay(this) ? "开" : "关",
                learner.classCount(),
                learner.whitelistCount()));

        enabledSwitch.setEnabled(accOn);
        enabledSwitch.setChecked(Prefs.enabled(this));
        overlaySwitch.setChecked(Prefs.overlay(this));
        ((Button) findViewById(R.id.btnTestSwipe)).setEnabled(serviceRunning);
    }

    /** 系统设置里本服务是否已启用 */
    private boolean isAccessibilityEnabled() {
        String svc = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String comp = getPackageName() + "/" + AdSkipService.class.getName();
        return svc != null && (svc.contains(comp) || svc.contains(
                getPackageName() + "/" + AdSkipService.class.getSimpleName()));
    }

    private boolean isHongGuoInstalled() {
        try {
            getPackageManager().getPackageInfo(AdSkipService.TARGET_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestBatteryWhitelist() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("无法打开电池优化设置");
            }
        }
    }

    private void copyDiag() {
        String dump = Diag.dump();
        if (dump.isEmpty()) {
            toast("暂无诊断日志");
            return;
        }
        dump = dump.trim();
        int lines = dump.split("\n").length;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("diag", dump));
            Diag.log("MAIN", "diag copied, " + lines + " lines");
            toast("诊断日志已复制（" + lines + " 行）");
        } else {
            toast("剪贴板不可用");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
