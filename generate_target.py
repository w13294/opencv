"""
靶标图案生成器
  - 生成可打印的 ArUco 标记 (200mm 大型标记, 适合10米距离)
  - 生成可打印的棋盘格标定板
  - 生成圆形网格图案

输出:
  - A4 或 A3 纸张可打印的 PDF 或高分辨率 PNG
  - 包含比例尺, 方便验证打印尺寸
"""

import cv2
import numpy as np
import os
import argparse


def generate_aruco_marker(marker_id=0, dict_name="DICT_6X6_250",
                           marker_mm=200, dpi=300, with_border=True):
    """
    生成 ArUco 标记

    参数:
        marker_id:    标记ID
        dict_name:    ArUco字典名称
        marker_mm:    标记边长 (mm)
        dpi:          打印分辨率
        with_border:  是否添加白色边框
    """
    aruco_dict = cv2.aruco.getPredefinedDictionary(
        getattr(cv2.aruco, dict_name))

    # 标记的位单元
    bits = aruco_dict.markerSize
    border_bits = 1

    # 计算像素尺寸
    marker_inch = marker_mm / 25.4
    marker_px = int(marker_inch * dpi)

    # 生成标记 (无边框)
    img = cv2.aruco.generateImageMarker(aruco_dict, marker_id, marker_px)
    img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)

    if with_border:
        border_px = int(border_bits * marker_px / bits)
        img = cv2.copyMakeBorder(img, border_px, border_px, border_px, border_px,
                                 cv2.BORDER_CONSTANT, value=(255, 255, 255))

    # 在底部添加信息栏
    info_h = int(0.5 * dpi)  # 0.5 英寸信息栏
    info_bar = np.ones((info_h, img.shape[1], 3), dtype=np.uint8) * 255

    cv2.putText(info_bar, f"ArUco ID:{marker_id}  Dict:{dict_name}  "
                f"Size:{marker_mm}x{marker_mm}mm",
                (20, info_h // 2 + 5), cv2.FONT_HERSHEY_SIMPLEX,
                0.5, (0, 0, 0), 1, cv2.LINE_AA)

    img_with_info = np.vstack([img, info_bar])

    return img_with_info


def generate_chessboard(cols=9, rows=6, square_mm=30, dpi=300):
    """
    生成棋盘格标定板

    参数:
        cols:       内角列数
        rows:       内角行数
        square_mm:  每格大小 (mm)
        dpi:        打印分辨率
    """
    square_inch = square_mm / 25.4
    square_px = int(square_inch * dpi)

    # 棋盘格图像 (外边框 = 内角数 + 2 用于两侧留白)
    board_w = (cols + 1) * square_px
    board_h = (rows + 1) * square_px

    board = np.zeros((board_h, board_w, 3), dtype=np.uint8)

    for r in range(rows + 1):
        for c in range(cols + 1):
            if (r + c) % 2 == 0:
                y1, y2 = r * square_px, (r + 1) * square_px
                x1, x2 = c * square_px, (c + 1) * square_px
                board[y1:y2, x1:x2] = (255, 255, 255)

    # 信息栏
    info_h = int(0.4 * dpi)
    info_bar = np.ones((info_h, board_w, 3), dtype=np.uint8) * 255
    cv2.putText(info_bar, f"Chessboard {cols}x{rows}  "
                f"Square:{square_mm}x{square_mm}mm",
                (20, info_h // 2 + 10), cv2.FONT_HERSHEY_SIMPLEX,
                0.6, (0, 0, 0), 2, cv2.LINE_AA)
    cv2.putText(info_bar, "内角点: 9列 x 6行 (检测用10x7方块)",
                (20, info_h - 15), cv2.FONT_HERSHEY_SIMPLEX,
                0.4, (100, 100, 100), 1, cv2.LINE_AA)

    return np.vstack([board, info_bar])


def generate_circle_grid(cols=9, rows=6, spacing_mm=30, dpi=300):
    """
    生成圆形网格图案 (对称圆网格)

    参数:
        cols:       圆列数
        rows:       圆行数
        spacing_mm: 圆心间距 (mm)
        dpi:        打印分辨率
    """
    spacing_inch = spacing_mm / 25.4
    spacing_px = int(spacing_inch * dpi)
    radius_px = int(spacing_px * 0.35)  # 圆半径占间距的35%

    # 留白
    margin = spacing_px

    board_w = (cols - 1) * spacing_px + 2 * margin
    board_h = (rows - 1) * spacing_px + 2 * margin

    board = np.ones((board_h, board_w, 3), dtype=np.uint8) * 255

    for r in range(rows):
        for c in range(cols):
            cx = margin + c * spacing_px
            cy = margin + r * spacing_px
            cv2.circle(board, (cx, cy), radius_px, (0, 0, 0), -1)

    # 添加基准标记 (四角大圆)
    marker_size = int(radius_px * 1.5)
    # 左上
    cv2.circle(board, (margin, margin), marker_size, (0, 0, 0), 3)
    # 右上
    cv2.circle(board, (margin + (cols - 1) * spacing_px, margin), marker_size, (0, 0, 0), 3)
    # 左下
    cv2.circle(board, (margin, margin + (rows - 1) * spacing_px), marker_size, (0, 0, 0), 3)

    # 信息栏
    info_h = int(0.4 * dpi)
    info_bar = np.ones((info_h, board_w, 3), dtype=np.uint8) * 255
    cv2.putText(info_bar, f"Circle Grid {cols}x{rows}  "
                f"Spacing:{spacing_mm}mm  Radius:{spacing_mm*0.35:.1f}mm",
                (20, info_h // 2 + 10), cv2.FONT_HERSHEY_SIMPLEX,
                0.5, (0, 0, 0), 1, cv2.LINE_AA)

    return np.vstack([board, info_bar])


def generate_quadrant(size_mm=200, dpi=300):
    """
    生成四象限/十字靶标 (外围黑环，内部分四象限黑白相间)
    
    参数:
        size_mm: 靶标外环直径 (mm)
        dpi:     打印分辨率
    """
    size_inch = size_mm / 25.4
    size_px = int(size_inch * dpi)
    
    # 画布大小，留出一定的白边
    margin_px = int(0.5 * dpi) # 0.5英寸白边
    board_size = size_px + 2 * margin_px
    
    board = np.ones((board_size, board_size, 3), dtype=np.uint8) * 255
    center = (board_size // 2, board_size // 2)
    
    ring_radius = size_px // 2
    inner_radius = int(ring_radius * 0.8) # 内十字占据 80% 半径
    
    # 绘制外侧黑环
    cv2.circle(board, center, ring_radius, (0, 0, 0), -1)
    
    # 绘制内侧白圆 (清空内部以便画象限)
    cv2.circle(board, center, inner_radius, (255, 255, 255), -1)
    
    # 绘制左下和右上黑色象限
    # OpenCV ellipse角度: 0度在X轴正向(右), 顺时针旋转
    # 右下 (0-90) -> 白色
    # 左下 (90-180) -> 黑色
    # 左上 (180-270) -> 白色
    # 右上 (270-360) -> 黑色
    cv2.ellipse(board, center, (inner_radius, inner_radius), 0, 90, 180, (0, 0, 0), -1)
    cv2.ellipse(board, center, (inner_radius, inner_radius), 0, 270, 360, (0, 0, 0), -1)
    
    # 靶心白点(极小点，避免打印溢墨导致中心模糊)，可选
    # cv2.circle(board, center, 2, (255, 255, 255), -1)

    # 信息栏
    info_h = int(0.5 * dpi)
    info_bar = np.ones((info_h, board_size, 3), dtype=np.uint8) * 255
    cv2.putText(info_bar, f"Quadrant Target  "
                f"Outer Diameter:{size_mm}mm  Inner Diameter:{size_mm*0.8:.1f}mm",
                (20, info_h // 2 + 10), cv2.FONT_HERSHEY_SIMPLEX,
                0.5, (0, 0, 0), 1, cv2.LINE_AA)

    return np.vstack([board, info_bar])



def main():
    parser = argparse.ArgumentParser(description="靶标图案生成器")
    parser.add_argument("--type", type=str, default="aruco",
                        choices=["aruco", "chessboard", "circles", "quadrant"],
                        help="靶标类型")
    parser.add_argument("--id", type=int, default=0, help="ArUco 标记ID")
    parser.add_argument("--dict", type=str, default="DICT_6X6_250",
                        help="ArUco 字典类型")
    parser.add_argument("--size", type=float, default=200,
                        help="标记尺寸 (ArUco边长, 棋盘格每格大小, 或四象限靶外环直径 mm)")
    parser.add_argument("--cols", type=int, default=9, help="列数")
    parser.add_argument("--rows", type=int, default=6, help="行数")
    parser.add_argument("--dpi", type=int, default=300, help="打印分辨率")
    parser.add_argument("--output", type=str, default="target.png",
                        help="输出文件路径")

    args = parser.parse_args()

    print(f"\n  生成靶标: {args.type}")
    print(f"  尺寸: {args.size}mm, DPI: {args.dpi}")

    if args.type == "aruco":
        img = generate_aruco_marker(
            args.id, args.dict, args.size, args.dpi)
    elif args.type == "chessboard":
        img = generate_chessboard(
            args.cols, args.rows, args.size, args.dpi)
    elif args.type == "circles":
        img = generate_circle_grid(
            args.cols, args.rows, args.size, args.dpi)
    elif args.type == "quadrant":
        img = generate_quadrant(
            args.size, args.dpi)

    os.makedirs(os.path.dirname(args.output) if os.path.dirname(args.output) else ".", exist_ok=True)
    cv2.imwrite(args.output, img)
    print(f"  已生成: {args.output} ({img.shape[1]}x{img.shape[0]} px)")
    print(f"  打印尺寸: {img.shape[1]/args.dpi:.1f}\" x {img.shape[0]/args.dpi:.1f}\"")

    # 同时生成一个带比例尺的预览版
    preview = img.copy()
    scale_bar_mm = 50  # 50mm 比例尺
    scale_bar_px = int(scale_bar_mm / 25.4 * args.dpi)
    bar_y = img.shape[0] - 30
    cv2.line(preview, (20, bar_y), (20 + scale_bar_px, bar_y),
             (0, 0, 255), 3)
    cv2.putText(preview, f"{scale_bar_mm}mm", (25, bar_y - 10),
                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)

    cv2.imshow("Target Preview (带比例尺)", cv2.resize(preview, (800, 600)))
    print("  按任意键关闭预览...")
    cv2.waitKey(0)
    cv2.destroyAllWindows()

    print(f"\n  打印提示:")
    print(f"  1. 使用 100% 比例打印 (不缩放)")
    print(f"  2. 用尺子验证打印尺寸是否准确")
    print(f"  3. 将靶标贴在平整无变形的底板上")
    print(f"  4. 确保充足的均匀照明\n")


if __name__ == "__main__":
    main()
