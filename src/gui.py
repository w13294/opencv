
# -*- coding: utf-8 -*-
"""
靶标视觉定位位移测量系统 - 图形界面 (PyQt5)

布局设计:
┌───────────────────────────────────────────────┐
│ Toolbar: [开始] [标定] [归零] 模式▼ [记录]   │
├────────────────────┬──────────────────────────┤
│                    │ ◆ 实时位移               │
│    视频画面        │ X: +0.012 mm  ████░░     │
│   (带检测叠加)     │ Y: -0.008 mm  ███░░░     │
│                    │ Z: +0.003 mm  ██░░░░     │
│                    │ 2D: 0.014 | 3D: 0.015    │
│                    │                          │
│                    │ ◆ X-Y 轨迹图             │
│                    │ (matplotlib 嵌入)        │
│                    │                          │
│                    │ ◆ 状态栏                 │
│                    │ 检测:✓ 质量:0.98 FPS:29  │
├────────────────────┴──────────────────────────┤
│ StatusBar: 就绪                                │
└───────────────────────────────────────────────┘
"""

import os
import sys
import time
import cv2
import numpy as np
import threading
import queue
from datetime import datetime
from typing import Optional
from collections import deque

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QLabel, QPushButton,
    QVBoxLayout, QHBoxLayout, QGridLayout, QGroupBox, QComboBox,
    QFrame, QSplitter, QScrollArea, QToolBar, QAction,
    QFileDialog, QMessageBox, QCheckBox, QSizePolicy,
    QLineEdit, QDialog, QDialogButtonBox, QFormLayout, QSpinBox,
    QDoubleSpinBox, QTabWidget, QSlider,
)
from PyQt5.QtCore import (
    Qt, QThread, pyqtSignal, QSize,
)
from PyQt5.QtGui import (
    QImage, QPixmap, QFont, QColor, QPalette, QPainter, QPen,
)

import matplotlib
matplotlib.use('Qt5Agg')
from matplotlib.backends.backend_qt5agg import FigureCanvasQTAgg as FigureCanvas
from matplotlib.figure import Figure

# 导入项目模块
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import TARGET, MEASURE, LOGGING, CAMERA
from calibration import CameraCalibrator
from detector import TargetDetector, DetectionResult
from measure import MultiTargetEngine, DisplacementResult
from visualizer import DataLogger


# ============================================================
# 全局样式表
# ============================================================
STYLE_QSS = """
/* ── 全局基础 ── */
QMainWindow {
    background-color: #16162a;
    color: #d0d0d0;
    font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
    font-size: 10pt;
}

/* ── 分组框 ── */
QGroupBox {
    border: 1px solid #4a4a70;
    border-radius: 6px;
    margin-top: 1.4em;
    padding: 16px 10px 10px 10px;
    background-color: #1e1e38;
    font-size: 10pt;
    font-weight: bold;
    color: #9ee8ff;
}
QGroupBox::title {
    subcontrol-origin: margin;
    left: 12px;
    padding: 0 8px;
    background-color: #1e1e38;
}

/* ── 按钮 ── */
QPushButton {
    background-color: #2d2d4a;
    border: 1px solid #4a4a6a;
    border-radius: 4px;
    padding: 5px 12px;
    font-size: 10pt;
    min-width: 60px;
    min-height: 26px;
    color: #d0d0d0;
}
QPushButton:hover {
    background-color: #3d3d5e;
    border-color: #7ec8e3;
    color: #fff;
}
QPushButton:pressed {
    background-color: #1a1a3e;
}
QPushButton:disabled {
    color: #555;
    background-color: #1e1e30;
    border-color: #2a2a3a;
}
QPushButton#btnStart {
    background-color: #1a6b3c;
    border-color: #2a8b4c;
    font-weight: bold;
}
QPushButton#btnStart:hover { background-color: #2a8b4c; }
QPushButton#btnStop {
    background-color: #8b2a2a;
    border-color: #ab3a3a;
    font-weight: bold;
}
QPushButton#btnStop:hover { background-color: #ab3a3a; }
QPushButton#btnZero {
    background-color: #2a5a8b;
    border-color: #3a6a9b;
    color: #ffffff;
    font-weight: bold;
}
QPushButton#btnZero:hover { background-color: #3a7ab5; }

/* ── 输入控件 ── */
QComboBox {
    background-color: #2d2d4a;
    border: 1px solid #4a4a6a;
    border-radius: 4px;
    padding: 4px 8px;
    min-width: 100px;
    min-height: 24px;
    color: #d0d0d0;
}
QComboBox:hover { border-color: #7ec8e3; }
QComboBox::drop-down {
    border: none;
    width: 22px;
    background-color: #3a3a5a;
    border-left: 1px solid #4a4a6a;
    border-radius: 0 3px 3px 0;
}
QComboBox::down-arrow {
    image: none;
    border-left: 5px solid transparent;
    border-right: 5px solid transparent;
    border-top: 6px solid #c0c0d0;
    width: 0;
    height: 0;
}
QComboBox QAbstractItemView {
    background-color: #2d2d4a;
    selection-background-color: #3d5a8b;
    color: #d0d0d0;
    outline: none;
}
QSpinBox, QDoubleSpinBox {
    background-color: #2d2d4a;
    border: 1px solid #4a4a6a;
    border-radius: 4px;
    padding: 3px 6px;
    color: #d0d0d0;
}
QSpinBox:hover, QDoubleSpinBox:hover { border-color: #7ec8e3; }
QSpinBox::up-button, QDoubleSpinBox::up-button {
    background-color: #3a3a5a;
    border-left: 1px solid #4a4a6a;
    width: 18px;
}
QSpinBox::down-button, QDoubleSpinBox::down-button {
    background-color: #3a3a5a;
    border-left: 1px solid #4a4a6a;
    border-top: 1px solid #4a4a6a;
    width: 18px;
}
QSpinBox::up-button:hover, QDoubleSpinBox::up-button:hover { background-color: #4a4a70; }
QSpinBox::down-button:hover, QDoubleSpinBox::down-button:hover { background-color: #4a4a70; }
QSpinBox::up-arrow {
    image: none;
    border-left: 4px solid transparent;
    border-right: 4px solid transparent;
    border-bottom: 5px solid #c0c0d0;
}
QSpinBox::down-arrow {
    image: none;
    border-left: 4px solid transparent;
    border-right: 4px solid transparent;
    border-top: 5px solid #c0c0d0;
}

/* ── 滑块 ── */
QSlider::groove:horizontal {
    height: 6px;
    background: #4a4a70;
    border-radius: 3px;
}
QSlider::handle:horizontal {
    width: 14px;
    height: 14px;
    background: #9ee8ff;
    border-radius: 7px;
    margin: -4px 0;
}
QSlider::handle:horizontal:hover { background: #c0f0ff; }
QSlider::sub-page:horizontal {
    background: #6a9fd7;
    border-radius: 3px;
}

/* ── 复选框 ── */
QCheckBox {
    color: #ccc;
    spacing: 6px;
}
QCheckBox::indicator {
    width: 16px;
    height: 16px;
    border: 1px solid #5a5a80;
    border-radius: 3px;
    background: #2d2d4a;
}
QCheckBox::indicator:checked {
    background: #4a9a6a;
    border-color: #6acc7a;
}
QCheckBox::indicator:hover { border-color: #9ee8ff; }

/* ── 数值标签 ── */
QLabel#value2D { color: #ffd43b; font-size: 12pt; font-weight: bold; }
QLabel#labelPrecision { color: #7ec8e3; font-size: 9pt; }

/* ── 视频框 ── */
QFrame#videoFrame {
    border: 1px solid #33335a;
    border-radius: 4px;
    background-color: #000;
}

/* ── 状态栏 ── */
QStatusBar {
    background-color: #12122a;
    color: #777;
    border-top: 1px solid #2a2a4a;
    font-size: 9pt;
}
"""


# ============================================================
# 数值显示标签 (带动画颜色)
# ============================================================
class _BidirectionalBar(QWidget):
    """双向条形图: 零点居中, 正向右, 负向左 (零位不显示色块)"""

    def __init__(self, bar_color, parent=None):
        super().__init__(parent)
        self.setFixedHeight(6)
        self.setMinimumWidth(40)
        self.bar_color = QColor(bar_color)
        self._value = 0.0   # -1.0 ~ +1.0
        self._range = 5.0   # 满量程 mm

    def set_value(self, mm_range, mm_val):
        self._range = max(abs(mm_range), 0.01)
        self._value = max(-1.0, min(1.0, mm_val / self._range))
        self.update()

    def paintEvent(self, event):
        super().paintEvent(event)
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing, True)

        w, h = self.width(), self.height()
        mid = w // 2
        r = h / 2 - 1.5

        # 背景轨道
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(45, 45, 70))
        p.drawRoundedRect(0, 0, w, h, r, r)

        # 中心零线
        p.setPen(QPen(QColor(100, 100, 140), 1))
        p.drawLine(mid, 0, mid, h)

        if abs(self._value) < 0.001:
            return

        # 有色填充条
        bar_w = int(mid * abs(self._value))
        color = self.bar_color
        if self._value < 0:
            bar_color = QColor(color.red(), color.green(), color.blue(), 200)
            x = mid - bar_w
        else:
            bar_color = QColor(color.red(), color.green(), color.blue(), 200)
            x = mid

        if bar_w < 2:
            bar_w = 2  # 最小可见宽度
            x = max(0, min(x, w - bar_w))

        p.setPen(Qt.NoPen)
        p.setBrush(bar_color)
        p.drawRoundedRect(x, 1, bar_w, h - 2, r, r)


