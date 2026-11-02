# HongGuoSkip

自动跳过红果短剧（`com.phoenix.read`）广告页的 Android 无障碍工具。基于 `docs/原app逆向分析.md` 的技术基线重写，**判定逻辑与防抖参数以分析文档第 6/7 节"修复版参数"为准**。

完全离线：无网络权限、无第三方依赖（纯 Java + 系统 API）。

## 原理

无障碍服务 300ms 轮询（事件驱动 + 轮询兜底）红果界面文本 → 五路信号判定"当前是广告页" → 等倒计时结束 → `dispatchGesture` 模拟上滑（起点 75% 屏高 → 终点 25% 屏高，260ms）。

## 与原版（hongguo.apk）的差异：6 项缺陷修复

| # | 缺陷 | 本工程实现 |
|---|---|---|
| 1 | 正片被滑走（"上滑继续看短剧"给电商 +2 助攻） | 检测到正片提示文案（不含"观看"）→ 本轮禁滑 + 电商通道短路（-99） |
| 2 | 泛词凑数误判 | ecomCombo 阈值 **5**（原 3） |
| 3 | 抖动误触 | 信号稳定时长 **2500ms**（原 1500） |
| 4 | 自动滑动后整屏学习污染特征库 | **移除自动学习**，仅保留悬浮球长按手动标记 |
| 5 | 白名单只作用于 learned 通道 | marker/ecom/learned 命中前统一做白名单**文本级过滤** |
| 6 | 悬浮球点击误触（学习+滑走一步到位） | **长按=标记**（学习+抑制 12s，不自动滑），**单击=仅测试滑动**；标记与滑动解耦 |

另外按重写要点 1：**marker 通道为判定主力**——滑动触发条件额外要求 `markerHit` 必须在场，learned/ecom 只能作辅助信号，不得独立满足 `adSignal+topSignal`。

## 工程结构

```
app/src/main/java/com/hongguoskip/
├── MainActivity      设置界面：总开关、悬浮窗开关、无障碍引导、电池白名单、
│                     测试上滑、复制诊断日志、清除学习记录、词库入口
├── AdSkipService     ★核心：无障碍服务，300ms 轮询 + 广告判定状态机 + 手势分发
├── AdLearner         广告特征自学习（files/ad_learner.json，仅人工通道）
├── WordStore         三类关键词库（强标记14/电商38/弱信号38，word_lists_v1）
├── WordEditActivity  词库增删改界面
├── OverlayBubble     悬浮球（TYPE_APPLICATION_OVERLAY，单击测试/长按标记）
├── Prefs             SharedPreferences 键常量
└── Diag              环形缓冲 400 行 + 文件日志（128KB 截断、3 天过期）
```

## 构建

Android Studio（Hedgehog 以上，JDK 17）直接打开工程根目录，`Build > Build APK(s)` 即可；或命令行：

```bash
gradle :app:assembleDebug
```

- minSdk 26 / targetSdk 34 / AGP 8.5.2 / Gradle 8.7
- 本仓库不含 gradle-wrapper.jar，命令行构建需自行安装 Gradle 或在 Android Studio 中生成 wrapper（`gradle wrapper`）

## 使用

1. 安装后打开 App，按界面状态卡提示：系统无障碍设置里启用「红果跳广告服务」；
2. 需要悬浮球时授予悬浮窗权限并打开开关；
3. 到红果短剧播放页：检测到广告页（"广告"角标 + 强标记词在上半屏，信号稳定 2.5s）会自动上滑跳过；有读秒的广告会等读秒结束 +2s 再滑；
4. 漏判的广告页：等悬浮球出现后**长按**悬浮球把当前页记为广告（自动滑动抑制 12 秒，之后同类页面可被 learned 通道辅助识别）；
5. 误判/调试：主界面「复制诊断日志」导出 `EVAL` 判定帧（每 1.2s 一帧，含五路信号与文本摘要）。

## 安全边界

- 仅监控 `com.phoenix.read` 的界面，其他应用窗口直接忽略；
- 悬浮球仅在红果前台且开关开启时显示；
- 无网络权限，词库/学习库/日志全部留在本机。
