# -*- coding: utf-8 -*-
"""speaker_capture.py — 抓取 Windows 扬声器正在播放的音频,保存为 WAV / MP3。

原理:WASAPI 环回采集(loopback),直接从系统混音器读取本机播放的
声音流,无需立体声混音设备,也不依赖 ffmpeg(MP3 由 lameenc 编码)。

用法:
    python speaker_capture.py --list-devices            列出可采集的输出设备
    python speaker_capture.py                           开始录制,Ctrl+C 停止
    python speaker_capture.py --duration 60             录 60 秒后自动停止
    python speaker_capture.py --format mp3 --bitrate 320
    python speaker_capture.py --gui                     启动图形界面
"""

import argparse
import datetime
import os
import sys
import threading
import time
import wave

import numpy as np
import soundcard as sc

try:
    import lameenc
    HAS_LAME = True
except ImportError:
    HAS_LAME = False

DEFAULT_SAMPLE_RATE = 44100
DEFAULT_BITRATE = 192
CHUNK_FRAMES = 1024


def list_loopback_devices():
    """返回所有可环回采集的输出设备(loopback 麦克风)。"""
    result = []
    for m in sc.all_microphones(include_loopback=True):
        if m.isloopback:
            result.append(m)
    return result


def default_loopback():
    """返回默认播放设备的环回采集器。"""
    speaker = sc.default_speaker()
    mic = sc.get_microphone(id=str(speaker.name), include_loopback=True)
    if mic is None:
        raise RuntimeError(
            "未找到默认扬声器对应的环回设备,请用 --list-devices 查看并 --device 手动指定。"
        )
    return mic


def float_to_s16(data: np.ndarray) -> bytes:
    """float [-1,1] 多声道数组 → 交织的 16bit 小端 PCM。"""
    clipped = np.clip(data, -1.0, 1.0)
    return (clipped * 32767.0).astype("<i2").tobytes()


class SpeakerRecorder:
    """在后台线程中采集扬声器环回音频,实时写 WAV,可选同时编码 MP3。"""

    def __init__(self, mic, sample_rate=DEFAULT_SAMPLE_RATE,
                 formats=("wav",), bitrate=DEFAULT_BITRATE,
                 outdir=".", name="capture", channels=2):
        self.mic = mic
        self.sample_rate = sample_rate
        self.formats = [f.lower() for f in formats]
        self.bitrate = int(bitrate)
        self.outdir = outdir
        self.name = name
        self.channels = channels

        self._stop = threading.Event()
        self._thread = None
        self.error = None

        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        base = os.path.join(outdir, f"{name}_{timestamp}")
        self.wav_path = base + ".wav" if "wav" in self.formats else None
        self.mp3_path = base + ".mp3" if "mp3" in self.formats else None

        # 供界面轮询的状态
        self.frames_captured = 0
        self.level = 0.0          # 0~1 的瞬时电平
        self.started_at = None

    @property
    def seconds_captured(self):
        return self.frames_captured / self.sample_rate

    @property
    def recording(self):
        return self._thread is not None and self._thread.is_alive()

    def start(self):
        os.makedirs(self.outdir, exist_ok=True)
        if "mp3" in self.formats and not HAS_LAME:
            raise RuntimeError("未安装 lameenc,无法输出 MP3:pip install lameenc")
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()

    def wait(self, timeout=None):
        if self._thread:
            self._thread.join(timeout)

    # ------------------------------------------------------------------ #
    def _run(self):
        try:
            with self.mic.recorder(samplerate=self.sample_rate,
                                   channels=self.channels,
                                   blocksize=CHUNK_FRAMES) as rec:
                self.started_at = time.time()
                wfile = wave.open(self.wav_path, "wb") if self.wav_path else None
                if wfile:
                    wfile.setnchannels(self.channels)
                    wfile.setsampwidth(2)
                    wfile.setframerate(self.sample_rate)
                enc = None
                if self.mp3_path:
                    enc = lameenc.Encoder()
                    enc.set_bit_rate(self.bitrate)
                    enc.set_in_sample_rate(self.sample_rate)
                    enc.set_channels(self.channels)
                    enc.set_quality(2)  # 2=高,7=快
                mp3_buf = bytearray()
                try:
                    while not self._stop.is_set():
                        data = rec.record(numframes=CHUNK_FRAMES)
                        if data is None or len(data) == 0:
                            time.sleep(0.01)
                            continue
                        self.frames_captured += len(data)
                        # 电平(RMS),供界面显示
                        rms = float(np.sqrt(np.mean(np.square(data))))
                        self.level = min(1.0, rms * 3.0)
                        pcm = float_to_s16(data)
                        if wfile:
                            wfile.writeframes(pcm)
                        if enc:
                            mp3_buf += enc.encode(pcm)
                finally:
                    if enc:
                        mp3_buf += enc.flush()
                    if wfile:
                        wfile.close()
                    if enc and self.mp3_path:
                        with open(self.mp3_path, "wb") as f:
                            f.write(bytes(mp3_buf))
        except Exception as e:  # 线程内异常不能吞掉,传给主线程
            self.error = e


