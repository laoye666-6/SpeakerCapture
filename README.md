# 扬声器录音机(Speaker Capture)

抓取 Windows 扬声器**正在播放**的音频,保存为 **WAV** 和 **MP3**。

原理:WASAPI 环回采集(loopback)——直接从系统混音器读取本机播放的
声音流,不需要开启"立体声混音"设备,也不需要麦克风;MP3 由内置的
lameenc 编码,无需安装 ffmpeg。

> 注意:与网络爬虫无关;"爬取"的对象是本机系统内播放的声音。
> 录制受版权保护的音乐仅供个人学习欣赏,请勿用于商业传播。

## 下载成品

无需自己编译,直接到 [Releases](https://github.com/laoye666-6/SpeakerCapture/releases/latest) 下载:

- `SpeakerCapture.exe` — Windows 单文件绿色版,无需 Python 环境,双击即用
- `SpeakerCapture.apk` — Android 版(Android 10+)

## 项目结构

| 路径 | 说明 |
|---|---|
| `speaker_capture.py` | Windows 核心模块 + 命令行界面 |
| `speaker_capture_gui.py` | tkinter 图形界面 |
| `app_entry.py` | EXE 打包入口(带参数→命令行,无参数→图形界面) |
| `android/` | Android 原生工程(Java + MediaProjection) |
| `android/mp3test/` | jump3r 编码器的桌面 JVM 验证脚本 |
| `recordings/` | 默认输出目录(录制时自动创建,不入库) |

## 安装

需要 Python 3.8+(在 Windows 上运行)。

```bash
pip install soundcard numpy lameenc
```

## 使用

### 图形界面

```bash
python speaker_capture.py --gui
# 或
python speaker_capture_gui.py
```

选择采集设备 → 勾选 WAV / MP3 → 设定比特率和保存目录 → 点击「开始录制」,
播放任意音乐,点「停止」(或关闭窗口)即保存。

### 命令行

```bash
# 列出可采集的输出设备
python speaker_capture.py --list-devices

# 一直录制,直到 Ctrl+C(默认同时输出 WAV + MP3 @192kbps)
python speaker_capture.py

# 录 60 秒后自动停止
python speaker_capture.py --duration 60

# 只输出 MP3,320kbps,采样率 48000
python speaker_capture.py --format mp3 --bitrate 320 --rate 48000

# 指定设备(编号来自 --list-devices)和输出目录
python speaker_capture.py --device 0 --outdir D:\Music --name my_song
```

常用参数:

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--format wav/mp3/both` | `both` | 输出格式 |
| `--bitrate` | `192` | MP3 比特率(kbps) |
| `--rate` | `44100` | 采样率(Hz) |
| `--duration` | 不限 | 录制秒数,到点自动停止 |
| `--device` | 系统默认扬声器 | 采集哪个输出设备的环回 |
| `--outdir` / `--name` | `recordings` / `capture` | 输出目录 / 文件名前缀 |

## 工作方式

录制在后台线程实时进行:每一块音频同时写入 WAV(边录边写,即使程序
被强杀也有完整/可恢复的 WAV)并喂给 LAME 编码器,停止时收尾写出 MP3。

## 常见问题

- **录出来是静音?** 确认正在播放声音的程序用的是你采集的那块输出
  设备(默认跟系统默认扬声器一致);用 `--list-devices` 换设备试试。
- **独占模式(WASAPI Exclusive/ASIO)播放时抓不到?** 独占模式绕过
  系统混音器,环回采集不到,属 Windows 限制。
- **游戏/浏览器无声但音乐软件有声?** 检查系统音量合成器里该程序
  是否被静音——环回采的是最终混音,静音了就没有信号。

## 打包 EXE

已打包好的成品在 `packaging/exe/SpeakerCapture.exe`(单文件,约 25MB,
无需安装 Python)。双击打开图形界面;从终端带参数运行则进入命令行模式:

```bash
packaging\exe\SpeakerCapture.exe --duration 60
```

重新打包(需要 `pip install pyinstaller`):

```bash
cd speaker_capture
python -m PyInstaller --onefile --windowed --name SpeakerCapture ^
    --collect-all soundcard app_entry.py ^
    --distpath packaging/exe --workpath packaging/build --specpath packaging
```

## Android 版(APK)

Android 没有 Windows 的 WASAPI 环回,系统声音必须走 **MediaProjection
音频回放采集**(Android 10 / API 29+,每次开始录制需在系统弹窗中授权,
且仅能抓到允许被采集的媒体音频——绝大多数音乐/视频 App 默认允许)。
因此 Android 版是原生 Java 重写,功能与 Windows 版对齐:

- MediaProjection 前台服务采集系统混音(USAGE_MEDIA/GAME/UNKNOWN)
- WAV 实时写入 + jump3r(纯 Java LAME)编码 MP3,无需 NDK
- 成品自动发布到系统音乐库 `Music/SpeakerCapture/`
- 内置「播放测试音」按钮,验证采集链路

构建(需 JDK 17 与 Android SDK,Gradle 会在首次构建时自动补齐
platform/build-tools 组件):

```bash
cd android
set JAVA_HOME=C:\Users\<你>\.jdks\jdk-17.0.20.1+1
set ANDROID_HOME=C:\Users\<你>\Android
gradle assembleDebug
```

产物:`android/app/build/outputs/apk/debug/app-debug.apk`,
可直接传到手机安装(允许"未知来源"即可;debug 签名仅供个人使用)。

Android 版限制:受 DRM 保护的内容(如 Netflix)、通话音频抓不到;
部分 App 可声明拒绝被采集;采集音量跟随系统媒体音量。

## 第三方依赖

- [SoundCard](https://github.com/bastibe/SoundCard)(MIT)—— Windows 端 WASAPI 环回采集
- [numpy](https://numpy.org)(BSD)—— 音频数据处理
- [lameenc](https://pypi.org/project/lameenc/)(LGPL)—— Windows 端 MP3 编码(LAME)
- [jump3r](https://github.com/Sciss/jump3r)(LGPL)—— Android 端 MP3 编码(LAME 的纯 Java 移植)

本项目代码以 [MIT License](LICENSE) 发布;各依赖库遵循其自身许可证。

## License

[MIT](LICENSE)

