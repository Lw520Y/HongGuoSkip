# 红果短剧跳广告工具（hongguo.apk）逆向分析

> 分析对象：`resources/hongguo.apk`（47KB）· jadx 1.5.6 反编译 · 2026-11-02
> 用途：HongGuoSkip 项目重写的技术基线。**重写时判定逻辑以本文第 6 节"修复版参数"为准**（原版存在误跳缺陷）。

## 1. 概述

**一句话**：Android 无障碍服务轮询红果短剧（`com.phoenix.read`）的界面文本 → 关键词/自学习特征判定"当前是广告页" → 等倒计时结束 → `dispatchGesture` 模拟上滑手势跳过广告。

| 属性 | 值 |
|---|---|
| 包名 | `com.workbuddy.adskip`（工具本体，非红果） |
| 版本 | v1.23 (versionCode 24)，debuggable 调试版 |
| 监控目标 | `AdSkipService.TARGET_PACKAGE = "com.phoenix.read"` 硬编码 |
| 规模 | 8 个类 ≈1500 行 Java，无第三方库、无混淆、**无网络权限（完全离线）** |
| SDK | minSdk 26 / targetSdk 34 |

## 2. 权限清单（AndroidManifest.xml）

| 权限 | 用途 |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | 核心：读屏 + 模拟手势 |
| `SYSTEM_ALERT_WINDOW` | "记为广告"悬浮球 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 保活 |

组件：`MainActivity`（启动器/设置）、`WordEditActivity`（词库管理）、`AdSkipService`（无障碍服务）。

## 3. 工程结构

```
com.workbuddy.adskip
├── MainActivity      设置界面：总开关、悬浮窗开关、无障碍引导、电池白名单、
│                     测试上滑、复制诊断日志、清除学习记录、词库入口
├── AdSkipService     ★核心：无障碍服务，300ms 轮询 + 广告判定状态机 + 手势分发
├── AdLearner         广告特征自学习（JSON 持久化 files/ad_learner.json）
├── WordStore         三类关键词库（持久化 SharedPreferences word_lists_v1）
├── WordEditActivity  词库增删改界面
├── OverlayBubble     "记为广告"悬浮球（WindowManager TYPE_APPLICATION_OVERLAY）
├── Prefs             SharedPreferences 键常量
└── Diag              环形缓冲 400 行 + 文件日志（128KB 截断，3 天过期）
```

## 4. 核心实现逻辑

### 4.1 无障碍服务配置（res/xml/accessibility_service_config.xml）

```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowContentChanged|typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="150"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"/>
```

`canPerformGestures` 是模拟滑动的前提；`flagReportViewIds` 支持采集 viewId。

### 4.2 主循环：事件驱动 + 轮询兜底

`onAccessibilityEvent` 每次事件把 300ms 的 poller 重新排队——事件密集时靠事件驱动，安静时靠轮询兜底：

```java
public void onAccessibilityEvent(AccessibilityEvent event) {
    if (isEnabled()) {
        handler.removeCallbacks(poller);
        handler.post(poller);          // 事件触发立即评估
    }
}
// poller: evaluate() → postDelayed(this, POLL_INTERVAL_MS=300)
```

### 4.3 界面文本采集

- `getRootInActiveWindow()` 拿活动窗口；拿不到时降级遍历 `getWindows()`（多窗口场景）
- **只处理包名 == `com.phoenix.read` 的窗口**，其他应用直接隐藏悬浮球返回
- 递归遍历节点树（深度≤30）：采集 `getText()`/`getContentDescription()` 非空节点
  - 带坐标节点（≤80 个）：需 `getBoundsInScreen` 与屏幕有 ≥8px 交集（过滤不可见项）
  - 松散文本（≤120 条）：不做可见性过滤，供倒计时/hint 识别

### 4.4 广告判定：五路信号（evaluate 核心）

