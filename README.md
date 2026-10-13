# HarmonyTouping

基于 [HOScrcpy](https://gitcode.com/OpenHarmonyToolkitsPlaza/HOScrcpy) 官方 SDK 的鸿蒙真机投屏工具，UI 层使用 **Kotlin Multiplatform / Compose Desktop** 重写（替代官方的 Java Swing UI），只保留投屏与控制能力。

## 功能

- 设备发现：`hdc list targets`（本地 + 可配置的远程主机）
- 实时投屏：H.264 / H.265 视频流（按流内容自动识别）→ FFmpeg 软解 → Skia 渲染
- 输入注入：
  - 触控：鼠标左键按下/拖动/抬起映射为触摸事件
  - 鼠标模式（2in1 设备自动开启，也可手动切换）：完整鼠标事件（左/中/右键、拖拽、滚轮），需设备 `uitest` 版本 >= 5.1.1.3
  - 键盘：字母/数字/符号/方向键等，`Shift` 组合、`Ctrl+V` 粘贴剪贴板文本
- 物理按键：电源、返回、音量+/-、重启
- 首帧/旋转时窗口自适应居中

官方 Swing UI 中的其它功能（控件树 Inspector、Layout 导入导出、录制、拖拽图片预览等）均已移除。

## 环境要求

- JDK 21+（开发构建和运行打包产物都需要）
- `hdc` 命令可用（随 DevEco Studio 提供，需在 PATH 中；也可在「设置」里指定绝对路径）
- 已开启 USB/无线调试的鸿蒙真机或模拟器

## 运行

```bash
./gradlew :desktopApp:run
```

## 打包

### 单文件 uber jar（java -jar 启动）

```bash
./gradlew :desktopApp:packageUberJarForCurrentOS
```

产物：`desktopApp/build/compose/jars/harmonytouping-macos-arm64-1.0.0.jar`（约 126MB），
已内嵌 Compose/Skia、FFmpeg 原生库（macOS arm64/x86_64、Windows x86_64）与官方 SDK，
目标机器只需安装 **JDK 21+** 即可直接启动，无需 gradle：

```bash
java -jar desktopApp/build/compose/jars/harmonytouping-macos-arm64-1.0.0.jar
```

注意事项：

- 官方 HOScrcpy.jar 是 Java 8 字节码，老 JRE 可跑；本项目因使用 Compose/Kotlin 2.x，**要求 JDK 21+**
- 需要支持 Linux 时，在 `desktopApp/build.gradle.kts` 中追加 `org.bytedeco:ffmpeg:6.0-1.5.9:linux-x86_64`（或 `linux-arm64`）classifier 后重新打包
- `java -jar` 能否找到 hdc 取决于运行环境的 PATH；找不到时在应用「设置」里填 hdc 绝对路径

### 系统安装包 / 可分发目录

```bash
./gradlew :desktopApp:createDistributable   # 生成自带运行时的可分发目录
./gradlew :desktopApp:packageDmg            # macOS 安装包
```

## 架构

```
├── shared (KMP commonMain，纯 Kotlin，可跨平台复用)
│   ├── key/KeyCodeMapper.kt      桌面按键 -> OpenHarmony uinput 键值映射（移植官方 KeyCodeUtil）
│   └── geom/ViewportMath.kt      画面等比缩放与坐标换算（移植官方 getActualClickPoint）
└── desktopApp (JVM)
    ├── device/HdcCli.kt          hdc 命令行封装：设备发现 / shell 执行 / 配置持久化
    ├── mirror/VideoStreamDecoder.kt FFmpeg(javacpp) H.264/H.265 解码 + sws 转 RGBA（移植官方 decodeH264，增加编码自动识别）
    ├── mirror/MirrorSession.kt   官方 SDK HosRemoteDevice 封装：状态机 + 视频回调 + 输入注入
    └── ui/App.kt                 Compose UI：设备栏 / 投屏画面 / 控制栏 / 设置对话框
```

投屏链路（与官方一致）：

```
hdc 推送 server so → uitest start-daemon 启动 scrcpy server
→ hdc fport 端口转发 → gRPC 拉流（SDK 内部完成）
→ onData(裸流分片) → 识别 H.264/H.265 → FFmpeg parser 重组/解码 → sws 转 RGBA → Skia ImageBitmap 渲染
```

说明：不同系统版本的 scrcpy server 输出编码不同（6.x 系统 server 输出 H.265，HarmonyOS 7.0 的 unix_6.6 server 视 so 版本可能输出 H.264），因此解码器在首个包含参数集的分片到达后按 NAL 类型自动识别；SDK 断流重连（onReady 再次回调）时会重置解码器重新识别。

输入链路：

- 触控/鼠标事件走 SDK 的 socket 通道（快，直接调用）
- 键盘文本、物理按键走 `hdc shell uinput`（慢，异步执行）

## 配置

`~/.harmonytouping/config.properties`：

| 键 | 说明 | 默认 |
|---|---|---|
| `hdcPath` | hdc 可执行文件路径 | `hdc`（PATH 查找） |
| `remoteHosts` | 远程主机 IP 列表（逗号分隔，设备通过 `hdc -s <ip>:8710` 访问） | 空 |

## 依赖说明

- `desktopApp/libs/hosScrcpy-1.0.18-beta.jar`：官方 SDK fat jar（grpc/netty/protobuf/guava/fastjson 已内置），取自官方 [v1.0.16-beta 发布包](https://gitcode.com/OpenHarmonyToolkitsPlaza/HOScrcpy/releases)
  - 注意：v1.0.15-beta（2026-04）及更早的 SDK 内置 server 只支持到 HarmonyOS 6.5，在 7.0 系统（如 Mate X5，API 26）上会报 `set virtual screen surface failed` 导致无画面；1.0.18-beta 新增 unix_6.6 server 修复了该问题
- `org.bytedeco:ffmpeg`：H.265 解码，版本组合与官方发布包一致（javacpp 1.5.9 + ffmpeg 6.0），默认引入 macOS arm64/x86_64 与 Windows x86_64 原生库，其它平台自行按 classifier 增删

## 测试

```bash
./gradlew :desktopApp:test
```

包含真实 H.265 裸流（`src/test/resources/test_stream.h265`，VideoToolbox 编码生成）的解码链路测试，以及按键映射、坐标换算的单元测试。
