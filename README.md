# Wallpaper Engine Sound Fix

[English](#english) | [中文](#中文)

---

## 中文

### 简介

这是一个 Android Xposed 模块，用于修复 **Wallpaper Engine** Android 版的声音问题。

### 问题背景

Wallpaper Engine Android 版存在以下声音问题：

1. **视频壁纸静音**：应用硬编码调用 `MediaPlayer.setVolume(0, 0)`，导致视频壁纸完全没有声音
2. **场景壁纸音频失效**：原生音频引擎 `libscenejni.so` 中的 `AndroidMediaExtensions` 类的音频函数全部是空壳实现（stub），场景壁纸中的音频对象从未真正播放

### 功能特性

- 修复视频壁纸的音轨播放
- 修复场景壁纸中视频元素的音轨播放
- 修复场景壁纸中独立音频对象（Sound）的播放
- 在设置界面注入音量控制滑条（0-100）
- 音量修改即时生效，无需重启壁纸
- 壁纸暂停/恢复时自动同步音频状态

### 技术原理

#### 路径 A：视频壁纸修复（Java 层）

- Hook `MediaPlayer.setVolume` 方法
- 拦截静音调用 `(0, 0)`，替换为用户设置的音量值
- 防止 hook 被内联优化失效（deoptimize 调用方方法）

#### 路径 B：场景音频修复（Native 层）

由于 Android linker namespace 隔离机制，模块无法直接调用 `libscenejni.so` 的函数。解决方案：

1. 从 `/proc/self/maps` 定位 `libscenejni.so` 的加载基址
2. 直接解析进程内存中的 ELF64 结构（program headers、dynamic 段、dynsym）
3. 找到 `_ZTV22AndroidMediaExtensions` 虚表和 13 个音频函数的运行时地址
4. 校验虚表槽位与函数地址一致性（防止版本变化导致错误替换）
5. 使用 `mprotect` 修改虚表内存权限，替换虚表槽位为自定义实现
6. 自定义实现通过 JNI 桥接到 Java 层的 `SoundBridge`（使用 `MediaPlayer` 播放）

#### 路径 C：音频录制器同步

- Hook `GLWallpaperEngine.updatePausedState`
- 壁纸暂停时同步停止 `AudioRecorder`（FFT 采集）和所有声音
- 壁纸恢复时同步启动，避免 CPU 浪费

### 修复范围

| 壁纸类型 | 视频音轨 | 场景音频 |
|---------|---------|---------|
| 视频壁纸 | ✓ | - |
| 场景壁纸（含视频元素） | ✓ | ✓ |
| 纯场景壁纸 | - | ✓ |

> **注意**：场景音频修复仅支持 **arm64-v8a** 架构设备

### 环境要求

- Android 9.0+（API 28+）
- LSPosed 或其他支持 Xposed API v101 的框架
- Wallpaper Engine for Android（包名：`io.wallpaperengine.weclient`）
- arm64-v8a 架构设备（用于场景音频修复）

### 下载

#### 方式一：GitHub Releases 下载（推荐）

1. 访问 [Releases 页面](https://github.com/nxdyy/WallpaperEngineSoundFix/releases)
2. 下载最新版本的 `app-release.apk` 文件
3. 文件大小约 1-2 MB

#### 方式二：网盘下载

从网盘下载资助作者😍！！！

[https://1816907939.share.123pan.cn/123pan/7lCyVv-EWEY?pwd=bili# 提取码：bili](https://1816907939.share.123pan.cn/123pan/7lCyVv-EWEY?pwd=bili#)

#### 方式三：自行构建

```bash
# 克隆项目
git clone https://github.com/nxdyy/WallpaperEngineSoundFix.git
cd WallpaperEngineSoundFix

# 构建 Release APK
./gradlew assembleRelease

# APK 输出路径
# app/build/outputs/apk/release/app-release.apk
```

### 安装使用

1. 下载并安装 APK
2. 在 LSPosed 管理器中启用本模块
3. 作用域勾选「Wallpaper Engine」
4. 强制停止 Wallpaper Engine 并重新打开
5. 打开 Wallpaper Engine 设置 → General，找到「壁纸音量/Wallpaper volume」滑条
6. 调整音量（默认 100 即恢复原声音量）

### 项目结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt        # Native 构建配置
│   └── soundfix.cpp          # Native 层虚表替换实现
├── java/io/wallpaper/soundfix/
│   ├── HookEntry.kt          # Xposed 模块入口，Hook 逻辑
│   ├── MainActivity.kt       # 主界面（显示使用说明）
│   ├── SoundBridge.kt        # Java 层音频播放桥接
│   └── SoundFixNative.kt     # Native 方法声明
└── resources/META-INF/xposed/
    ├── java_init.list         # Xposed 初始化配置
    ├── module.prop            # 模块属性
    └── scope.list             # 作用域配置
```

### 依赖

- [libxposed-api](https://github.com/libxposed/api) v101.0.1 - Xposed API
- AndroidX AppCompat
- Material Design Components

---

## English

### Introduction

This is an Android Xposed module that fixes sound issues in **Wallpaper Engine** for Android.

### Problem

Wallpaper Engine for Android has the following sound issues:

1. **Video wallpapers are silent**: The app hardcodes `MediaPlayer.setVolume(0, 0)`, resulting in no sound for video wallpapers
2. **Scene wallpaper audio is broken**: The native audio engine `libscenejni.so` has stub implementations for all audio functions in `AndroidMediaExtensions` class, so audio objects in scene wallpapers never actually play

### Features

- Fix video wallpaper audio playback
- Fix video element audio in scene wallpapers
- Fix standalone audio objects (Sound) in scene wallpapers
- Inject volume control slider (0-100) in settings UI
- Volume changes take effect immediately without restarting wallpaper
- Automatic audio state sync when wallpaper pauses/resumes

### Technical Details

#### Path A: Video Wallpaper Fix (Java Layer)

- Hook `MediaPlayer.setVolume` method
- Intercept silent calls `(0, 0)` and replace with user-defined volume
- Prevent hook from being inlined (deoptimize caller methods)

#### Path B: Scene Audio Fix (Native Layer)

Due to Android linker namespace isolation, the module cannot directly call functions from `libscenejni.so`. Solution:

1. Locate `libscenejni.so` base address from `/proc/self/maps`
2. Parse ELF64 structures directly from process memory (program headers, dynamic section, dynsym)
3. Find `_ZTV22AndroidMediaExtensions` vtable and 13 audio function runtime addresses
4. Verify vtable slot consistency with function addresses (prevent incorrect replacement due to version changes)
5. Use `mprotect` to modify vtable memory permissions, replace vtable slots with custom implementations
6. Custom implementations bridge to Java `SoundBridge` via JNI (using `MediaPlayer` for playback)

#### Path C: Audio Recorder Sync

- Hook `GLWallpaperEngine.updatePausedState`
- Synchronously stop `AudioRecorder` (FFT capture) and all sounds when wallpaper pauses
- Synchronously start when wallpaper resumes, avoiding CPU waste

### Fix Coverage

| Wallpaper Type | Video Audio | Scene Audio |
|---------------|-------------|-------------|
| Video Wallpaper | ✓ | - |
| Scene Wallpaper (with video elements) | ✓ | ✓ |
| Pure Scene Wallpaper | - | ✓ |

> **Note**: Scene audio fix only supports **arm64-v8a** architecture devices

### Requirements

- Android 9.0+ (API 28+)
- LSPosed or other framework supporting Xposed API v101
- Wallpaper Engine for Android (package: `io.wallpaperengine.weclient`)
- arm64-v8a architecture device (for scene audio fix)

### Download

#### Option 1: GitHub Releases (Recommended)

1. Visit the [Releases page](https://github.com/nxdyy/WallpaperEngineSoundFix/releases)
2. Download the latest `app-release.apk` file
3. File size is approximately 1-2 MB

#### Option 2: Cloud Storage Download

Download from cloud storage to support the author 😍!!!

[https://1816907939.share.123pan.cn/123pan/7lCyVv-EWEY?pwd=bili# password：bili](https://1816907939.share.123pan.cn/123pan/7lCyVv-EWEY?pwd=bili#)

#### Option 3: Build from Source

```bash
# Clone project
git clone https://github.com/nxdyy/WallpaperEngineSoundFix.git
cd WallpaperEngineSoundFix

# Build Release APK
./gradlew assembleRelease

# APK output path
# app/build/outputs/apk/release/app-release.apk
```

### Installation

1. Download and install the APK
2. Enable this module in LSPosed Manager
3. Check "Wallpaper Engine" in scope
4. Force stop Wallpaper Engine and reopen
5. Open Wallpaper Engine Settings → General, find "Wallpaper volume" slider
6. Adjust volume (default 100 restores original sound level)

### Project Structure

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt        # Native build configuration
│   └── soundfix.cpp          # Native vtable replacement implementation
├── java/io/wallpaper/soundfix/
│   ├── HookEntry.kt          # Xposed module entry, hook logic
│   ├── MainActivity.kt       # Main activity (shows instructions)
│   ├── SoundBridge.kt        # Java audio playback bridge
│   └── SoundFixNative.kt     # Native method declarations
└── resources/META-INF/xposed/
    ├── java_init.list         # Xposed initialization config
    ├── module.prop            # Module properties
    └── scope.list             # Scope configuration
```

### Dependencies

- [libxposed-api](https://github.com/libxposed/api) v101.0.1 - Xposed API
- AndroidX AppCompat
- Material Design Components