| 信号 | 判定方式 | 位置约束 |
|---|---|---|
| marker | `isAdLabel`（"广告"+标点）或 WordStore 强标记词 | 无（topMarker 才要求上半屏） |
| topMarker | 同上且 `rect.top < screenH/2` | 有 |
| learned | AdLearner 特征命中 ≥2 个 | **无**（缺陷，见第 6 节） |
| ecomCombo | 电商词计数 ≥ 阈值 | **无**（缺陷） |
| countdown | 正则 `(\d{1,2})\s*秒后` 匹配"X秒后可滑动"类文案 | — |

hint（"还在倒计时不能滑"）判定：文本含"秒后"且含"上滑/滑动/继续"，或含"倒计时结束后即可滑动"；带"观看"字样的变体（"上滑继续**观看**短剧"是广告页提示）特殊处理；"划"字归一为"滑"（对抗错别字变体）。

信号组合：

```java
boolean adSignal  = marker || learned || ecomCombo;
boolean topSignal = topMarker || learned || ecomCombo;
boolean signalNow = adSignal && hint;          // 稳定计时条件
boolean stable = 持续 >= SIGNAL_STABLE_MS;      // 防抖
```

### 4.5 滑动触发与防抖状态机

触发条件：`!hint && adSwipeCount < 3 && adSignal && topSignal && stable`

```java
private void performSwipe() {
    DisplayMetrics m = getResources().getDisplayMetrics();
    Path path = new Path();
    path.moveTo(m.widthPixels / 2f, 0.75f * m.heightPixels);  // 起点：75% 高度
    path.lineTo(m.widthPixels / 2f, 0.25f * m.heightPixels);  // 终点：25% 高度
    GestureDescription g = new GestureDescription.Builder()
        .addStroke(new GestureDescription.StrokeDescription(path, 0, 260)).build();
    dispatchGesture(g, null, null);
}
```

防抖参数：每场广告最多滑 3 次；首滑间隔 ≥900ms、补滑 ≥2.5s；手动标记后抑制 12s；倒计时期间（读秒+2s）不滑；倒计时把 `adWindowEnd = now + seconds*1000 + 2000`。会话边界：广告信号消失 1s 重置会话计数。

### 4.6 自学习闭环（AdLearner）

- **存储**：`files/ad_learner.json`，`{ads: [[特征...]], whitelist: [...]}`；≤50 类广告 × 每类 ≤40 特征，白名单 ≤400 条
- **自动学习**（原版）：强标记/电商组合触发自动滑动后，把"可学习文本"记入特征库 —— **误学污染源，重写时移除**
- **手动学习**：点悬浮球 → `recordAd(整屏文本)` → 立即滑走 + 抑制 12s
- **归并**：与已有类特征重叠度 ≥50%（`common*2 >= features.size()`）则合并入该类
- **匹配**：当前屏幕特征与某类广告交集 ≥2 即判 learned
- **归一化**：剔除数字/空白/标点/货币符（`¥￥$.,：|—·()【】""!？~+#@&%…`）；泛型词黑名单（"上滑继续""秒后""倒计时"等 8 个短语）不学习

### 4.7 白名单防误学

连续 5 秒无任何广告信号（且弱词 <2）的界面 → 整屏特征加入白名单（30s 冷却）。白名单特征在 matchAd 时排除。**原版缺陷：白名单不影响 marker/ecom 通道**。

### 4.8 悬浮球（OverlayBubble）

WindowManager `TYPE_APPLICATION_OVERLAY`，左上角 x=8dp、y=屏高 30%；点击回调 `markCurrentAdInternal()`（学习+滑走）。仅在红果前台且开关开启时显示。**原版缺陷：看剧易误触**——重写建议改为长按触发或加确认。

### 4.9 词库系统（WordStore）

三类词库，用户可在 WordEditActivity 增删改，SharedPreferences 持久化，为空时回落默认：

