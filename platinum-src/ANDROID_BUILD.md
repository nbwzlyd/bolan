# Android DLNA 编译指南

本文档介绍如何在 Android 项目中使用 Platinum UPnP SDK 编译 DLNA 投屏功能。

## 目录

- [前置要求](#前置要求)
- [CMake 编译方式](#cmake-编译方式)
- [集成到 Android 项目](#集成到-android-项目)
- [16KB 页面对齐（Android 15+）](#16kb-页面对齐android-15)
- [常见问题](#常见问题)

## 前置要求

- Android Studio 或 Android SDK
- NDK r25 或更高版本（推荐 r27+）
- CMake 3.18.1 或更高版本
- minSdkVersion >= 23

## CMake 编译方式

### 1. 目录结构

```
项目根目录/
├── app/
│   └── src/main/
│       ├── jni/
│       │   ├── CMakeLists.txt    # CMake 构建配置
│       │   └── git-platinum.cpp  # JNI 桥接代码
│       └── jniLibs/
│           ├── arm64-v8a/
│           │   ├── libgit-platinum.so
│           │   └── libc++_shared.so
│           └── armeabi-v7a/
│               ├── libgit-platinum.so
│               └── libc++_shared.so
└── platinum-src/                  # 本仓库
    ├── Source/
    └── ThirdParty/
        └── Neptune/
```

### 2. CMakeLists.txt 配置

在你的 `app/src/main/jni/CMakeLists.txt` 中添加：

```cmake
cmake_minimum_required(VERSION 3.18.1)
project(git-platinum)

set(CMAKE_CXX_STANDARD 11)

# Platinum 源码路径
set(PLATINUM_ROOT "${CMAKE_SOURCE_DIR}/../../../../platinum-src")
set(NEPTUNE_ROOT "${PLATINUM_ROOT}/ThirdParty/Neptune")

# 头文件目录
include_directories(
    ${PLATINUM_ROOT}/Source/Platinum
    ${PLATINUM_ROOT}/Source/Core
    ${PLATINUM_ROOT}/Source/Devices/MediaRenderer
    ${PLATINUM_ROOT}/Source/Devices/MediaServer
    ${PLATINUM_ROOT}/Source/Devices/MediaConnect
    ${PLATINUM_ROOT}/Source/Extras
    ${NEPTUNE_ROOT}/Source/Core
    ${NEPTUNE_ROOT}/Source/System/Posix
    ${NEPTUNE_ROOT}/Source/System/Bsd
    ${NEPTUNE_ROOT}/Source/System/Android
    ${NEPTUNE_ROOT}/Source/System/Null
)

# 编译定义
add_definitions(-DANDROID -DNPT_CONFIG_HAVE_SYSTEM_LOG_CONFIG)

# Release 优化
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_definitions(-DNDEBUG)
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -Os -fvisibility=hidden -fvisibility-inlines-hidden")
    set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -Os -fvisibility=hidden")
endif()

# 源文件
file(GLOB NEPTUNE_CORE_SOURCES ${NEPTUNE_ROOT}/Source/Core/*.cpp)
file(GLOB NEPTUNE_POSIX_SOURCES ${NEPTUNE_ROOT}/Source/System/Posix/*.cpp)
file(GLOB NEPTUNE_BSD_SOURCES ${NEPTUNE_ROOT}/Source/System/Bsd/*.cpp)
file(GLOB NEPTUNE_ANDROID_SOURCES ${NEPTUNE_ROOT}/Source/System/Android/*.cpp)
file(GLOB NEPTUNE_NULL_SOURCES ${NEPTUNE_ROOT}/Source/System/Null/NptNullAutoreleasePool.cpp)
set(NEPTUNE_STDC_SOURCES ${NEPTUNE_ROOT}/Source/System/StdC/NptStdcEnvironment.cpp)
file(GLOB PLATINUM_CORE_SOURCES ${PLATINUM_ROOT}/Source/Core/*.cpp)
file(GLOB PLATINUM_MEDIARENDERER_SOURCES ${PLATINUM_ROOT}/Source/Devices/MediaRenderer/*.cpp)

# 生成共享库
add_library(git-platinum SHARED
    git-platinum.cpp
    ${NEPTUNE_CORE_SOURCES}
    ${NEPTUNE_POSIX_SOURCES}
    ${NEPTUNE_BSD_SOURCES}
    ${NEPTUNE_ANDROID_SOURCES}
    ${NEPTUNE_NULL_SOURCES}
    ${NEPTUNE_STDC_SOURCES}
    ${PLATINUM_CORE_SOURCES}
    ${PLATINUM_MEDIARENDERER_SOURCES}
)

# 链接库
target_link_libraries(git-platinum
    android
    log
    z
)
```

### 3. app/build.gradle 配置

```gradle
android {
    defaultConfig {
        ndk {
            abiFilters "armeabi-v7a", "arm64-v8a"
            stl "c++_shared"
            version "27.0.12077973"  // 指定 NDK 版本
        }
        externalNativeBuild {
            cmake {
                arguments "-DANDROID_STL=c++_shared", "-DANDROID_PLATFORM=android-23", "-DCMAKE_BUILD_TYPE=Release"
                cFlags "-fPIC"
                cppFlags "-fPIC"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path file('src/main/jni/CMakeLists.txt')
            version '3.22.1'
        }
    }
}
```

### 4. 编译命令

```bash
# 编译 Release 版本
./gradlew :app:externalNativeBuildRelease

# 或者完整编译
./gradlew :app:assembleRelease
```

编译产物路径：
- `app/build/intermediates/cxx/Release/{hash}/obj/arm64-v8a/libgit-platinum.so`
- `app/build/intermediates/cxx/Release/{hash}/obj/armeabi-v7a/libgit-platinum.so`

## 集成到 Android 项目

### 1. 拷贝 so 文件

将编译好的 `.so` 文件拷贝到 `app/src/main/jniLibs/` 对应架构目录下：

```bash
cp libgit-platinum.so app/src/main/jniLibs/arm64-v8a/
cp libgit-platinum.so app/src/main/jniLibs/armeabi-v7a/
```

同时需要拷贝 `libc++_shared.so`（从 NDK 目录获取）：

```bash
# NDK r27+
cp $NDK/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so app/src/main/jniLibs/arm64-v8a/
cp $NDK/toolchains/llvm/prebuilt/*/sysroot/usr/lib/arm-linux-androideabi/libc++_shared.so app/src/main/jniLibs/armeabi-v7a/
```

### 2. Java 层加载

```java
public class PlatinumJniProxy {
    static {
        System.loadLibrary("git-platinum");
    }
    
    // JNI 方法声明
    public native int startEngine(String name, String uuid);
    public native int stopEngine();
    // ... 其他方法
}
```

## 16KB 页面对齐（Android 15+）

从 Android 15（API 35）开始，系统支持 16KB 内存页大小。为了确保兼容性，ELF 文件的 LOAD 段需要 16KB 对齐。

### 配置方式

在 CMakeLists.txt 中添加链接器选项：

```cmake
# 方式一：全局设置
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

# 方式二：目标级别设置（推荐）
target_link_options(git-platinum PRIVATE 
    -Wl,-z,max-page-size=16384 
    -Wl,-z,common-page-size=16384
)
```

### 验证对齐

使用 `readelf` 工具验证：

```bash
llvm-readelf -l libgit-platinum.so | grep LOAD
```

输出示例（对齐值为 0x4000 = 16384 = 16KB）：

```
  LOAD           0x000000 ... R E 0x4000
  LOAD           0x055a30 ... RW  0x4000
```

### NDK 版本要求

- **arm64-v8a**：NDK r25+ 的 `libc++_shared.so` 已支持 16KB 对齐
- **armeabi-v7a**：32 位架构通常使用 4KB 页，不受影响

## 常见问题

### 1. UnsatisfiedLinkError: dlopen failed: library "libc++_shared.so" not found

**原因**：缺少 C++ 标准库。

**解决**：
- 确保 `stl "c++_shared"` 配置正确
- 手动将 `libc++_shared.so` 拷贝到 jniLibs 目录
- 或者使用静态 STL：`stl "c++_static"`

### 2. 编译报错：找不到头文件

**原因**：include 目录配置不正确。

**解决**：检查 `include_directories` 中的路径是否正确，确保相对路径基于 CMakeLists.txt 所在位置。

### 3. 16KB 对齐未生效

**原因**：链接器选项未正确传递。

**解决**：
- 使用 `target_link_options` 而不是 `CMAKE_CXX_FLAGS`
- 确认选项格式为 `-Wl,-z,max-page-size=16384`
- 使用 `llvm-readelf -l` 验证

### 4. JNI 方法未找到

**原因**：JNI 方法签名不匹配。

**解决**：使用 `javah` 或 `javac -h` 生成正确的 JNI 头文件，确保方法名符合规范。

## 参考资源

- [Platinum UPnP SDK](https://github.com/plutinosoft/Platinum)
- [Android NDK 文档](https://developer.android.com/ndk)
- [16KB 页面对齐支持](https://developer.android.com/about/versions/15/features/16kb-page-size)