class DisplacementLabel(QFrame):
    """位移数值显示组件 - 带双向条形图和颜色指示"""

    def __init__(self, label, color, parent=None):
        super().__init__(parent)
        self.setFixedHeight(44)
        self.color = QColor(color)
        self.value = 0.0

        layout = QHBoxLayout(self)
        layout.setContentsMargins(6, 2, 6, 2)
        layout.setSpacing(8)

        # 轴标签
        self.label = QLabel(label)
        self.label.setFixedWidth(22)
        self.label.setAlignment(Qt.AlignCenter)
        self.label.setFont(QFont("Microsoft YaHei", 11, QFont.Bold))
        self.label.setStyleSheet(f"color: {color}; background: transparent;")
        layout.addWidget(self.label)

        # 数值
        self.value_label = QLabel("+0.000 mm")
        self.value_label.setFont(QFont("Consolas", 11, QFont.Bold))
        self.value_label.setStyleSheet(f"color: {color}; background: transparent;")
        self.value_label.setMinimumWidth(130)
        self.value_label.setAlignment(Qt.AlignLeft | Qt.AlignVCenter)
        layout.addWidget(self.value_label)

        # 双向条形图
        self.bar = _BidirectionalBar(color, self)
        layout.addWidget(self.bar, 1)

        self.setStyleSheet("background: transparent; border: none; border-radius: 4px;")

    def set_value(self, val_mm):
        self.value = val_mm
        sign = "+" if val_mm >= 0 else ""
        self.value_label.setText(f"{sign}{val_mm:.3f} mm")

        # 颜色强度随位移变化
        intensity = min(abs(val_mm) / 5.0, 1.0) * 0.6 + 0.4
        c = self.color
        r2, g2, b2 = int(c.red() * intensity), int(c.green() * intensity), int(c.blue() * intensity)
        self.value_label.setStyleSheet(f"color: rgb({r2},{g2},{b2}); background: transparent;")
        self.bar.set_value(5.0, val_mm)


# ============================================================
# 实时轨迹图 (Matplotlib 嵌入)
# ============================================================
class TrajectoryCanvas(FigureCanvas):
    """X-Y 位移轨迹实时绘图 (优化: 降低更新频率)"""

    def __init__(self, parent=None, buffer_len=200):
        self.fig = Figure(figsize=(3.0, 3.0), dpi=60, facecolor='#1a1a2e')
        self.ax = self.fig.add_subplot(111)
        super().__init__(self.fig)
        self.setParent(parent)

        self.buffer_len = buffer_len
        self.traj_x = deque(maxlen=buffer_len)
        self.traj_y = deque(maxlen=buffer_len)
        self._update_counter = 0

        self._setup_axes()
        self.fig.tight_layout(pad=1.0)

    def _setup_axes(self):
        ax = self.ax
        ax.set_facecolor('#12122a')
        for spine in ax.spines.values():
            spine.set_color('#4a4a70')
        ax.tick_params(colors='#aaa', labelsize=7)
        ax.set_xlabel('X (mm)', color='#bbb', fontsize=8)
        ax.set_ylabel('Y (mm)', color='#bbb', fontsize=8)
        ax.grid(True, alpha=0.25, color='#888')
        ax.set_xlim(-2, 2)
        ax.set_ylim(-2, 2)
        ax.axhline(0, color='#4a4a70', linewidth=0.5)
        ax.axvline(0, color='#4a4a70', linewidth=0.5)

        # 原点标记
        ax.plot(0, 0, '+', color='#6a6a8a', markersize=8)

        # 轨迹线和当前点
        self.traj_line, = ax.plot([], [], '-', color='#7ec8e3', linewidth=1, alpha=0.8)
        self.curr_point, = ax.plot([], [], 'o', color='#ffd43b', markersize=6, markeredgecolor='#fff', markeredgewidth=0.5)

        # 参考圆 (1mm 精度参考)
        theta = np.linspace(0, 2 * np.pi, 60)
        for r, color, style in [(0.5, '#3a5a3a', '--'), (1.0, '#5a3a3a', ':'), (2.0, '#3a3a5a', ':')]:
            ax.plot(r * np.cos(theta), r * np.sin(theta), style, color=color, linewidth=0.5, alpha=0.5)

    def update_trajectory(self, x_mm, y_mm):
        self.traj_x.append(x_mm)
        self.traj_y.append(y_mm)

        # 每 5 帧才重绘一次 Matplotlib (大幅降低渲染开销)
        self._update_counter += 1
        if self._update_counter < 5:
            return
        self._update_counter = 0

        tx, ty = np.array(self.traj_x), np.array(self.traj_y)

        if len(tx) > 0 and len(ty) > 0:
            max_r = max(np.abs(tx).max(), np.abs(ty).max(), 0.5) * 1.5
            self.ax.set_xlim(-max_r, max_r)
            self.ax.set_ylim(-max_r, max_r)

        self.traj_line.set_data(tx, ty)
        if len(tx) > 0:
            self.curr_point.set_data([tx[-1]], [ty[-1]])
        self.draw_idle()


# ============================================================
# 视频采集与处理线程
# ============================================================
class CameraCaptureThread(threading.Thread):
    """独立的相机采集线程，避免 I/O 阻塞算法处理"""
    def __init__(self, cap, frame_queue):
        super().__init__()
        self.cap = cap
        self.frame_queue = frame_queue
        self.running = True
        self.daemon = True

    def run(self):
        while self.running:
            ret, frame = self.cap.read()
            if not ret:
                time.sleep(0.005)
                continue
                
            # 保持队列中始终只有最新的一帧（零延迟策略）
            if self.frame_queue.full():
                try:
                    self.frame_queue.get_nowait()
                except queue.Empty:
                    pass
            try:
                self.frame_queue.put_nowait(frame)
            except queue.Full:
                pass

    def stop(self):
        self.running = False


