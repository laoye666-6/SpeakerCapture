# -*- coding: utf-8 -*-
"""speaker_capture_gui.py — 扬声器录音机的 tkinter 图形界面。"""

import os
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import soundcard as sc

import speaker_capture as core


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("扬声器录音机 — 抓取本机播放的音频")
        root.resizable(False, False)

        self.recorder = None
        self.ui_queue = queue.Queue()
        self.devices = []

        self._build_ui()
        self._load_devices()
        self._poll_queue()

    # ------------------------------------------------------------------ #
    def _build_ui(self):
        pad = {"padx": 8, "pady": 6}
        frm = ttk.Frame(self.root)
        frm.pack(fill="both", expand=True)

        row = 0
        ttk.Label(frm, text="采集设备:").grid(row=row, column=0, sticky="e", **pad)
        self.device_var = tk.StringVar()
        self.device_box = ttk.Combobox(frm, textvariable=self.device_var,
                                       state="readonly", width=42)
        self.device_box.grid(row=row, column=1, columnspan=2, sticky="w", **pad)

        row += 1
        ttk.Label(frm, text="输出格式:").grid(row=row, column=0, sticky="e", **pad)
        self.wav_var = tk.BooleanVar(value=True)
        self.mp3_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(frm, text="WAV", variable=self.wav_var)\
            .grid(row=row, column=1, sticky="w", **pad)
        ttk.Checkbutton(frm, text="MP3", variable=self.mp3_var)\
            .grid(row=row, column=2, sticky="w", padx=8)
        ttk.Label(frm, text="比特率:").grid(row=row, column=2, sticky="e", **pad)

        row += 1
        self.bitrate_var = tk.StringVar(value="192")
        ttk.Combobox(frm, textvariable=self.bitrate_var, values=["128", "192", "256", "320"],
                     width=6, state="readonly").grid(row=row, column=2, sticky="w", padx=8)
        ttk.Label(frm, text="kbps").grid(row=row, column=2, sticky="e", padx=(90, 8))

        row += 1
        ttk.Label(frm, text="采样率:").grid(row=row, column=0, sticky="e", **pad)
        self.rate_var = tk.StringVar(value="44100")
        ttk.Combobox(frm, textvariable=self.rate_var, values=["44100", "48000"],
                     width=6, state="readonly").grid(row=row, column=1, sticky="w", **pad)
        ttk.Label(frm, text="Hz").grid(row=row, column=1, sticky="e", padx=(0, 8))

        row += 1
        ttk.Label(frm, text="保存目录:").grid(row=row, column=0, sticky="e", **pad)
        self.outdir_var = tk.StringVar(
            value=os.path.abspath(os.path.join(os.path.dirname(__file__), "recordings")))
        ttk.Entry(frm, textvariable=self.outdir_var, width=44)\
            .grid(row=row, column=1, columnspan=2, sticky="w", **pad)
        ttk.Button(frm, text="浏览…", command=self._browse, width=8)\
            .grid(row=row, column=2, sticky="e", padx=8)

        row += 1
        btns = ttk.Frame(frm)
        btns.grid(row=row, column=0, columnspan=3, pady=10)
        self.start_btn = ttk.Button(btns, text="开始录制", command=self._start, width=14)
        self.start_btn.pack(side="left", padx=8)
        self.stop_btn = ttk.Button(btns, text="停止", command=self._stop,
                                   width=14, state="disabled")
        self.stop_btn.pack(side="left", padx=8)

        row += 1
        self.level_bar = ttk.Progressbar(frm, length=360, maximum=100)
        self.level_bar.grid(row=row, column=0, columnspan=3, pady=(2, 0), padx=8)

        row += 1
        self.status_var = tk.StringVar(value="就绪。点击「开始录制」抓取扬声器正在播放的声音。")
        ttk.Label(frm, textvariable=self.status_var, anchor="w")\
            .grid(row=row, column=0, columnspan=3, sticky="ew", **pad)

    def _browse(self):
        d = filedialog.askdirectory(initialdir=self.outdir_var.get() or ".")
        if d:
            self.outdir_var.set(d)

    def _load_devices(self):
        try:
            self.devices = core.list_loopback_devices()
            names = [m.name for m in self.devices]
            self.device_box["values"] = names
            if names:
                try:
                    default_mic = core.default_loopback()
                    idx = next(i for i, m in enumerate(self.devices)
                               if m.id == default_mic.id)
                except Exception:
                    idx = 0
                self.device_box.current(idx)
            else:
                self.status_var.set("未找到可环回采集的输出设备。")
        except Exception as e:
            messagebox.showerror("设备枚举失败", str(e))

    # ------------------------------------------------------------------ #
    def _start(self):
        if not self.wav_var.get() and not self.mp3_var.get():
            messagebox.showwarning("提示", "请至少选择一种输出格式(WAV 或 MP3)。")
            return
        if not self.devices:
            messagebox.showerror("错误", "没有可用的采集设备。")
            return
        mic = self.devices[self.device_box.current()]
        formats = ([] + (["wav"] if self.wav_var.get() else [])
                   + (["mp3"] if self.mp3_var.get() else []))
        self.recorder = core.SpeakerRecorder(
            mic,
            sample_rate=int(self.rate_var.get()),
            formats=formats,
            bitrate=int(self.bitrate_var.get()),
            outdir=self.outdir_var.get(),
        )
        self.recorder.start()
        self.start_btn["state"] = "disabled"
        self.stop_btn["state"] = "normal"
        self.device_box["state"] = "disabled"
        self.status_var.set(f"正在录制…  设备:{mic.name}")

    def _stop(self):
        if self.recorder and self.recorder.recording:
            self.stop_btn["state"] = "disabled"
            self.status_var.set("正在收尾,等待编码完成…")
            self.recorder.stop()

    def _poll_queue(self):
        try:
            while True:
                msg = self.ui_queue.get_nowait()
                self._handle(msg)
        except queue.Empty:
            pass

        # 更新录制进度
        r = self.recorder
        if r and r.recording:
            self.level_bar["value"] = r.level * 100
            self.status_var.set(
                f"正在录制…  已录 {r.seconds_captured:.1f} 秒  设备:{r.mic.name}")
        elif r and not r.recording and self.start_btn["state"] == "disabled":
            self._finished()

        self.root.after(100, self._poll_queue)

    def _finished(self):
        r = self.recorder
        self.start_btn["state"] = "normal"
        self.stop_btn["state"] = "disabled"
        self.device_box["state"] = "readonly"
        self.level_bar["value"] = 0
        if r is None:
            return
        if r.error:
            messagebox.showerror("录制出错", str(r.error))
            self.status_var.set(f"录制出错:{r.error}")
            return
        files = [p for p in (r.wav_path, r.mp3_path) if p and os.path.exists(p)]
        size = sum(os.path.getsize(p) for p in files) / 1024 / 1024
        self.status_var.set(
            f"完成:共 {r.seconds_captured:.1f} 秒,已保存 {len(files)} 个文件({size:.2f} MB)。")
        if files:
            try:
                os.startfile(os.path.normpath(self.outdir_var.get()))
            except OSError:
                pass

    def _on_close(self):
        if self.recorder and self.recorder.recording:
            if not messagebox.askokcancel("退出", "正在录制,确定停止并退出吗?"):
                return
            self.recorder.stop()
            self.recorder.wait(timeout=5)
        self.root.destroy()


def run_gui():
    root = tk.Tk()
    app = App(root)
    root.protocol("WM_DELETE_WINDOW", app._on_close)
    root.mainloop()


if __name__ == "__main__":
    run_gui()