| 类型 | 默认词（节选） | 作用 |
|---|---|---|
| MARKER(0) 强标记 | 查看详情、点击进入游戏、点击进入直播间、立即下载、领券购买、进店逛逛…（14 个） | 直接广告证据 |
| ECOM(1) 电商 | 下单、抢购、补贴、限时、优惠、包邮、百亿补贴、券后、运费险…（38 个） | 计数 ≥ 阈值判广告 |
| WEAK(2) 弱信号 | 下载、红包、福利、详情、不感兴趣…（38 个） | 辅助（≥2 个在上半屏计入 minTop） |

### 4.10 诊断日志（Diag）

内存 ArrayDeque 400 行 + `getExternalFilesDir/diag.log` 文件（>128KB 截尾、3 天过期清空）。每 1.2s 输出一帧判定快照（pkg/读秒/标记/学习/电商/提示/稳定/已滑/ids/文本摘要），主界面一键复制——远程调试闭环。

## 5. 与真实广告页的对照（实测验证）

对两类真实广告页（电商直播带货，"广告"角标在右上角 ~8% 屏高）：

| 页面特征 | 有读秒广告页 | 无读秒广告页 |
|---|---|---|
| 底部提示 | "5秒后可继续上滑**观看**短剧 ⇈" | "上滑继续**观看**短剧 ⌃" |
| 判定路径 | countdown 识别读秒 5s → 读秒结束 hint=false → marker 通道触发 | hint 恒 false → marker 通道直接触发 |
| marker 来源 | "广告"角标（上半屏）+"点击进入直播间"+"查看详情" | 同左 |

**关键差异**：广告页提示带"**观看**"二字；正片翻集提示是"上滑继续**看**短剧"（无"观看"）。原版反而给正片提示 +2 电商加分导致正片被滑走——修复补丁 1 正是利用这个文案差异做短路。

## 6. 原版缺陷与修复参数（★重写必须继承）

| # | 缺陷 | 原版行为 | 修复后（hongguo-fixed.apk 已验证） |
|---|---|---|---|
| 1 | 正片被滑走 | 提示含"上滑继续看短剧"→ ecom **+2** 助攻误判 | 匹配到该串 → ecom **-99**（电商通道整体失效） |
| 2 | 泛词凑数误判 | ecomCombo 阈值 **3** | 阈值 **5** |
| 3 | 抖动误触 | 稳定时长 **1500ms** | **2500ms** |
| 4 | 学习库污染 | 自动滑动后整屏学习 | **关闭自动学习**；仅保留悬浮球手动学习 |
| 5 | 白名单失效 | 白名单只作用于 learned 通道 | （smali 未修，重写时实现：marker/ecom 命中前过滤白名单文本） |
| 6 | 悬浮球误触 | 点击=学习整屏+滑走 | （smali 未修，重写时实现：长按触发/二次确认） |

修复版工程：apktool 输出 `C:\Users\49360\AppData\Local\Temp\hongguo-apktool`（AdSkipService.smali 四处补丁），签名证书见仓库外 `tools\android-signing\hongguo.keystore`。

## 7. HongGuoSkip 重写要点

1. **判定主力是 marker 通道**（"广告"角标 + 强标记词，上半屏位置约束）——它误报率最低、对两类真实广告页全覆盖；learned/ecom 只能作辅助信号，**不得**独立满足 adSignal+topSignal
2. 电商/弱词命中文本须先过白名单过滤（缺陷 5）
3. 正片短路：检测到正片提示文案（"上滑继续看短剧"，不含"观看"）→ 本轮禁滑（缺陷 1 的语义化实现）
4. 学习只保留人工标记通道；标记动作与滑动解耦（缺陷 6 的交互重设计：悬浮球长按=标记，点击=仅测试滑动）
5. hint/倒计时识别保留正则方案，另加"广告页提示含观看"的文案区分
6. 状态机防抖参数沿用修复版：稳定 2500ms / 每场 ≤3 次 / 首滑 900ms / 补滑 2500ms / 标记抑制 12s
7. 悬浮球、词库管理、诊断日志（一键复制）三个配套功能对等保留；诊断日志格式尽量兼容原版便于对照