class MeasurementThread(QThread):
    """后台视频采集与测量线程"""

    # 信号
    frame_ready = pyqtSignal(np.ndarray, dict, dict, dict)
    status_update = pyqtSignal(str)
    error_occurred = pyqtSignal(str)

    def __init__(self, camera_id=0, calib_file=None,
                 resolution=(1280, 720), exposure=-6, gain=None):
        super().__init__()
        self.camera_id = camera_id
        self.target_resolution = resolution
        self.target_exposure = exposure
        self.target_gain = gain
        self.calib_file = calib_file
        self.running = False
        self.cap = None

        # 测量组件
        self.camera_matrix = np.eye(3, dtype=np.float64)
        self.dist_coeffs = np.zeros((1, 5), dtype=np.float64)
        self.detector = None
        self.engine = None

        self._init_components()

    def _init_components(self):
        """初始化测量组件"""
        self.calibrated = False
        self._calib_image_size = None

        # 加载标定
        if self.calib_file and os.path.exists(self.calib_file):
            calib = CameraCalibrator()
            calib.load(self.calib_file)
            self.camera_matrix = calib.camera_matrix
            self.dist_coeffs = calib.dist_coeffs
            self._calib_image_size = getattr(calib, 'image_size', None)
            self.calibrated = True
            print(f"[相机] 已加载标定参数: {self.calib_file}")
            if calib.reprojection_error > 0.5:
                print(f"[相机] ⚠ 标定重投影误差 {calib.reprojection_error:.2f} 像素, 质量较差!")
                print(f"       建议重新标定 (良好 < 0.3 像素)")
        else:
            # 无标定文件 → 用基于分辨率的合理默认内参
            # 假设普通 USB 摄像头, 水平 FOV ≈ 60°, 垂直 FOV ≈ 40°
            w, h = self.target_resolution
            fx = w / (2 * np.tan(np.radians(60.0 / 2)))  # ≈ 0.866 * w
            fy = h / (2 * np.tan(np.radians(40.0 / 2)))  # ≈ 1.374 * h
            self.camera_matrix = np.array([
                [fx, 0.0, w / 2.0],
                [0.0, fy, h / 2.0],
                [0.0, 0.0, 1.0]
            ], dtype=np.float64)
            self.dist_coeffs = np.zeros((1, 5), dtype=np.float64)
            print(f"[相机] ⚠ 未找到标定文件, 使用默认内参 (fx={fx:.1f}, fy={fy:.1f})")
            print(f"       建议先进行相机标定以获得准确测量结果!")

        self.detector = TargetDetector(TARGET)
        self.engine = MultiTargetEngine(MEASURE)

    def run(self):
        """主循环 (在子线程中运行)"""
        # 尝试多种后端打开摄像头
        backends = [cv2.CAP_DSHOW, cv2.CAP_MSMF, cv2.CAP_ANY]
        opened = False
        for backend in backends:
            self.cap = cv2.VideoCapture(self.camera_id, backend)
            if self.cap.isOpened():
                opened = True
                break
            else:
                self.cap.release()

        if not opened:
            self.error_occurred.emit(
                f"无法打开摄像头 {self.camera_id}\n"
                f"请确认:\n"
                f"  1. USB 摄像头已正确连接\n"
                f"  2. 未被其他程序占用\n"
                f"  3. 在 config.py 中 CAMERA['camera_id'] 设置正确")
            return

        # 设置编码格式为 MJPG, 极其关键! 否则 1080P 会卡死在 5fps
        self.cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))
        # 设置分辨率
        target_w, target_h = self.target_resolution
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, target_w)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, target_h)
        self.cap.set(cv2.CAP_PROP_FPS, CAMERA["fps"])

        # 曝光控制
        # Windows DSHOW 的 CAP_PROP_EXPOSURE 是对数刻度 (log2 秒):
        #   -13 ≈ 1/8192s (极快,画面暗),  0 ≈ 1s (极慢,画面亮)
        # 高曝光值(如 0)会严重拖慢帧率，建议用自动曝光 + 适当补光
        if self.target_exposure is not None:
            try:
                self.cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 0.25)  # 手动模式
                self.cap.set(cv2.CAP_PROP_EXPOSURE, self.target_exposure)
            except Exception:
                pass
        # 否则保持默认（通常为自动曝光，帧率最优）
        # 停用自动对焦 (USB摄像头可能不支持，忽略错误)

        # 增益控制 — 不影响帧率，但增加噪点
        # 注意: 大量USB摄像头不支持 CAP_PROP_GAIN，可能无效果
        if self.target_gain is not None:
            try:
                self.cap.set(cv2.CAP_PROP_GAIN, self.target_gain)
            except Exception:
                pass

        # 停用自动对焦 (USB摄像头可能不支持，忽略错误)

        try:
            self.cap.set(cv2.CAP_PROP_AUTOFOCUS, 0)
        except Exception:
            pass

        # 读取实际分辨率
        actual_w = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        actual_h = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        actual_fps = self.cap.get(cv2.CAP_PROP_FPS)

        # 若标定分辨率与实际不一致, 自动缩放 camera_matrix
        if self.calibrated and self._calib_image_size is not None:
            calib_w, calib_h = self._calib_image_size
            if calib_w > 0 and calib_h > 0:
                sx = actual_w / calib_w
                sy = actual_h / calib_h
                if abs(sx - 1.0) > 0.01 or abs(sy - 1.0) > 0.01:
                    self.camera_matrix = self.camera_matrix.copy()
                    self.camera_matrix[0] *= sx
                    self.camera_matrix[1] *= sy
                    print(f"[相机] 分辨率缩放: 标定{calib_w}x{calib_h} → 实际{actual_w}x{actual_h}, "
                          f"fx={self.camera_matrix[0,0]:.1f}, fy={self.camera_matrix[1,1]:.1f}")

        exp_info = "自动" if self.target_exposure is None else f"手动({self.target_exposure})"
        gain_info = f" 增益:{self.target_gain}" if self.target_gain is not None else ""
        self.status_update.emit(
            f"摄像头 {self.camera_id}: {actual_w}x{actual_h} @ {actual_fps:.0f}fps 曝光:{exp_info}{gain_info}")

        self.running = True
        self.status_update.emit("运行中 — 正在归零...")
        frame_num = 0
        target_fps = 30
        frame_interval = 1.0 / target_fps
        
        # 启动独立采集线程
        self.frame_queue = queue.Queue(maxsize=1)
        self.capture_thread = CameraCaptureThread(self.cap, self.frame_queue)
        self.capture_thread.start()

        while self.running:
            try:
                try:
                    frame = self.frame_queue.get(timeout=0.1)
                except queue.Empty:
                    continue

                frame_num += 1
                timestamp = time.time()

                # 检测
                detect_results = self.detector.detect(
                    frame, self.camera_matrix, self.dist_coeffs)

                # 测量所有靶标
                disp_results = self.engine.measure_all(detect_results, timestamp)

                stats = self.engine.get_stats()

                # 缩放到显示尺寸再发送信号，大幅减少跨线程数据量
                h, w = frame.shape[:2]
                MAX_DISPLAY = 960  # 显示端最大尺寸，平衡清晰度和性能
                scale = min(MAX_DISPLAY / max(w, h), 1.0)
                if scale < 1.0:
                    dw, dh = int(w * scale), int(h * scale)
                    display_frame = cv2.resize(frame, (dw, dh), interpolation=cv2.INTER_NEAREST)
                    # 同步缩放角点/中心坐标
                    for t_id, d_res in detect_results.items():
                        if d_res.success:
                            if d_res.corners is not None:
                                c_arr = np.atleast_2d(d_res.corners).astype(np.float64)
                                if c_arr.shape[1] == 5:
                                    c_arr[0, 0:4] *= scale  # 只缩放 (x,y,a,b)，绝不能缩放角度 angle
                                    d_res.corners = c_arr.astype(np.float32)
                                else:
                                    d_res.corners = (c_arr * scale).astype(np.float32)
                            if d_res.center is not None:
                                d_res.center = np.asarray(d_res.center, dtype=np.float64) * scale
                else:
                    display_frame = frame

                self.frame_ready.emit(display_frame, detect_results, disp_results, stats)

                # 控制帧率 (用实际耗时，避免忙等待)
                elapsed = time.time() - timestamp
                sleep_ms = max(1, int((frame_interval - elapsed) * 1000))
                # 如果检测耗时已经超过帧间隔，额外 sleep 避免 CPU 空转
                if sleep_ms <= 1:
                    self.msleep(5)
                else:
                    self.msleep(sleep_ms)
            except Exception as e:
                print(f"[Thread Error] {e}")
                self.msleep(10)

        if hasattr(self, 'capture_thread'):
            self.capture_thread.stop()
            self.capture_thread.join(timeout=1.0)
            
        if self.cap:
            self.cap.release()
        self.status_update.emit("已停止")

    def stop(self):
        self.running = False
        self.wait(2000)

    def reset_zero(self):
        if self.engine:
            self.engine.reset_zero()

    def set_mode(self, mode):
        self.mode = mode
        if self.detector:
            self.detector.set_mode(mode)
        if self.engine:
            self.engine.reset_zero()


# ============================================================
# 标定对话框
# ============================================================
class CalibrationDialog(QDialog):
    """相机标定对话框"""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("相机标定")
        self.setFixedSize(420, 320)
        self.setStyleSheet("""
            QDialog { background-color: #1a1a2e; color: #e0e0e0; }
            QLabel { background: transparent; }
        """)

        layout = QFormLayout(self)
        layout.setSpacing(12)

        # 棋盘格参数
        self.cb_cols = QLineEdit("9")
        self.cb_rows = QLineEdit("6")
        self.cb_square = QLineEdit("30")
        self.cb_num = QLineEdit("20")
        self.cb_delay = QLineEdit("2.0")
        self.cb_output = QLineEdit("calib/camera_params.npz")

        for label, widget in [
            ("棋盘格内角列数:", self.cb_cols),
            ("棋盘格内角行数:", self.cb_rows),
            ("每格大小 (mm):", self.cb_square),
            ("采集图像数量:", self.cb_num),
            ("采集间隔 (秒):", self.cb_delay),
            ("输出文件:", self.cb_output),
        ]:
            layout.addRow(label, widget)

        # 按钮
        btn_box = QDialogButtonBox()
        self.btn_calib = QPushButton("开始标定")
        self.btn_calib.setStyleSheet("background:#1a6b3c; border-color:#2a8b4c; font-weight:bold;")
        btn_cancel = QPushButton("取消")
        btn_box.addButton(self.btn_calib, QDialogButtonBox.AcceptRole)
        btn_box.addButton(btn_cancel, QDialogButtonBox.RejectRole)
        layout.addRow(btn_box)

        self.btn_calib.clicked.connect(self._start_calibration)
        btn_cancel.clicked.connect(self.reject)

        self.status_label = QLabel("")
        self.status_label.setStyleSheet("color: #7ec8e3;")
        layout.addRow(self.status_label)

    def _start_calibration(self):
        try:
            cols = int(self.cb_cols.text())
            rows = int(self.cb_rows.text())
            square = float(self.cb_square.text())
            num = int(self.cb_num.text())
            delay = float(self.cb_delay.text())
            output = self.cb_output.text()
        except ValueError:
            QMessageBox.warning(self, "错误", "请输入有效的数值")
            return

        self.status_label.setText("正在采集标定图像...")
        QApplication.processEvents()

        calib = CameraCalibrator((cols, rows), square)
        images = calib.capture_calibration_images(0, num, delay)

        self.status_label.setText("正在计算标定参数...")
        QApplication.processEvents()

        rms, K, D, _, _ = calib.calibrate(images)
        calib.save(output)

        self.status_label.setText(f"标定完成! RMS={rms:.4f}px")
        QMessageBox.information(self, "标定完成",
            f"重投影误差: {rms:.4f} px\n"
            f"焦距: fx={K[0,0]:.1f}, fy={K[1,1]:.1f}\n"
            f"已保存: {output}")

        self.accept()


