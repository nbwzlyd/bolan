# 波澜投屏 Media3 迁移报告（ExoPlayer 2.15.1 → AndroidX Media3 1.10.1）

> 工程路径：`D:\dev\bolan`
> 目标版本：`androidx.media3:*` **1.10.1**（Media3 当前稳定版，2026-05-12 发布；1.11.0-beta01 同期发布，未采用）
> 说明：**沙盒无 Android SDK/NDK、无法联网拉依赖，以下均为源码级迁移，未进行实际编译验证。** 本迁移把"能机械完成的"全部做完，把"必须结合编译错误迭代的"列出清单交本地 Android Studio 处理。

---

## 一、已完成（机械、可确定正确）

### 1.1 Gradle 依赖迁移（ExoPlayer 2.15.1 AAR → Media3 1.10.1 AAR）

| 模块 | 旧依赖 | 新依赖 |
|---|---|---|
| `VideoPlayModule` | exoplayer-dash / hls / smoothstreaming / extension-rtmp / extension-cronet `2.15.1` | `media3-exoplayer` + `media3-exoplayer-dash/hls/smoothstreaming` + `media3-datasource-rtmp` + `media3-datasource-cronet` + `media3-common` + `media3-datasource` |
| `VideoUi` | `exoplayer-core:2.15.1` | `media3-exoplayer` + `media3-common` + `media3-datasource`（**不引入 `media3-ui` AAR**，见 §2.1） |
| `VideoPlayModule-Lite` | （经 `:VideoUi` 传递） | 显式加 `media3-exoplayer` + `media3-common` + `media3-datasource` |
| `ffmpeg` | （经 `:VideoPlayModule-Lite` 传递） | 显式加 `media3-exoplayer`（包名保留，见 §2.2） |
| `app` | `project(':VideoPlayModule')` / `project(':ffmpeg')` | 同上 + 显式 `media3-exoplayer` + `media3-common` + `media3-datasource` |

- 统一版本号：`versions.gradle` 新增 `ext.media3_version = "1.10.1"`，各模块用 `$media3_version` 引用。
- 已确认工程内**无任何 `com.google.android.exoplayer:` AAR 依赖残留**。

### 1.2 源码包名重映射（脚本 `media3_migrate.py`，68 个含引用的文件全部处理）

规则（详见脚本注释）：
- 子包：`ui→androidx.media3.ui`、`util→androidx.media3.common.util`、`upstream→androidx.media3.datasource`、`extractor→androidx.media3.extractor`、`decoder→androidx.media3.decoder`、`drm/audio/video/text/metadata/mediacodec/offline/scheduler/source/trackselection` → 对应 `androidx.media3.*`。
- 顶层类：`Format/C/MediaItem/Timeline/PlaybackException/TrackGroup(Array)` → `androidx.media3.common.*`；`Player/ExoPlayer/BaseRenderer/...` → `androidx.media3.exoplayer.*`；`TrackSelector/TrackSelection/DefaultTrackSelector` → `androidx.media3.exoplayer.trackselection.*`；`MediaCodecInfo` → `mediacodec.*`；`BandwidthMeter` → `exoplayer.upstream.*` 等。
- **修正项**（ExoPlayer 2.x 包布局 ≠ Media3 包布局）：`source.TrackGroup(Array)`、`source.MediaPeriodId`、`video.VideoSize/ColorInfo`、`audio.AudioAttributes` 在 Media3 实际位于 `androidx.media3.common`，已单独修正。
- `SimpleExoPlayer` → `ExoPlayer`（Media3 已移除 `SimpleExoPlayer`，改用 `ExoPlayer.Builder`）。
- 反射字符串：`MyDefaultExtractorsFactory` 中 FLAC 扩展的 `Class.forName("com.google.android.exoplayer2.ext.flac.*")` → `androidx.media3.extractor.flac.*`（Media3 的 FLAC 已内置在 `media3-extractor`）。

**结果**：残留 `com.google.android.exoplayer2` 引用 **0 处**（除 §2.2 刻意保留的 `ext.ffmpeg` 包名外）。抽样校验：`Format→common.Format`、`C→common.C`、`Player→exoplayer.Player`、`util.Util→common.util.Util`、`upstream→datasource`、`TrackGroupArray→common.TrackGroupArray` 均正确。

### 1.3 目录与 namespace 调整

- `VideoUi` 源码目录由 `com/google/android/exoplayer2/ui/` 整体 `git mv` 到 `androidx/media3/ui/`（26 个文件，含自定义 `ExoPlayerView`/`ExoPlayerControlView`），`namespace` 由 `com.google.android.exoplayer2.ui` 改为 `androidx.media3.ui`。
- `ffmpeg` 的 `package` 与 `namespace` **保持 `com.google.android.exoplayer2.ext.ffmpeg` 不变**（JNI 约束，见 §2.2）。

---

## 二、关键决策与原因

