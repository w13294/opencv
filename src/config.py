"""
靶标视觉定位位移测量系统 - 配置文件

关键参数说明:
  - 10米处 1mm 精度要求: 角分辨率 ≈ 0.0057° → 需要亚像素精度 < 0.2px
  - 推荐相机: 800万像素以上, 配合长焦镜头(窄FOV)
  - 推荐靶标: 200mm大型ArUco标记 或 高精度棋盘格
"""

import numpy as np

# ============================================================
# 相机与光学参数
# ============================================================
CAMERA = {
    "resolution": (1280, 720),   # 处理分辨率 (降低以提高速度)
    "fps": 30,
    "camera_id": 1,              # 摄像头索引, 0=默认摄像头, 1=USB外接
    "exposure": -6,              # 曝光补偿 (减少运动模糊)
    "gain": 0,                   # 增益/ISO (0=无增益, 不影响帧率但加噪点)
    "calibration_file": "calib/camera_params.npz",
    "display_scale": 0.5,        # 显示缩放 (降低渲染负担)
}

# ============================================================
# 靶标参数
# ============================================================
TARGET = {
    # ArUco 标记参数
    "dictionary": "DICT_6X6_250",  # ArUco字典
    "marker_id": 0,                 # 使用的标记ID
    "marker_size_mm": 200.0,        # 标记实际边长 (mm) — 10米处需要大标记
    # 棋盘格参数 (备选方案)
    "chessboard_size": (4, 4),      # 内角点数 / 圆点阵列 (cols, rows)
    "chessboard_square_mm": 50.0,   # 棋盘格边长 / 圆心间距 (mm)
    # 检测参数
    "subpix_window": (11, 11),      # 亚像素搜索窗口
    "subpix_zero_zone": (-1, -1),   # 死区
    "subpix_criteria": (30, 0.001), # (max_iter, epsilon)
}

# ============================================================
# 测量参数
# ============================================================
MEASURE = {
    # 以下距离仅用于理论分辨率估算 (打印参考), 不影响实际测量.
    # 实际距离由 solvePnP 根据相机内参和靶标尺寸自动计算.
    "target_distance_mm": 1000.0,   # 估算用参考距离 (默认1m, 按需修改)

    "zero_samples": 100,            # 归零采样数 (初始位置校准)
    "moving_average_window": 5,     # 滑动平均窗口
    "outlier_threshold_mm": 50.0,   # 异常值阈值 (mm) - 放宽阈值以适应快速的手部移动

    # 卡尔曼滤波器参数
    "kalman": {
        "process_noise": 0.05,      # 过程噪声 (mm²) — 提高此值以减少跟踪延迟 (橡胶带效应)
        "measurement_noise": 0.5,   # 测量噪声 (mm²) — 越大越相信模型
        "init_error": 1.0,          # 初始估计误差
    },

    # 精度验证
    "required_precision_um": 1000,  # 要求精度 1000μm = 1mm
}

# ============================================================
# 实时显示参数
# ============================================================
DISPLAY = {
    "show_fps": True,
    "show_grid": True,
    "show_trajectory": True,        # 显示位移轨迹
    "trajectory_length": 200,       # 轨迹保留点数
    "scale_bar_length_mm": 5.0,     # 比例尺 (5mm)
    "font_scale": 0.6,
    "colors": {
        "target": (0, 255, 0),      # 绿色 - 靶标边框
        "corner": (0, 0, 255),      # 红色 - 角点
        "text": (255, 255, 255),    # 白色 - 文字
        "trajectory": (255, 255, 0), # 青色 - 轨迹
        "reference": (255, 0, 0),   # 蓝色 - 参考位置
    },
}

# ============================================================
# 数据记录参数
# ============================================================
LOGGING = {
    "enabled": True,
    "log_dir": "logs/",
    "csv_delimiter": ",",
    "save_interval_sec": 1.0,       # 数据保存间隔
}

# ============================================================
# 性能分析 (理论值)
# ============================================================
# 计算理论像素分辨率
# 假设 FOV 水平和垂直角度
HFOV_DEG = 8.0    # 水平视场角 (长焦镜头)
VFOV_DEG = 4.5    # 垂直视场角

pixel_pitch_h = (2 * MEASURE["target_distance_mm"] *
                 np.tan(np.radians(HFOV_DEG / 2))) / CAMERA["resolution"][0]
pixel_pitch_v = (2 * MEASURE["target_distance_mm"] *
                 np.tan(np.radians(VFOV_DEG / 2))) / CAMERA["resolution"][1]

print(f"[理论分析] 10m处像素分辨率: {pixel_pitch_h:.2f} mm/像素 (H) x "
      f"{pixel_pitch_v:.2f} mm/像素 (V)")
print(f"[理论分析] 要达到1mm精度, 需要亚像素精度: "
      f"{MEASURE['required_precision_um']/1000 / pixel_pitch_h:.2f} 像素")
print(f"[理论分析] 标记在图像中占比约: "
      f"{TARGET['marker_size_mm'] / (2 * MEASURE['target_distance_mm'] * np.tan(np.radians(HFOV_DEG / 2))) * CAMERA['resolution'][0]:.0f} 像素宽")
