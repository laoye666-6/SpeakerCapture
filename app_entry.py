# -*- coding: utf-8 -*-
"""EXE 打包入口:无参数 → 图形界面;带参数 → 命令行模式。"""

import ctypes
import sys

if len(sys.argv) > 1:
    # 从真实终端调用时,把输出接回父控制台,CLI 参数才有可见输出
    try:
        ctypes.windll.kernel32.AttachConsole(-1)
        sys.stdout = open("CONOUT$", "w", buffering=1)
        sys.stderr = open("CONOUT$", "w", buffering=1)
    except OSError:
        pass
    from speaker_capture import main
    main(sys.argv[1:])
else:
    from speaker_capture_gui import run_gui
    run_gui()