# ---------------------------------------------------------------------- #
def cli_list_devices():
    devices = list_loopback_devices()
    if not devices:
        print("未找到任何支持环回采集的输出设备。")
        return
    print("可采集的输出设备(对应其环回通道):")
    for i, m in enumerate(devices):
        print(f"  [{i}] {m.name}")


def cli_record(args):
    try:
        if args.device is None:
            mic = default_loopback()
        else:
            devices = list_loopback_devices()
            if not (0 <= args.device < len(devices)):
                print(f"设备编号无效(0~{len(devices) - 1}),用 --list-devices 查看。")
                sys.exit(1)
            mic = devices[args.device]
    except Exception as e:
        print(f"初始化失败:{e}")
        sys.exit(1)

    recorder = SpeakerRecorder(
        mic,
        sample_rate=args.rate,
        formats=args.format,
        bitrate=args.bitrate,
        outdir=args.outdir,
        name=args.name,
    )
    print(f"采集设备 : {mic.name}")
    print(f"采样率   : {recorder.sample_rate} Hz")
    print(f"输出格式 : {'+'.join(f.upper() for f in recorder.formats)}"
          + (f" @ {recorder.bitrate} kbps" if recorder.mp3_path else ""))
    print(f"输出目录 : {os.path.abspath(recorder.outdir)}")
    print("开始录制,按 Ctrl+C 停止...\n")

    recorder.start()
    try:
        while recorder.recording:
            time.sleep(0.25)
            bar = "#" * int(recorder.level * 30)
            print(f"\r已录制 {recorder.seconds_captured:8.1f}s  电平 |{bar:<30}|",
                  end="", flush=True)
            if args.duration and recorder.seconds_captured >= args.duration:
                recorder.stop()
        recorder.wait()
    except KeyboardInterrupt:
        print("\n收到停止信号,正在收尾...")
        recorder.stop()
        recorder.wait()

    print()
    if recorder.error:
        print(f"录制出错:{recorder.error}")
        sys.exit(1)
    dur = recorder.seconds_captured
    print(f"录制完成,共 {dur:.1f} 秒。")
    for p in (recorder.wav_path, recorder.mp3_path):
        if p and os.path.exists(p):
            size = os.path.getsize(p) / 1024 / 1024
            print(f"  已保存 {p}  ({size:.2f} MB)")


def build_parser():
    p = argparse.ArgumentParser(
        description="抓取 Windows 扬声器正在播放的音频,保存为 WAV / MP3。")
    p.add_argument("--gui", action="store_true", help="启动图形界面")
    p.add_argument("--list-devices", action="store_true", help="列出可采集的输出设备")
    p.add_argument("--device", type=int, default=None,
                   help="设备编号(来自 --list-devices),默认为系统默认扬声器")
    p.add_argument("--format", nargs="+", default=["both"],
                   choices=["wav", "mp3", "both"], help="输出格式,默认 both")
    p.add_argument("--bitrate", type=int, default=DEFAULT_BITRATE,
                   help="MP3 比特率 kbps,默认 192")
    p.add_argument("--rate", type=int, default=DEFAULT_SAMPLE_RATE,
                   help="采样率 Hz,默认 44100")
    p.add_argument("--duration", type=float, default=None,
                   help="录制时长(秒),不指定则一直录到 Ctrl+C")
    p.add_argument("--outdir", default="recordings", help="输出目录,默认 recordings")
    p.add_argument("--name", default="capture", help="输出文件名前缀,默认 capture")
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    if "both" in args.format:
        args.format = ["wav", "mp3"]
    if args.list_devices:
        cli_list_devices()
        return
    if args.gui:
        from speaker_capture_gui import run_gui
        run_gui()
        return
    cli_record(args)


if __name__ == "__main__":
    main()