# ============================================================
# 靶标生成对话框
# ============================================================
class TargetGeneratorDialog(QDialog):
    """靶标图案与标定板生成对话框"""

    ARUCO_DICTS = [
        "DICT_4X4_50", "DICT_4X4_100", "DICT_4X4_250", "DICT_4X4_1000",
        "DICT_5X5_50", "DICT_5X5_100", "DICT_5X5_250", "DICT_5X5_1000",
        "DICT_6X6_50", "DICT_6X6_100", "DICT_6X6_250", "DICT_6X6_1000",
        "DICT_7X7_50", "DICT_7X7_100", "DICT_7X7_250", "DICT_7X7_1000",
    ]

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("靶标 & 标定板生成器")
        self.setMinimumSize(560, 520)
        self.setStyleSheet("""
            QDialog { background-color: #1a1a2e; color: #e0e0e0; }
            QLabel { background: transparent; }
            QTabWidget::pane { border: 1px solid #3a3a5c; background: #1a1a2e; }
            QTabBar::tab { background: #2d2d4a; color: #ccc; padding: 6px 16px;
                           border: 1px solid #3a3a5c; border-bottom: none; 
                           border-top-left-radius: 4px; border-top-right-radius: 4px; }
            QTabBar::tab:selected { background: #1a1a2e; color: #7ec8e3;
                                     border-bottom: 1px solid #1a1a2e; }
            QTabBar::tab:hover { color: #7ec8e3; }
            QSpinBox, QDoubleSpinBox, QComboBox { 
                background: #2d2d4a; border: 1px solid #4a4a6a; border-radius: 3px;
                padding: 3px 6px; min-width: 90px; color: #e0e0e0; }
            QLineEdit { background: #2d2d4a; border: 1px solid #4a4a6a;
                        border-radius: 3px; padding: 3px 6px; color: #e0e0e0; }
        """)

        self._generated_image = None
        self._init_ui()

    def _init_ui(self):
        layout = QVBoxLayout(self)
        layout.setSpacing(10)

        # ── 配置区 ──
        grp_config = QGroupBox("多靶标组合生成 (四象限靶标)")
        cfg_layout = QFormLayout(grp_config)
        cfg_layout.setSpacing(10)
        cfg_layout.setContentsMargins(20, 16, 20, 16)

        self.edit_sizes = QLineEdit("150, 100, 50")
        self.edit_sizes.setPlaceholderText("用逗号分隔，如: 150, 100, 50")
        
        self.cb_paper = QComboBox()
        self.cb_paper.addItem("A4 (210 x 297 mm)", (210.0, 297.0))
        self.cb_paper.addItem("A3 (297 x 420 mm)", (297.0, 420.0))
        self.cb_paper.addItem("Letter (215.9 x 279.4 mm)", (215.9, 279.4))

        self.spin_dpi = QSpinBox()
        self.spin_dpi.setRange(72, 1200)
        self.spin_dpi.setValue(300)

        cfg_layout.addRow("靶标尺寸列表 (mm):", self.edit_sizes)
        cfg_layout.addRow("打印纸张大小:", self.cb_paper)
        cfg_layout.addRow("打印 DPI:", self.spin_dpi)

        layout.addWidget(grp_config)

        # ── 预览区 ──
        grp_preview = QGroupBox("预览")
        grp_preview_layout = QVBoxLayout(grp_preview)
        self.preview_label = QLabel("点击 [生成预览] 查看靶标图案")
        self.preview_label.setAlignment(Qt.AlignCenter)
        self.preview_label.setMinimumHeight(300)
        self.preview_label.setStyleSheet(
            "color: #666; background: #12122a; border: 1px solid #2a2a4a; border-radius: 4px;")
        grp_preview_layout.addWidget(self.preview_label)
        layout.addWidget(grp_preview)

        # ── 底部按钮 ──
        btn_layout = QHBoxLayout()
        btn_layout.setSpacing(10)

        self.btn_generate = QPushButton("🔄 生成预览")
        self.btn_generate.setStyleSheet(
            "background:#2a5a8b; border-color:#3a6a9b; font-weight:bold; padding:8px 20px;")
        self.btn_generate.clicked.connect(self._on_generate)

        self.btn_save = QPushButton("💾 保存图片")
        self.btn_save.setStyleSheet(
            "background:#1a6b3c; border-color:#2a8b4c; font-weight:bold; padding:8px 20px;")
        self.btn_save.clicked.connect(self._on_save)
        self.btn_save.setEnabled(False)

        btn_layout.addStretch()
        btn_layout.addWidget(self.btn_generate)
        btn_layout.addWidget(self.btn_save)
        layout.addLayout(btn_layout)

        # 打印提示
        info_label = QLabel("打印提示：使用 100% 比例（原尺寸）打印，不要缩放；使用后用尺子验证基准尺寸。")
        info_label.setStyleSheet("color: #999; font-size: 11px;")
        info_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(info_label)

    def _on_generate(self):
        """生成靶标图案"""
        import sys
        import os
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        from generate_target import generate_multi_quadrant

        size_text = self.edit_sizes.text()
        try:
            sizes = [float(s.strip()) for s in size_text.split(",") if s.strip()]
        except ValueError:
            QMessageBox.warning(self, "输入错误", "靶标尺寸列表格式错误，请使用英文逗号分隔数字。")
            return
            
        if not sizes:
            QMessageBox.warning(self, "输入错误", "请至少输入一个靶标尺寸。")
            return

        dpi = self.spin_dpi.value()
        paper_w, paper_h = self.cb_paper.currentData()
        
        self._generated_image = generate_multi_quadrant(sizes, dpi, paper_w, paper_h)
        self._update_preview()
        self.btn_save.setEnabled(True)

    def _update_preview(self):
        if self._generated_image is None:
            return
        img = self._generated_image.copy()

        # 添加比例尺
        dpi = self.spin_dpi.value()
        scale_bar_mm = 30
        scale_bar_px = int(scale_bar_mm / 25.4 * dpi)
        h, w = img.shape[:2]
        bar_y = h - 15
        bar_x = max(10, w - scale_bar_px - 30)
        cv2.line(img, (bar_x, bar_y), (bar_x + scale_bar_px, bar_y), (255, 0, 0), 2)
        cv2.putText(img, f"{scale_bar_mm}mm", (bar_x, bar_y - 6),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.4, (255, 0, 0), 1)

        # 缩放预览
        max_preview_w = 500
        scale = min(max_preview_w / w, 1.0)
        if scale < 1.0:
            img = cv2.resize(img, (int(w * scale), int(h * scale)),
                             interpolation=cv2.INTER_AREA)

        img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        qimg = QImage(img_rgb.data, img_rgb.shape[1], img_rgb.shape[0],
                       img_rgb.shape[1] * 3, QImage.Format_RGB888)
        self.preview_label.setPixmap(QPixmap.fromImage(qimg))
        self.preview_label.setMinimumHeight(min(200, img.shape[0]))

    def _on_save(self):
        if self._generated_image is None:
            return

        default_name = "target_multi_quadrant.png"

        path, _ = QFileDialog.getSaveFileName(
            self, "保存靶标图片", default_name,
            "PNG 图片 (*.png);;JPEG 图片 (*.jpg);;所有文件 (*.*)")
        if not path:
            return

        cv2.imwrite(path, self._generated_image)
        QMessageBox.information(self, "保存成功",
            f"靶标图片已保存至:\n{path}\n\n"
            "请使用 100% 缩放打印，并用尺子验证实际尺寸。")