### 2.1 为什么 `VideoUi` 保留为源码、且不引入 `media3-ui` AAR
`VideoUi` 不是单纯的 ExoPlayer UI 源码拷贝——它额外包含工程自定义的 `ExoPlayerView` / `ExoPlayerControlView`，而 `VideoPlayModule-Lite` 的 `BaseView` 等直接继承/引用这两个类。因此**不能删 `VideoUi` 去依赖官方 `media3-ui` AAR**（那样自定义类就没了，且会与 AAR 的 `androidx.media3.ui.PlayerView` 等产生**重复类冲突**）。
→ 做法：把 `VideoUi` 当成"自带 `androidx.media3.ui.*` 实现的库"，只依赖 `media3-exoplayer/-common/-datasource`，**不引入 `media3-ui` AAR**。

### 2.2 为什么 `ffmpeg` 保留 `com.google.android.exoplayer2.ext.ffmpeg` 包名
`ffmpeg` 模块自带预编译原生库（`libffmpeg.so` / `libavcodec.so` 等，位于 `ffmpeg/src/main/jniLibs/`）。JNI 原生符号是按 **`com.google.android.exoplayer2.ext.ffmpeg`** 包名生成的（`Java_com_google_android_exoplayer2_ext_ffmpeg_*`）。若把 Java 包名改成 `androidx.media3.ext.ffmpeg`，`System.loadLibrary` 后调用 native 方法会 `UnsatisfiedLinkError`。
→ 做法：ffmpeg 的 **Java 包名与 namespace 保持不变**，只把其 import 的 ExoPlayer 类型重映射为 `androidx.media3.*`。现有 `.so` 在运行时**仍能正常加载**。

> 长期方案：如需彻底规范化，应**按 Media3 的 ffmpeg JNI 重新编译原生库**，再把包名迁移到 `androidx.media3.ext.ffmpeg`、改为依赖 `media3-ext-ffmpeg` AAR。本轮未做（需 NDK 交叉编译环境）。

---

## 三、仍需本地编译核对的破坏性变更（机械重映射无法修复）

包名迁移只解决"类在哪"，**不解决"类怎么用"**。Media3 相对 ExoPlayer 2.15.1 有大量 API 签名/行为变更，以下问题需在 Android Studio 中 `Sync → Make`，按报错逐项移植：

| # | 破坏性变更 | 涉及范围（已 grep 量化） | 处理方式 |
|---|---|---|---|
| 1 | `Player.EventListener` → `Player.Listener`；回调方法整体改为 `onEvents(Player, Events)` + 细分 `onPlaybackStateChanged` 等 | 7 个文件 | 实现 `Player.Listener`，用 `Events` 判断具体事件；或保留旧方法（Media3 仍提供兼容桥接，但建议迁移） |
| 2 | `DefaultDataSource(ctx)` / `DefaultHttpDataSourceFactory(...)` 构造 → `DefaultDataSource.Factory` / `DefaultHttpDataSource.Factory` | `DefaultDataSource(` 9 处、`DefaultHttpDataSource*` 9 处 | 改为 `Factory(context).createDataSource()`；`MediaSource.Factory` 现接收 `DataSource.Factory` |
| 3 | `ControlDispatcher` / `DefaultControlDispatcher` **已在 Media3 移除** | 仅 `VideoUi` 内 5 个 UI 类（`PlayerControlView`/`PlayerView`/`StyledPlayer*View`/`PlayerNotificationManager`） | 见 §四——`VideoUi` 的 UI 源码需整体移植，这是最大的单点工作量 |
| 4 | `player.prepare(MediaSource)` 弃用 → `player.setMediaSource(...)` + `prepare()` | 5 处 | 改用 `setMediaSource` / `setMediaItems` + `prepare()` |
| 5 | `TrackSelection` / `DefaultTrackSelector` API 变更：`TrackSelection.Factory` → `ExoTrackSelection.Factory`；`DefaultTrackSelector` 构造与 `Parameters` builder 调整 | `VideoPlayModule-Lite`/`VideoPlayModule` 的 trackselector 用法 | 按 Media3 `DefaultTrackSelector` 新版 builder 改写；`MappingTrackSelector`/`SelectionOverride` 逐处核对 |
| 6 | `MediaItem` 取代旧 `MediaSource` 构造入参；`ProgressiveMediaSource`/`HlsMediaSource`/`DashMediaSource` 的 `Factory` 签名调整 | `MediaSourceBuilder`/`WholeMediaSource` 等 | 用 `MediaItem.Builder` 描述；`Factory(dataSourceFactory).createMediaSource(mediaItem)` |
| 7 | `BandwidthMeter`/`DefaultBandwidthMeter`、`LoadControl`/`DefaultLoadControl` 改为 `Builder` 式 | 渲染器/带宽相关 | `.Builder().build()` |
| 8 | `BaseRenderer` 构造与覆写方法签名变更（影响 ffmpeg） | `ffmpeg` 的 `FfmpegAudioRenderer`/`FfmpegDecoder` | 适配 Media3 `BaseRenderer`/`Decoder` 新签名 |
| 9 | `Player.VideoComponent`/`TextComponent` 移除；`VideoSize` 改为 `onVideoSizeChanged(VideoSize)` 监听 | `MediaPlayerAdapter.kt` 等 | 用 `Player.getVideoSize()` / `onVideoSizeChanged` |
| 10 | `Timeline`/`Window`/`Period`、`PlayerMessage`、`Format.Builder` 等细节 API | 多处 | 按 Media3 文档逐项 |

