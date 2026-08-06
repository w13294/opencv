import cv2
import numpy as np
from dataclasses import dataclass
from typing import Optional, Dict

@dataclass
class DetectionResult:
    """检测结果数据结构"""
    success: bool                               # 是否成功
    target_id: int = -1                         # 靶标分配的 ID
    corners: Optional[np.ndarray] = None        # 图像角点 (拟合椭圆数据)
    center: Optional[np.ndarray] = None         # 图像中靶标中心 (cx, cy)
    rvec: Optional[np.ndarray] = None           # 旋转向量
    tvec: Optional[np.ndarray] = None           # 平移向量 (mm)
    quality: float = 0.0                        # 检测质量 (0-1)


class TargetDetector:
    """
    专精的多四象限靶标检测器
    """
    def __init__(self, config: dict):
        self.config = config
        self.mode = "quadrant"  # 当前检测模式 (固定为四象限椭圆检测)
        
        # 光流法状态缓存
        # 结构: { target_id: {"gray": prev_gray, "klt_pts": np.ndarray} }
        self.tracked_targets = {}
        
        self.default_sizes = config.get("default_sizes_mm", [200.0, 100.0, 50.0])
        # {target_id: size_mm} 动态设定的尺寸
        self.target_sizes = {} 
        
    def set_mode(self, mode: str):
        """切换检测模式（当前仅支持 quadrant，保留接口以兼容调用方）"""
        self.mode = mode
        
    def set_target_size(self, target_id: int, size_mm: float):
        self.target_sizes[target_id] = size_mm
        
    def get_target_size(self, target_id: int) -> float:
        if target_id in self.target_sizes:
            return self.target_sizes[target_id]
        if target_id < len(self.default_sizes):
            return self.default_sizes[target_id]
        return self.default_sizes[-1] if self.default_sizes else 200.0

    def detect(self, image: np.ndarray, camera_matrix: np.ndarray, dist_coeffs: np.ndarray) -> Dict[int, DetectionResult]:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        thresh = cv2.adaptiveThreshold(blurred, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, 
                                       cv2.THRESH_BINARY_INV, 21, 10)
        _, thresh_global = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        thresh = cv2.bitwise_or(thresh, thresh_global)
        
        contours, hierarchy = cv2.findContours(thresh, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)

        valid_candidates = []
        if contours and hierarchy is not None:
            hierarchy = hierarchy[0]
            h_img, w_img = image.shape[:2]

            def collect_descendants(parent_idx, depth=0):
                """递归收集某个轮廓的所有后代轮廓"""
                if depth > 5:
                    return []
                results = []
                child = hierarchy[parent_idx][2]
                while child != -1:
                    child_area = cv2.contourArea(contours[child])
                    if child_area > 20:
                        M = cv2.moments(contours[child])
                        if M["m00"] != 0:
                            results.append({
                                "idx": child,
                                "area": child_area,
                                "cx": M["m10"] / M["m00"],
                                "cy": M["m01"] / M["m00"],
                                "depth": depth,
                            })
                    # 递归收集孙轮廓
                    results.extend(collect_descendants(child, depth + 1))
                    child = hierarchy[child][0]
                return results

            for i, (c, h) in enumerate(zip(contours, hierarchy)):
                area = cv2.contourArea(c)
                # 面积过滤: 太小是噪声, 太大是背景/边缘
                if area < 500 or area > w_img * h_img * 0.25:
                    continue

                # 必须有至少一个后代轮廓 (外环内部的子结构)
                all_descendants = collect_descendants(i)
                if not all_descendants:
                    continue

                # 后代总面积应占外环的合理比例 (靶标内部有显著结构)
                total_inner_area = sum(d["area"] for d in all_descendants)
                if total_inner_area < area * 0.1 or total_inner_area > area * 0.95:
                    continue

                # 筛选象限候选: 面积在外环 1%~45% 之间的后代
                quadrants = [d for d in all_descendants
                             if area * 0.01 < d["area"] < area * 0.45]

                # 如果没有明确象限，回退到"有子轮廓的椭圆"检测
                fallback_mode = False
                if len(quadrants) < 2:
                    # 取面积最大的后代作为象限候选（至少2个）
                    all_descendants.sort(key=lambda d: d["area"], reverse=True)
                    quadrants = all_descendants[:2]
                    if len(quadrants) < 2:
                        continue
                    fallback_mode = True
                elif len(quadrants) > 2:
                    # 超过2个，取面积最大的2个
                    quadrants.sort(key=lambda q: q["area"], reverse=True)
                    quadrants = quadrants[:2]

                # ──── 验证对角分布 ────
                ocenter = ((quadrants[0]["cx"] + quadrants[1]["cx"]) / 2,
                           (quadrants[0]["cy"] + quadrants[1]["cy"]) / 2)
                v1 = np.array([quadrants[0]["cx"] - ocenter[0], quadrants[0]["cy"] - ocenter[1]])
                v2 = np.array([quadrants[1]["cx"] - ocenter[0], quadrants[1]["cy"] - ocenter[1]])
                dist1 = np.linalg.norm(v1)
                dist2 = np.linalg.norm(v2)

                if dist1 < 3 or dist2 < 3:
                    continue

                cos_angle = np.dot(v1, v2) / (dist1 * dist2)
                cos_angle = max(-1.0, min(1.0, cos_angle))
                angle_deg = np.degrees(np.arccos(cos_angle))

                # 对角验证 (回退模式下放宽)
                min_angle = 90.0 if fallback_mode else 100.0
                if angle_deg < min_angle:
                    continue

                # 两象限面积比
                area_ratio_quad = max(quadrants[0]["area"], quadrants[1]["area"]) / \
                                 (min(quadrants[0]["area"], quadrants[1]["area"]) + 1.0)
                max_ratio = 5.0 if fallback_mode else 3.5
                if area_ratio_quad > max_ratio:
                    continue

                # ──── 外轮廓椭圆拟合 ────
                if len(c) < 5:
                    continue

                ellipse = cv2.fitEllipse(c)
                (ex, ey), (axis_a, axis_b), _ = ellipse
                if max(axis_a, axis_b) == 0:
                    continue
                ratio = min(axis_a, axis_b) / max(axis_a, axis_b)

                # 外环必须接近圆形 (回退模式下更宽松)
                min_ratio = 0.5 if fallback_mode else 0.7
                if ratio < min_ratio:
                    continue

                # 边缘排除
                margin = 20
                if ex < margin or ex > w_img - margin or ey < margin or ey > h_img - margin:
                    continue

                # 象限质心作为检测点
                c_pts = np.array([[quadrants[0]["cx"], quadrants[0]["cy"]],
                                  [quadrants[1]["cx"], quadrants[1]["cy"]]], dtype=np.float32)

                # 质量评分
                if fallback_mode:
                    quality = 0.3  # 回退模式质量较低
                else:
                    angle_score = min(1.0, (angle_deg - 100.0) / 80.0)
                    area_consistency = 1.0 - (area_ratio_quad - 1.0) / 2.5
                    quality = 0.4 * ((ratio - 0.7) / 0.3) + 0.3 * angle_score + 0.3 * area_consistency
                    quality = max(0.3, quality)

                valid_candidates.append({
                    "ellipse": ellipse,
                    "centroids": c_pts,
                    "ratio": ratio,
                    "children_count": len(quadrants),
                    "quality": quality,
                    "angle_deg": angle_deg,
                })

        # ── 稳定 ID 分配 ──
        # 1) 计算每个候选的像素面积 (用于首次排序)
        cand_areas = []
        for cand in valid_candidates:
            (_, _), (axis_a, axis_b), _ = cand["ellipse"]
            area = np.pi * (axis_a / 2.0) * (axis_b / 2.0)
            cand_areas.append(area)
        
        # 2) 按面积从大到小排序候选，面积大的 = 小 ID
        sorted_indices = sorted(range(len(valid_candidates)),
                                key=lambda i: cand_areas[i], reverse=True)
        
        # 3) 上一帧已知靶标的中心
        prev_centers = {}
        for tid, data in self.tracked_targets.items():
            prev_centers[tid] = data["center"]
        
        assigned_ids = {}      # ci -> tid
        used_tids = set()      # 已分配的 tid
        
        # 4) 最近邻匹配 (优先匹配大靶标，保持 ID 稳定)
        if prev_centers and valid_candidates:
            for ci in sorted_indices:  # 从大到小遍历
                cc = (valid_candidates[ci]["centroids"][0] +
                      valid_candidates[ci]["centroids"][1]) / 2.0
                best_tid = None
                best_dist = float('inf')
                for tid, pc in prev_centers.items():
                    if tid in used_tids:
                        continue
                    dist = np.linalg.norm(cc - pc)
                    if dist < best_dist:
                        best_dist = dist
                        best_tid = tid
                if best_tid is not None and best_dist < 150.0:
                    assigned_ids[ci] = best_tid
                    used_tids.add(best_tid)
        
        # 5) 未匹配的候选 → 按面积顺序分配新 ID
        # 新 ID = 该候选在面积排序中的位置 (0=最大, 1=次大...)
        for rank, ci in enumerate(sorted_indices):
            if ci not in assigned_ids:
                assigned_ids[ci] = rank
                used_tids.add(rank)
        
        # 保留未匹配到的已知旧靶标历史 (最多保留 30 帧，防止一帧识别不到就重置 ID)
        new_tracked_targets = {}
        shared_gray = gray.copy()
        
        for tid, data in self.tracked_targets.items():
            if tid not in used_tids:
                m_cnt = data.get("missing_count", 0) + 1
                if m_cnt <= 30:  # 容忍最多 30 帧的短暂丢失
                    new_tracked_targets[tid] = {
                        "gray": data["gray"],
                        "klt_pts": data["klt_pts"],
                        "center": data["center"],
                        "missing_count": m_cnt
                    }
        
        results = {}
        for ci, cand in enumerate(valid_candidates):
            target_id = assigned_ids[ci]
            child_centroids = cand["centroids"]
            ellipse = cand["ellipse"]
            (cx_ell, cy_ell), (axis_a, axis_b), angle = ellipse
            
            center_pt = (child_centroids[0] + child_centroids[1]) / 2.0
            
            # 更新追踪状态
            new_tracked_targets[target_id] = {
                "gray": shared_gray,
                "klt_pts": child_centroids.copy(),
                "center": center_pt,
                "missing_count": 0
            }
            
            fx, fy = camera_matrix[0, 0], camera_matrix[1, 1]
            cx0, cy0 = camera_matrix[0, 2], camera_matrix[1, 2]
            
            if np.linalg.norm(child_centroids[0] - child_centroids[1]) > 1e-3:
                centroid_dist = np.linalg.norm(child_centroids[0] - child_centroids[1])
                pixel_diameter = 0.8 * (centroid_dist / 0.58) + 0.2 * ((axis_a + axis_b) / 2.0)
            else:
                pixel_diameter = (axis_a + axis_b) / 2.0
            
            marker_size = self.get_target_size(target_id)
            z = ((fx + fy) / 2.0 * marker_size) / max(pixel_diameter, 1.0)
            x = (center_pt[0] - cx0) * z / fx
            y = (center_pt[1] - cy0) * z / fy
            
            tvec = np.array([x, y, z], dtype=np.float64)
            rvec = np.zeros(3, dtype=np.float64)
            
            # 用候选阶段计算的四象限质量评分 (圆度+对角角度+象限面积一致性)
            quality = cand.get("quality", 0.5)

            results[target_id] = DetectionResult(
                success=True,
                target_id=target_id,
                center=center_pt,
                corners=np.array([[cx_ell, cy_ell, axis_a, axis_b, angle]]),
                rvec=rvec, tvec=tvec,
                quality=quality
            )

        self.tracked_targets = new_tracked_targets
        return results

    def draw_detections(self, image: np.ndarray, results: Dict[int, DetectionResult]) -> np.ndarray:
        output = image.copy()
        for t_id, res in results.items():
            if not res.success: continue
            
            if res.corners is not None:
                c_arr = np.atleast_2d(res.corners)
                if c_arr.shape[1] == 5:
                    cx_e, cy_e, a_e, b_e, ang_e = c_arr[0]
                    ellipse = ((float(cx_e), float(cy_e)), (float(a_e), float(b_e)), float(ang_e))
                    cv2.ellipse(output, ellipse, (0, 255, 0), 2)
                
            if res.center is not None:
                c_center = np.asarray(res.center).flatten()
                if len(c_center) >= 2:
                    cx, cy = int(c_center[0]), int(c_center[1])
                    cv2.circle(output, (cx, cy), 8, (0, 255, 255), -1)
                    cv2.putText(output, f"ID: {t_id} ({self.get_target_size(t_id):.0f}mm)", (cx + 15, cy - 15), 
                                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2)
        return output
