"""端到端复现 TargetDetector 的判定链, 验证修复效果。

镜像 Kotlin 实现的各道关卡, 对比 OLD / NEW 规则在各类场景下的接受情况。
"""
import cv2
import math
import numpy as np

W, H = 640, 480
DIRS = (315.0, 45.0, 135.0, 225.0)


def draw_target(img, cx, cy, R, rot=0, dark=0, light=255):
    cv2.circle(img, (int(cx), int(cy)), int(R), dark, -1)
    cv2.circle(img, (int(cx), int(cy)), int(R * 0.8), light, -1)
    for a in (315 + rot, 135 + rot):
        pts = [(cx, cy)]
        for d in (a - 45, a + 45):
            r = math.radians(d)
            pts.append((cx + R * math.cos(r), cy + R * math.sin(r)))
        cv2.fillPoly(img, [np.array(pts, np.int32)], dark)


def pipeline(gray, new_rules):
    """返回被接受的候选列表 [(cx,cy,R)]"""
    blur = cv2.medianBlur(gray, 3)
    _, th = cv2.threshold(blur, 0, 255, cv2.THRESH_OTSU)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    closed = cv2.morphologyEx(th, cv2.MORPH_CLOSE, k)
    chain = cv2.CHAIN_APPROX_NONE if new_rules else cv2.CHAIN_APPROX_SIMPLE
    cs, _ = cv2.findContours(closed, cv2.RETR_LIST, chain)
    imgdiag = math.hypot(W, H)
    out = []
    for c in cs:
        area = cv2.contourArea(c)
        if area <= 100 or len(c) < 5:
            continue
        (ex, ey), (w, h), ang = cv2.fitEllipse(c)
        if w < 1 or h < 1:
            continue
        if min(w, h) / max(w, h) < 0.5:
            continue
        ea = math.pi * (w / 2) * (h / 2)
        if new_rules and ea > 1:
            f = area / ea
            if f < 0.93 or f > 1.10:
                continue
        cx, cy = ex, ey
        R = (w + h) / 4.0

        # 四象限灰度
        sums = [0.0] * 4
        cnts = [0] * 4
        rin, rout = 0.30 * R, 0.70 * R
        for y in range(max(0, int(cy - rout)), min(H, int(cy + rout) + 1)):
            dy = y - cy
            for x in range(max(0, int(cx - rout)), min(W, int(cx + rout) + 1)):
                dx = x - cx
                d2 = dx * dx + dy * dy
                if d2 < rin * rin or d2 > rout * rout:
                    continue
                a = math.degrees(math.atan2(dy, dx))
                bd, bdf = -1, 1e9
                for i, da in enumerate(DIRS):
                    diff = abs(a - da) % 360
                    if diff > 180:
                        diff = 360 - diff
                    if diff <= 50 and diff < bdf:
                        bdf, bd = diff, i
                if bd < 0:
                    continue
                sums[bd] += gray[y, x]
                cnts[bd] += 1
        if any(n == 0 for n in cnts):
            continue
        qm = [sums[i] / cnts[i] for i in range(4)]
        contrast = max(qm) - min(qm)
        sr = (R * 2 / imgdiag)
        if new_rules:
            cthr = 18.0 if sr < 0.10 else 30.0
            bthr = 0.15 if sr < 0.10 else 0.35
        else:
            cthr = 5.0 if sr < 0.10 else 25.0
            bthr = 0.10 if sr < 0.10 else 0.35
        if contrast < cthr:
            continue
        idx = sorted(range(4), key=lambda i: qm[i])
        dark = set(idx[:2])
        d = [i in dark for i in range(4)]
        if not ((d[0] and d[2] and not d[1] and not d[3]) or
                (d[1] and d[3] and not d[0] and not d[2])):
            continue
        dm = (qm[idx[0]] + qm[idx[1]]) / 2
        lm = (qm[idx[2]] + qm[idx[3]]) / 2
        bw = (lm - dm) / max(lm, 1)
        if bw < bthr:
            continue
        if new_rules:
            gap = lm - dm
            within = max(abs(qm[idx[0]] - qm[idx[1]]), abs(qm[idx[2]] - qm[idx[3]]))
            if gap < 1.5 * max(within, 1.0):
                continue

        # 圆环完整性
        if new_rules:
            NS = 36
            ew, eh = w / 2, h / 2
            ca, sa = math.cos(math.radians(ang)), math.sin(math.radians(ang))
            pts = c.reshape(-1, 2)
            best = [-1.0] * NS
            bpx = [0.0] * NS
            bpy = [0.0] * NS
            for p in pts:
                aa = math.atan2(p[1] - cy, p[0] - cx)
                if aa < 0:
                    aa += 2 * math.pi
                b = min(int(aa / (2 * math.pi) * NS), NS - 1)
                dr = (p[0] - cx) ** 2 + (p[1] - cy) ** 2
                if dr > best[b]:
                    best[b], bpx[b], bpy[b] = dr, p[0], p[1]
            tol2 = max(3.0, R * 0.15) ** 2
            inside = sup = 0
            for kk in range(NS):
                aa = 2 * math.pi * kk / NS
                px = cx + ew * math.cos(aa) * ca - eh * math.sin(aa) * sa
                py = cy + ew * math.cos(aa) * sa + eh * math.sin(aa) * ca
                if px < 2 or py < 2 or px > W - 3 or py > H - 3:
                    continue
                inside += 1
                sa2 = math.atan2(py - cy, px - cx)
                if sa2 < 0:
                    sa2 += 2 * math.pi
                own = min(int(sa2 / (2 * math.pi) * NS), NS - 1)
                for off in (-1, 0, 1):
                    b = (own + off) % NS
                    if best[b] < 0:
                        continue
                    if (bpx[b] - px) ** 2 + (bpy[b] - py) ** 2 <= tol2:
                        sup += 1
                        break
            if inside < NS:
                continue
            if sup / NS < 0.80:
                continue
        else:
            hist = [0] * 24
            for p in c.reshape(-1, 2):
                aa = math.atan2(p[1] - cy, p[0] - cx)
                if aa < 0:
                    aa += 2 * math.pi
                hist[min(int(aa / (2 * math.pi) * 24), 23)] += 1
            if sum(1 for v in hist if v > 0) < 18:
                continue

        # 内圆
        rr = (0.6 * R) ** 2
        vals = []
        for y in range(max(0, int(cy - 0.6 * R)), min(H, int(cy + 0.6 * R) + 1)):
            dy = y - cy
            for x in range(max(0, int(cx - 0.6 * R)), min(W, int(cx + 0.6 * R) + 1)):
                if (x - cx) ** 2 + dy * dy <= rr:
                    vals.append(gray[y, x])
        if not vals:
            continue
        vals = np.array(vals, dtype=int)
        if new_rules:
            if vals.max() - vals.min() < 30:
                continue
            thr_i = (int(vals.min()) + int(vals.max())) / 2.0
        else:
            thr_i = (qm[idx[0]] + qm[idx[3]]) / 2.0
        ratio = (vals > thr_i).mean()
        if ratio < 0.30 or ratio > 0.95:
            continue
        out.append((cx, cy, R))

    # NMS
    out.sort(key=lambda t: -t[2])
    ded = []
    for cnd in out:
        if not any(math.hypot(cnd[0] - e[0], cnd[1] - e[1]) < max(cnd[2], e[2]) * 0.7
                   for e in ded):
            ded.append(cnd)
    return ded