### 四、`VideoUi` 的 ExoPlayer 2.15.1 UI 源码需整体移植（最大工作量）
`VideoUi` 的 26 个类（含 `PlayerView`、`PlayerControlView`、`StyledPlayerView`、`SubtitleView`、`DefaultTimeBar` 及自定义 `ExoPlayerView`/`ExoPlayerControlView`）是 **ExoPlayer 2.15.1 的 UI 源码直接拷贝 + 少量定制**。即便包名已重映射，其内部大量引用了 ExoPlayer 2.15.1 的 Player/组件内部 API（如 §三 #1/#3/#9），与 Media3 的 `androidx.media3.exoplayer.Player` 行为/签名不一致，**无法编译通过**。

**两条可选路线**（建议路线 B，更干净但工作量集中）：
- **路线 A（最小改动）**：保留这 26 个类，逐文件把 Media3 的 Player 新 API（`Player.Listener`、`onEvents`、移除的内部组件等）移植进去。改动分散、易错。
- **路线 B（推荐）**：删除 ExoPlayer 2.15.1 UI 源码拷贝（24 个标准类），仅保留 2 个自定义类 `ExoPlayerView`/`ExoPlayerControlView` 并重写成基于官方 `media3-ui` AAR 的 `PlayerView`/`PlayerControlView` 子类；同时把 `VideoUi` 依赖改为 `implementation "androidx.media3:media3-ui:1.10.1"`，撤掉 §2.1 的"不引入 media3-ui"约束。这样 UI 实现完全交给官方库，后续跟随 Media3 升级零成本。

> 无论哪条路线，`VideoUi` 都必须在本地编译中先跑通，否则上层 `VideoPlayModule-Lite`（继承 `ExoPlayerView` 等）会连带报错。

### 五、`ffmpeg` 模块的原生库重建（长期）
- 现有 `.so` 是 ExoPlayer 2.15.1 的 ffmpeg JNI 构建，包名匹配 `com.google.android.exoplayer2.ext.ffmpeg`，**本轮因包名未动而可继续加载**。
- 一旦未来把 ffmpeg 包名迁到 `androidx.media3.ext.ffmpeg` 或改用 `media3-ext-ffmpeg` AAR，必须用 NDK 按 Media3 的 ffmpeg 扩展 JNI 重新编译 `libffmpeg.so` 等（注意现有各 ABI 的 `.so` 本身就不齐：仅 `armeabi-v7a` 完整，且工程只编 `armeabi-v7a`）。

---

## 四、本地验证步骤

1. **环境**：Android Studio（AGP 8.3.2 需 AS Hedgehog+/JDK 17）、Gradle 8.5、联网可访问 `google()`/`mavenCentral()`/阿里云镜像。
2. 打开 `D:\dev\bolan` → **Sync Project with Gradle Files**（先解决依赖解析；确认 `media3-*` 1.10.1 能下载）。
3. **Make Project**（`assembleDebug`）。预期首批报错集中在：
   - `VideoUi`（ExoPlayer 2.15.1 UI 内部 API，见 §四）→ 按路线 A/B 处理；
   - `VideoPlayModule(-Lite)`（§三 #1/#2/#4/#5/#6/#7）的 `Player.Listener`、DataSource Factory、`prepare`→`setMediaSource`、`TrackSelector` 改写；
   - `ffmpeg`（§三 #8）的 `BaseRenderer`/`Decoder` 签名。
4. 按报错循环修复 → 真机回归：本地/网络视频播放、FLAC 音频、RTMP 直播源、DLNA 投屏、网页投屏。
5. 注意 `.so` 加载：确认 `ffmpeg` 模块 `libffmpeg.so` 等仍能 `loadLibrary` 成功（包名未变，应无碍）。

---

## 五、附：本次迁移改动文件清单（概要）

- 依赖：`versions.gradle`（加 `media3_version`）、`app/build.gradle`、`VideoPlayModule/build.gradle`、`VideoPlayModule-Lite/build.gradle`、`VideoUi/build.gradle`、`ffmpeg/build.gradle`
- 命名空间：`VideoUi/build.gradle`（`namespace → androidx.media3.ui`）
- 源码重映射（68 文件）：`VideoPlayModule(-Lite)`、`VideoUi`（已迁移目录）、`ffmpeg`（仅 import）、`app/src/main`（4 个 .kt 直接引用）
- 目录移动：`VideoUi/src/main/java/{com/google/android/exoplayer2/ui → androidx/media3/ui}`（git rename）
- 工具脚本：`media3_migrate.py`（可重复运行的重映射脚本，确认无误后可删除）