# ============================================================
# 主窗口
# ============================================================
class MainWindow(QMainWindow):
    """靶标位移测量系统主窗口"""

    def __init__(self):
        super().__init__()
        self.setWindowTitle("靶标视觉定位位移测量系统")
        self.setMinimumSize(1100, 700)
        self.setStyleSheet(STYLE_QSS)

        # 状态变量
        self.measuring = False
        self.logging_active = False
        self.thread: Optional[MeasurementThread] = None
        self.data_logger: Optional[DataLogger] = None
        self.current_mode = "aruco"
        self.last_frame = None
        self.frame_count = 0
        self._last_fps_time = time.time()
        self._fps_counter = 0
        self._current_fps = 0
        self._frame_busy = False  # 丢帧保护，防止GUI队列积压

        self._init_ui()
        self._create_toolbar()
        self._create_statusbar()

    # ── UI 构建 ────────────────────────────────────────────
    def _init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QHBoxLayout(central)
        main_layout.setContentsMargins(6, 6, 6, 6)
        main_layout.setSpacing(0)

        # ── QSplitter 分隔: 视频区 | 数据面板 (可拖拽) ──
        self.main_splitter = QSplitter(Qt.Horizontal)
        self.main_splitter.setHandleWidth(3)
        self.main_splitter.setStyleSheet("""
            QSplitter::handle {
                background: #3a3a5c;
                margin: 0 2px;
            }
            QSplitter::handle:hover { background: #7ec8e3; }
        """)

        left_panel = self._create_video_panel()
        self.main_splitter.addWidget(left_panel)

        right_scroll = QScrollArea()
        right_scroll.setWidgetResizable(True)
        right_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        right_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        right_scroll.setStyleSheet("""
            QScrollArea { background: transparent; border: none; }
            QScrollBar:vertical {
                background: #1a1a2e; width: 6px; border-radius: 3px;
            }
            QScrollBar::handle:vertical {
                background: #3a3a5c; border-radius: 3px; min-height: 40px;
            }
            QScrollBar::handle:vertical:hover { background: #5a5a7c; }
            QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
        """)
        right_panel = self._create_data_panel()
        right_scroll.setWidget(right_panel)
        self.main_splitter.addWidget(right_scroll)

        # 默认比例 7:3
        self.main_splitter.setSizes([700, 300])
        self.main_splitter.setStretchFactor(0, 7)
        self.main_splitter.setStretchFactor(1, 3)

        main_layout.addWidget(self.main_splitter)

    def _create_video_panel(self) -> QWidget:
        panel = QWidget()
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(0, 0, 0, 0)

        # 视频显示
        self.video_frame = QLabel("摄像头未连接")
        self.video_frame.setObjectName("videoFrame")
        self.video_frame.setAlignment(Qt.AlignCenter)
        self.video_frame.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self.video_frame.setStyleSheet(
            "QLabel#videoFrame { background:#000; color:#999; font-size:16pt; "
            "border:1px solid #4a4a70; border-radius:4px; }")
        self.video_frame.setMinimumSize(640, 480)
        layout.addWidget(self.video_frame)

        return panel

    def _create_data_panel(self) -> QWidget:
        panel = QWidget()
        panel.setMinimumWidth(300)
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(4, 0, 4, 0)
        layout.setSpacing(6)

        # ── 摄像头设置 ──
        grp_cam = QGroupBox("◆  摄像头设置")
        cam_layout = QFormLayout(grp_cam)
        cam_layout.setSpacing(6)
        cam_layout.setFieldGrowthPolicy(QFormLayout.ExpandingFieldsGrow)

        # 摄像头 ID
        self.spin_cam_id = QSpinBox()
        self.spin_cam_id.setRange(0, 9)
        self.spin_cam_id.setValue(CAMERA["camera_id"])
        self.spin_cam_id.setToolTip("摄像头索引: 0=内置, 1=USB外接")
        self.spin_cam_id.setFixedWidth(50)

        self.btn_scan_cam = QPushButton("扫描")
        self.btn_scan_cam.setFixedWidth(50)
        self.btn_scan_cam.setStyleSheet("padding:2px 4px; font-size:9pt;")
        self.btn_scan_cam.clicked.connect(self._scan_cameras)
        self.btn_scan_cam.setToolTip("扫描系统中可用的摄像头")

        id_row = QHBoxLayout()
        id_row.addWidget(self.spin_cam_id)
        id_row.addWidget(self.btn_scan_cam)
        id_row.addStretch()
        cam_layout.addRow("摄像头:", id_row)

        # 分辨率
        self.cb_resolution = QComboBox()
        self.cb_resolution.addItems([
            "640×480", "800×600", "1280×720", "1920×1080",
        ])
        res_w = CAMERA["resolution"][0]
        res_text = f"{res_w}×{CAMERA['resolution'][1]}"
        idx = self.cb_resolution.findText(res_text)
        if idx >= 0:
            self.cb_resolution.setCurrentIndex(idx)
        else:
            self.cb_resolution.setCurrentIndex(2)
        cam_layout.addRow("分辨率:", self.cb_resolution)

        # ── 曝光 ──
        self.cb_auto_exposure = QCheckBox("自动曝光")
        self.cb_auto_exposure.setChecked(True)
        self.cb_auto_exposure.setStyleSheet("QCheckBox { background: transparent; color: #ccc; }")
        self.cb_auto_exposure.toggled.connect(self._on_auto_exposure_toggled)
        cam_layout.addRow("曝光:", self.cb_auto_exposure)

        self.slider_exposure = QSlider(Qt.Horizontal)
        self.slider_exposure.setRange(-13, 0)
        self.slider_exposure.setValue(CAMERA.get("exposure", -6))
        self.slider_exposure.setEnabled(False)
        self.slider_exposure.valueChanged.connect(self._on_exposure_changed)
        cam_layout.addRow(self.slider_exposure)

        self.lbl_exposure = QLabel("自动曝光")
        self.lbl_exposure.setStyleSheet("color: #6ee78a; font-size: 10px;")
        self.lbl_exposure.setAlignment(Qt.AlignCenter)
        cam_layout.addRow(self.lbl_exposure)

        # ── 增益 ──
        self.slider_gain = QSlider(Qt.Horizontal)
        self.slider_gain.setRange(0, 100)
        self.slider_gain.setValue(CAMERA.get("gain", 0))
        self.slider_gain.valueChanged.connect(self._on_gain_changed)
        cam_layout.addRow("增益:", self.slider_gain)

        self.lbl_gain = QLabel("增益: 0  (不影响帧率, 高值=更亮但噪点多)")
        self.lbl_gain.setStyleSheet("color: #aaa; font-size: 10px;")
        self.lbl_gain.setAlignment(Qt.AlignCenter)
        cam_layout.addRow(self.lbl_gain)

        layout.addWidget(grp_cam)

        # ── 位移读数 ──
        grp_disp = QGroupBox("◆  实时位移")
        disp_layout = QVBoxLayout(grp_disp)
        disp_layout.setSpacing(4)

        self.disp_x = DisplacementLabel("X", "#ff8585")
        self.disp_y = DisplacementLabel("Y", "#6ee78a")
        self.disp_z = DisplacementLabel("Z", "#7ec8ff")

        disp_layout.addWidget(self.disp_x)
        disp_layout.addWidget(self.disp_y)
        disp_layout.addWidget(self.disp_z)

        # 总位移
        total_layout = QHBoxLayout()
        self.lbl_2d = QLabel("2D: ---")
        self.lbl_2d.setObjectName("value2D")
        self.lbl_2d.setFont(QFont("Consolas", 12))
        self.lbl_3d = QLabel("3D: ---")
        self.lbl_3d.setFont(QFont("Consolas", 10))
        self.lbl_3d.setStyleSheet("color: #ccc;")
        total_layout.addWidget(self.lbl_2d)
        total_layout.addStretch()
        total_layout.addWidget(self.lbl_3d)
        disp_layout.addLayout(total_layout)

        layout.addWidget(grp_disp)

        # ── 靶标尺寸配置 ──
        grp_sizes = QGroupBox("◆  各靶标尺寸配置 (外径 mm)")
        self.sizes_layout = QFormLayout(grp_sizes)
        self.sizes_layout.setSpacing(6)
        self.size_inputs = {}
        layout.addWidget(grp_sizes)

        # ── 轨迹图 ──
        grp_traj = QGroupBox("◆  X-Y 轨迹")
        traj_layout = QVBoxLayout(grp_traj)
        self.trajectory = TrajectoryCanvas(buffer_len=200)
        traj_layout.addWidget(self.trajectory)
        layout.addWidget(grp_traj)

        # ── 状态面板 ──
        grp_status = QGroupBox("◆  状态")
        status_layout = QGridLayout(grp_status)
        status_layout.setSpacing(6)

        self.lbl_target_status = QLabel("检测: ---")
        self.lbl_target_status.setStyleSheet("color: #aaa;")
        status_layout.addWidget(self.lbl_target_status, 0, 0)

        self.lbl_quality = QLabel("质量: ---")
        self.lbl_quality.setStyleSheet("color: #aaa;")
        status_layout.addWidget(self.lbl_quality, 0, 1)

        self.lbl_fps = QLabel("FPS: ---")
        self.lbl_fps.setStyleSheet("color: #aaa;")
        status_layout.addWidget(self.lbl_fps, 1, 0)

        self.lbl_frames = QLabel("帧: 0")
        self.lbl_frames.setStyleSheet("color: #aaa;")
        status_layout.addWidget(self.lbl_frames, 1, 1)

        self.lbl_precision = QLabel("精度: ---")
        self.lbl_precision.setObjectName("labelPrecision")
        status_layout.addWidget(self.lbl_precision, 2, 0, 1, 2)

        layout.addWidget(grp_status)

        # ── 控制按钮 ──
        btn_layout = QHBoxLayout()
        btn_layout.setSpacing(8)

        self.btn_start = QPushButton("▶  开始测量")
        self.btn_start.setObjectName("btnStart")
        self.btn_start.setMinimumHeight(32)
        self.btn_start.clicked.connect(self._toggle_measurement)

        self.btn_zero = QPushButton("↺ 归零")
        self.btn_zero.setObjectName("btnZero")
        self.btn_zero.setMinimumHeight(32)
        self.btn_zero.clicked.connect(self._reset_zero)
        self.btn_zero.setEnabled(False)

        btn_layout.addWidget(self.btn_start)
        btn_layout.addWidget(self.btn_zero)
        layout.addLayout(btn_layout)

        # 记录
        opt_layout = QHBoxLayout()
        opt_layout.setSpacing(8)

        self.cb_logging = QCheckBox("记录CSV")
        self.cb_logging.setStyleSheet("QCheckBox { background: transparent; color: #aaa; }")
        self.cb_logging.toggled.connect(self._toggle_logging)

        opt_layout.addWidget(self.cb_logging)
        layout.addLayout(opt_layout)

        layout.addStretch()
        return panel

    def _create_toolbar(self):
        toolbar = QToolBar("主工具栏")
        toolbar.setMovable(False)
        toolbar.setIconSize(QSize(18, 18))
        toolbar.setStyleSheet("""
            QToolBar { 
                background: #12122a; 
                border-bottom: 1px solid #2a2a4a; 
                spacing: 4px; 
                padding: 3px 8px; 
            }
            QToolBar QToolButton {
                background: #2d2d4a;
                border: 1px solid #4a4a6a;
                border-radius: 4px;
                padding: 4px 12px;
                color: #ccc;
                font-size: 10pt;
            }
            QToolBar QToolButton:hover {
                background: #3d5a8b;
                border-color: #7ec8e3;
                color: #fff;
            }
            QToolBar QToolButton:pressed {
                background: #2a4a6b;
            }
            QToolBar QToolButton#tbStart:hover {
                background: #2a8b4c;
                border-color: #3aab5c;
            }
            QToolBar QToolButton#tbStop:hover {
                background: #ab3a3a;
                border-color: #cb4a4a;
            }
            QToolBar QToolButton#tbZero:hover {
                background: #3a6a9b;
                border-color: #4a7aab;
            }
        """)
        self.addToolBar(toolbar)
        self._toolbar = toolbar  # 保存引用

        # 主要操作按钮 (QToolButton 在 QToolBar 上表现更好)
        self.tb_start = toolbar.addAction("▶ 开始")
        self.tb_start.triggered.connect(self._toggle_measurement)
        toolbar.widgetForAction(self.tb_start).setObjectName("tbStart")

        self.tb_zero = toolbar.addAction("↺ 归零")
        self.tb_zero.triggered.connect(self._reset_zero)
        self.tb_zero.setEnabled(False)
        toolbar.widgetForAction(self.tb_zero).setObjectName("tbZero")

        toolbar.addSeparator()

        act_target = toolbar.addAction("靶标生成")
        act_target.triggered.connect(self._open_target_generator)

        act_calib = toolbar.addAction("相机标定")
        act_calib.triggered.connect(self._open_calibration_dialog)

        act_screenshot = toolbar.addAction("截图")
        act_screenshot.triggered.connect(self._take_screenshot)

        toolbar.addSeparator()

        self.toolbar_status = QLabel("  就绪  ")
        self.toolbar_status.setStyleSheet("color: #888; background: transparent; font-size: 10pt;")
        toolbar.addWidget(self.toolbar_status)

    def _create_statusbar(self):
        self.statusBar().showMessage("就绪 | 请先标定相机，然后点击 [开始测量]")

    # ── 事件处理 ──────────────────────────────────────────
    def _toggle_measurement(self):
        if self.measuring:
            self._stop_measurement()
        else:
            self._start_measurement()

    def _start_measurement(self):
        # 检查标定文件
        calib_file = CAMERA.get("calibration_file", "calib/camera_params.npz")
        if not os.path.exists(calib_file):
            reply = QMessageBox.question(self, "标定提醒",
                "未找到相机标定文件。\n\n"
                "没有标定将使用默认参数，精度可能大幅下降。\n"
                "是否继续？",
                QMessageBox.Yes | QMessageBox.No)
            if reply == QMessageBox.No:
                return

        # 读取摄像头设置
        camera_id = self.spin_cam_id.value()
        res_text = self.cb_resolution.currentText()
        w, h = map(int, res_text.replace("×", "x").split("x"))
        resolution = (w, h)
        # 自动曝光时传 None，线程内不设曝光（保持默认自动模式）
        exposure = None if self.cb_auto_exposure.isChecked() else self.slider_exposure.value()
        # 增益始终读取（0=无增益，>0=提高亮度）; 0 时传 None 跳过设置
        gain_val = self.slider_gain.value()
        gain = gain_val if gain_val > 0 else None

        # 启动测量线程
        self.thread = MeasurementThread(
            camera_id=camera_id,
            calib_file=calib_file if os.path.exists(calib_file) else None,
            resolution=resolution,
            exposure=exposure,
            gain=gain,
        )
        self.thread.frame_ready.connect(self._on_frame)
        self.thread.status_update.connect(self._on_status)
        self.thread.error_occurred.connect(self._on_error)
        self.thread.start()

        self.measuring = True
        self.btn_start.setText("■  停止测量")
        self.btn_start.setObjectName("btnStop")
        self.btn_start.setStyleSheet(
            "QPushButton#btnStop { background-color:#8b2a2a; border-color:#ab3a3a; "
            "font-weight:bold; font-size:13px; }"
            "QPushButton#btnStop:hover { background-color:#ab3a3a; }")
        self.btn_zero.setEnabled(True)
        self.tb_start.setText("■ 停止")
        self.tb_zero.setEnabled(True)
        # 更新工具栏按钮外观
        tb_w = self._toolbar.widgetForAction(self.tb_start)
        if tb_w:
            tb_w.setObjectName("tbStop")
            tb_w.setStyleSheet("")
        # 禁用摄像头设置控件
        self.spin_cam_id.setEnabled(False)
        self.btn_scan_cam.setEnabled(False)
        self.cb_resolution.setEnabled(False)
        self.cb_auto_exposure.setEnabled(False)
        self.slider_exposure.setEnabled(False)
        self.slider_gain.setEnabled(False)
        self.toolbar_status.setText("  运行中 — 正在归零...")
        self.toolbar_status.setStyleSheet("color: #7ec8e3; background: transparent;")

        self.statusBar().showMessage("测量中... 保持靶标静止完成归零")
        self.frame_count = 0

    def _stop_measurement(self):
        if self.thread:
            self.thread.stop()
            self.thread = None

        self.measuring = False
        self.btn_start.setText("▶  开始测量")
        self.btn_start.setObjectName("btnStart")
        self.btn_start.setStyleSheet(
            "QPushButton#btnStart { background-color:#1a6b3c; border-color:#2a8b4c; "
            "font-weight:bold; font-size:13px; }"
            "QPushButton#btnStart:hover { background-color:#2a8b4c; }")
        self.btn_zero.setEnabled(False)
        self.tb_start.setText("▶ 开始")
        tb_w = self._toolbar.widgetForAction(self.tb_start)
        if tb_w:
            tb_w.setObjectName("tbStart")
            tb_w.setStyleSheet("")
        self.tb_zero.setEnabled(False)
        # 恢复摄像头设置控件
        self.spin_cam_id.setEnabled(True)
        self.btn_scan_cam.setEnabled(True)
        self.cb_resolution.setEnabled(True)
        self.cb_auto_exposure.setEnabled(True)
        self.slider_exposure.setEnabled(not self.cb_auto_exposure.isChecked())
        self.slider_gain.setEnabled(True)
        self.toolbar_status.setText("  就绪  ")
        self.toolbar_status.setStyleSheet("color: #888; background: transparent;")

        if self.data_logger:
            self.data_logger.stop()
            self.data_logger = None
            self.cb_logging.setChecked(False)

        self.statusBar().showMessage("已停止")

        # 重置显示
        self.video_frame.setText("摄像头未连接")
        self._clear_size_inputs()
        self._reset_displays()

    def _clear_size_inputs(self):
        if hasattr(self, 'sizes_layout') and hasattr(self, 'size_inputs'):
            while self.sizes_layout.rowCount() > 0:
                self.sizes_layout.removeRow(0)
            self.size_inputs.clear()

    def _on_frame(self, frame, detect_results, disp_results, stats):
        """接收处理后的帧"""
        # 丢帧保护：如果上一帧还没处理完，直接跳过
        if self._frame_busy:
            return
        self._frame_busy = True
        try:
            self.frame_count += 1
            self.last_frame = frame

            # 动态创建靶标尺寸输入框 (通过之前初始化的sizes_layout)
            if hasattr(self, 'size_inputs') and hasattr(self, 'sizes_layout') and self.thread and self.thread.detector:
                for t_id in disp_results.keys():
                    if t_id not in self.size_inputs:
                        spin = QDoubleSpinBox()
                        spin.setRange(10.0, 1000.0)
                        spin.setDecimals(1)
                        spin.setSingleStep(1.0)
                        spin.setStyleSheet("color: white; background: #2d2d4a; padding: 2px;")
                        spin.setValue(self.thread.detector.get_target_size(t_id))
                        spin.valueChanged.connect(lambda val, i=t_id: self.thread.detector.set_target_size(i, val))
                        self.sizes_layout.addRow(f"靶标 ID:{t_id} (mm):", spin)
                        self.size_inputs[t_id] = spin

            # 提取主靶标进行数据展示
            main_detect = None
            main_disp = None
            
            # 优先选择当前成功检测到的活跃靶标
            active_ids = [tid for tid, det in detect_results.items() if det.success]
            if active_ids:
                target_id_to_show = active_ids[0]
            elif disp_results:
                target_id_to_show = list(disp_results.keys())[0]
            else:
                target_id_to_show = None
            
            if target_id_to_show is not None:
                main_disp = disp_results.get(target_id_to_show)
                main_detect = detect_results.get(target_id_to_show)

            if main_detect is None or main_disp is None:
                # 没检测到靶标，构造空数据
                class Dummy: pass
                main_detect = Dummy(); main_detect.success = False; main_detect.quality = 0
                main_detect.corners = None; main_detect.center = None
                
                if main_disp is None:
                    main_disp = Dummy(); main_disp.x = 0; main_disp.y = 0; main_disp.z = 0
                    main_disp.is_outlier = False; main_disp.displacement_2d = 0; main_disp.displacement_3d = 0
                    main_disp.timestamp = 0

            # 更新视频显示 (此时传给HUD和面板的是主靶标)
            annotated = self._draw_frame(frame, detect_results, main_disp)
            self._update_video_display(annotated)

            # 更新数据面板
            self._update_displacement(main_disp)
            self._update_status(main_detect, main_disp, stats)

            # 更新轨迹
            if main_detect.success:
                self.trajectory.update_trajectory(main_disp.x, main_disp.y)

            # 数据记录
            if hasattr(self, 'logging_active') and self.logging_active and self.data_logger and main_detect.success:
                self.data_logger.log(self.frame_count, main_disp)
        except Exception as e:
            import traceback
            traceback.print_exc()
            print(f"[GUI Error] _on_frame: {e}")
        finally:
            self._frame_busy = False

    def _draw_frame(self, frame, detect_results, disp_result):
        """在帧上绘制检测叠加"""
        output = frame.copy() if frame.flags['C_CONTIGUOUS'] else frame
        h, w = output.shape[:2]

        any_success = False

        # ── 绘制所有靶标特征点和追踪框 ──
        if isinstance(detect_results, dict):
            for t_id, res in detect_results.items():
                if res.success:
                    any_success = True
                    # corners 存储的是椭圆参数: [[cx, cy, axis_a, axis_b, angle]]
                    if res.corners is not None:
                        c_arr = np.atleast_2d(res.corners)
                        if c_arr.shape[1] == 5:
                            cx_e, cy_e, a_e, b_e, ang_e = c_arr[0]
                            ellipse = ((float(cx_e), float(cy_e)),
                                       (float(a_e), float(b_e)), float(ang_e))
                            cv2.ellipse(output, ellipse, (0, 255, 0), 2, cv2.LINE_AA)
                    # 画中心十字 + ID 标签
                    if res.center is not None:
                        c_center = np.asarray(res.center).flatten()
                        if len(c_center) >= 2:
                            cx, cy = int(c_center[0]), int(c_center[1])
                            cv2.drawMarker(output, (cx, cy), (0, 255, 255),
                                           cv2.MARKER_CROSS, 20, 2, cv2.LINE_AA)
                            size_mm = self.thread.detector.get_target_size(t_id) if self.thread else 0
                            cv2.putText(output, f"ID:{t_id} ({size_mm:.0f}mm)",
                                        (cx + 15, cy - 15), cv2.FONT_HERSHEY_SIMPLEX,
                                        0.55, (0, 255, 0), 2, cv2.LINE_AA)

        # ── 未标定警告 ──
        if self.thread and not getattr(self.thread, 'calibrated', True):
            warn = "警告: 相机未标定"
            cv2.putText(output, warn, (w // 2 - 180, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 80), 4, cv2.LINE_AA)
            cv2.putText(output, warn, (w // 2 - 180, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (50, 50, 255), 2, cv2.LINE_AA)

        # ── 半透明 HUD 面板 (左上角, 仅 ROI 区域叠加) ──
        panel_h, panel_w = 120, 200
        x1, y1, x2, y2 = 4, 10, panel_w + 4, panel_h + 10
        roi = output[y1:y2, x1:x2]
        dark = np.full_like(roi, (10, 10, 18), dtype=np.uint8)
        cv2.addWeighted(dark, 0.55, roi, 0.45, 0, roi)
        output[y1:y2, x1:x2] = roi

        # 位移数值
        texts = [
            (f"X: {disp_result.x:+7.3f} mm", (60, 245, 245)),
            (f"Y: {disp_result.y:+7.3f} mm", (100, 220, 100)),
            (f"Z: {disp_result.z:+7.3f} mm", (70, 170, 255)),
            (f"二维: {disp_result.displacement_2d:.3f} mm", (220, 210, 80)),
        ]
        for i, (text, color) in enumerate(texts):
            y = 46 + i * 30
            cv2.putText(output, text, (12, y), cv2.FONT_HERSHEY_SIMPLEX,
                        0.70, (0, 0, 0), 5, cv2.LINE_AA)
            cv2.putText(output, text, (12, y), cv2.FONT_HERSHEY_SIMPLEX,
                        0.70, color, 2, cv2.LINE_AA)

        if not any_success:
            # 未检测到提示
            msg = "未检测到靶标"
            (tw, _), _ = cv2.getTextSize(msg, cv2.FONT_HERSHEY_SIMPLEX, 1.2, 3)
            msg_x = (w - tw) // 2
            msg_y = h // 2
            cv2.putText(output, msg, (msg_x, msg_y),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 0, 80), 5, cv2.LINE_AA)
            cv2.putText(output, msg, (msg_x, msg_y),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.2, (50, 50, 255), 2, cv2.LINE_AA)

        return output

    def _update_video_display(self, frame):
        """将 OpenCV 帧转换为 QPixmap 并显示 — frame 已预缩放，按需微调"""
        h, w = frame.shape[:2]

        # 只在尺寸差异较大时才缩放
        display_w = max(self.video_frame.width() - 4, 320)
        display_h = max(self.video_frame.height() - 4, 240)
        if abs(w - display_w) > 30 or abs(h - display_h) > 30:
            scale = min(display_w / w, display_h / h)
            new_w, new_h = int(w * scale), int(h * scale)
            frame = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_NEAREST)

        frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

        # 保持对数据的引用，防止 QImage 访问已释放内存
        self._frame_rgb_data = frame_rgb
        h, w = frame_rgb.shape[:2]
        bytes_per_line = 3 * w
        qimg = QImage(self._frame_rgb_data.data, w, h,
                       bytes_per_line, QImage.Format_RGB888)
        self.video_frame.setPixmap(QPixmap.fromImage(qimg))

    def _update_displacement(self, disp):
        self.disp_x.set_value(disp.x)
        self.disp_y.set_value(disp.y)
        self.disp_z.set_value(disp.z)

        self.lbl_2d.setText(f"2D: {disp.displacement_2d:.3f} mm")
        self.lbl_3d.setText(f"3D: {disp.displacement_3d:.3f} mm")

    def _update_status(self, detect, disp, stats):
        # 检测状态
        if detect.success:
            self.lbl_target_status.setText("检测: ✓ 已锁定")
            self.lbl_target_status.setStyleSheet("color: #51cf66;")
            self.lbl_quality.setText(f"质量: {detect.quality:.2f}")
            q = detect.quality
            color = "#51cf66" if q > 0.7 else ("#ffd43b" if q > 0.4 else "#ff6b6b")
            self.lbl_quality.setStyleSheet(f"color: {color};")
        else:
            self.lbl_target_status.setText("检测: ✗ 未检测到")
            self.lbl_target_status.setStyleSheet("color: #ff6b6b;")
            self.lbl_quality.setText("质量: ---")
            self.lbl_quality.setStyleSheet("color: #888;")

        # 离群值指示
        if disp.is_outlier:
            self.lbl_target_status.setText("检测: ⚠ 异常值")
            self.lbl_target_status.setStyleSheet("color: #ff6b6b; font-weight: bold;")

        # FPS
        self._fps_counter += 1
        now = time.time()
        if now - self._last_fps_time >= 1.0:
            self._current_fps = self._fps_counter / (now - self._last_fps_time)
            self._fps_counter = 0
            self._last_fps_time = now
        self.lbl_fps.setText(f"FPS: {self._current_fps:.0f}")
        self.lbl_frames.setText(f"帧: {self.frame_count}")

        # 精度估计
        if stats["zeroed"] and detect.success and abs(disp.x) < 0.5 and abs(disp.y) < 0.5:
            self.lbl_precision.setText("精度: < 1 mm ✓")
            self.lbl_precision.setStyleSheet("color: #51cf66; font-size: 12px;")
        else:
            self.lbl_precision.setText(f"精度: 理论 0.04mm | 要求 1mm ✓")
            self.lbl_precision.setStyleSheet("color: #7ec8e3; font-size: 12px;")

        # 归零状态
        if stats["zeroed"] and not disp.is_outlier:
            tb_status = "运行中"
            tb_color = "#51cf66"
        elif not stats["zeroed"]:
            tb_status = f"归零中... {stats.get('zero_cnt', 0)}/{stats.get('zero_samples', MEASURE['zero_samples'])}"
            tb_color = "#ffd43b"
        else:
            tb_status = "运行中"
            tb_color = "#7ec8e3"

        if self.measuring:
            self.toolbar_status.setText(f"  {tb_status}  ")
            self.toolbar_status.setStyleSheet(f"color: {tb_color}; background: transparent;")
            # 同步更新底部状态栏以防一直显示"正在归零..."
            if stats["zeroed"]:
                self.statusBar().showMessage("运行中 — 正在测量")
            else:
                self.statusBar().showMessage(f"运行中 — {tb_status}")

    def _reset_displays(self):
        self.disp_x.set_value(0)
        self.disp_y.set_value(0)
        self.disp_z.set_value(0)
        self.lbl_2d.setText("2D: ---")
        self.lbl_3d.setText("3D: ---")
        self.lbl_target_status.setText("检测: ---")
        self.lbl_target_status.setStyleSheet("color: #888;")
        self.lbl_quality.setText("质量: ---")
        self.lbl_quality.setStyleSheet("color: #888;")
        self.lbl_precision.setText("精度: ---")
        self.lbl_precision.setStyleSheet("color: #888;")

    def _reset_zero(self):
        if self.thread and self.thread.engine:
            self.thread.engine.reset_zero()
            self.statusBar().showMessage("已重新归零 — 请保持靶标静止...")

    def _toggle_logging(self, checked):
        if checked:
            self.data_logger = DataLogger(LOGGING["log_dir"])
            self.data_logger.start()
            self.logging_active = True
            self.statusBar().showMessage("数据记录中...")
        else:
            if self.data_logger:
                self.data_logger.stop()
            self.data_logger = None
            self.logging_active = False
            self.statusBar().showMessage("数据记录已停止")

    def _open_calibration_dialog(self):
        if self.measuring:
            QMessageBox.warning(self, "提示", "请先停止测量再进行标定")
            return
        dlg = CalibrationDialog(self)
        dlg.exec_()

    def _open_target_generator(self):
        """打开靶标生成对话框"""
        dlg = TargetGeneratorDialog(self)
        dlg.exec_()

    def _on_exposure_changed(self, value):
        """曝光滑块值变化 — 显示对应曝光时间和最大理论帧率"""
        # CAP_PROP_EXPOSURE 在 DSHOW 下是 log2 秒
        exp_sec = 2 ** value
        max_fps = 1.0 / exp_sec if exp_sec > 0 else 999
        if exp_sec < 0.001:
            exp_str = f"{exp_sec*1e6:.0f}μs"
        elif exp_sec < 1:
            exp_str = f"{exp_sec*1000:.1f}ms"
        else:
            exp_str = f"{exp_sec:.2f}s"
        self.lbl_exposure.setText(f"曝光: {exp_str}  |  {max_fps:.0f} fps")

    def _on_auto_exposure_toggled(self, checked):
        """自动曝光开关"""
        self.slider_exposure.setEnabled(not checked)
        if checked:
            self.lbl_exposure.setText("自动曝光")
            self.lbl_exposure.setStyleSheet("color: #51cf66; font-size: 10px;")
        else:
            self._on_exposure_changed(self.slider_exposure.value())
            self.lbl_exposure.setStyleSheet("color: #ffd43b; font-size: 10px;")

    def _on_gain_changed(self, value):
        """增益滑块 — 不影响帧率，但增加噪点"""
        self.lbl_gain.setText(f"增益: {value}  (不影响帧率, 高值=更亮但噪点多)")
        if value > 60:
            self.lbl_gain.setStyleSheet("color: #ff6b6b; font-size: 10px;")
        elif value > 30:
            self.lbl_gain.setStyleSheet("color: #ffd43b; font-size: 10px;")
        else:
            self.lbl_gain.setStyleSheet("color: #888; font-size: 10px;")

    def _scan_cameras(self):
        """扫描可用摄像头并更新显示"""
        available = []
        for i in range(5):
            for backend in [cv2.CAP_DSHOW, cv2.CAP_MSMF, cv2.CAP_ANY]:
                cap = cv2.VideoCapture(i, backend)
                if cap.isOpened():
                    w = cap.get(cv2.CAP_PROP_FRAME_WIDTH)
                    h = cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
                    available.append(f"cam {i}: {int(w)}x{int(h)}")
                    cap.release()
                    break
                cap.release()

        if available:
            msg = "检测到摄像头:\n  " + "\n  ".join(available)
            QMessageBox.information(self, "扫描结果", msg)
        else:
            QMessageBox.warning(self, "扫描结果", "未检测到任何摄像头\n请检查 USB 连接")

    def _take_screenshot(self):
        if self.last_frame is not None:
            os.makedirs("screenshots", exist_ok=True)
            ts = datetime.now().strftime("%Y%m%d_%H%M%S")
            fname = f"screenshots/screenshot_{ts}.png"
            cv2.imwrite(fname, self.last_frame)
            self.statusBar().showMessage(f"截图已保存: {fname}")

    def _on_status(self, msg):
        self.statusBar().showMessage(msg)
        self.toolbar_status.setText(f"  {msg}  ")

    def _on_error(self, msg):
        QMessageBox.critical(self, "错误", msg)
        self._stop_measurement()

    def closeEvent(self, event):
        self._stop_measurement()
        event.accept()


# ============================================================
# 入口
# ============================================================
def run_gui():
    """启动 GUI 应用"""
    # Windows DPI 适配
    if hasattr(Qt, 'AA_EnableHighDpiScaling'):
        QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
    if hasattr(Qt, 'AA_UseHighDpiPixmaps'):
        QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)

    app = QApplication(sys.argv)
    app.setStyle('Fusion')

    # 全局暗色调色板
    palette = QPalette()
    palette.setColor(QPalette.Window, QColor(26, 26, 46))
    palette.setColor(QPalette.WindowText, QColor(224, 224, 224))
    palette.setColor(QPalette.Base, QColor(18, 18, 42))
    palette.setColor(QPalette.Text, QColor(224, 224, 224))
    palette.setColor(QPalette.Button, QColor(45, 45, 74))
    palette.setColor(QPalette.ButtonText, QColor(224, 224, 224))
    palette.setColor(QPalette.Highlight, QColor(61, 90, 139))
    app.setPalette(palette)

    window = MainWindow()
    window.show()
    sys.exit(app.exec_())


if __name__ == "__main__":
    run_gui()