def scene(name, build, expect):
    img = np.full((H, W), 235, np.uint8)
    build(img)
    o = len(pipeline(img, False))
    n = len(pipeline(img, True))
    ok = "PASS" if n == expect else "FAIL"
    print("%-34s expect=%d  OLD=%d  NEW=%d  [%s]" % (name, expect, o, n, ok))
    return n == expect


res = []
res.append(scene("2 full targets", lambda i: (
    draw_target(i, 180, 240, 90), draw_target(i, 460, 240, 55)), 2))
res.append(scene("truncated at left edge", lambda i:
                 draw_target(i, 20, 240, 90), 0))
res.append(scene("truncated at top edge", lambda i:
                 draw_target(i, 320, 25, 80), 0))
res.append(scene("full + truncated", lambda i: (
    draw_target(i, 400, 240, 85), draw_target(i, 15, 200, 70)), 1))
res.append(scene("solid black disc (noise)", lambda i:
                 cv2.circle(i, (320, 240), 90, 0, -1), 0))
res.append(scene("black square (noise)", lambda i:
                 cv2.rectangle(i, (230, 150), (410, 330), 0, -1), 0))
res.append(scene("checkerboard 2x2 (lookalike)", lambda i: (
    cv2.rectangle(i, (230, 150), (320, 240), 0, -1),
    cv2.rectangle(i, (320, 240), (410, 330), 0, -1)), 0))
res.append(scene("small distant target", lambda i:
                 draw_target(i, 320, 240, 26), 1))
res.append(scene("target w/ uneven lighting", lambda i: (
    draw_target(i, 320, 240, 95, dark=45, light=185)), 1))
res.append(scene("rotated target 30deg", lambda i:
                 draw_target(i, 320, 240, 95, rot=30), 1))

print()
print("total %d/%d scenarios pass" % (sum(res), len(res)))
