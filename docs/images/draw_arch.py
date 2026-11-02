from PIL import Image, ImageDraw, ImageFont
import math

W, H = 2200, 1300
img = Image.new("RGB", (W, H), "#ffffff")
draw = ImageDraw.Draw(img)

try:
    font_title = ImageFont.truetype("msyh.ttc", 32)
    font_layer = ImageFont.truetype("msyh.ttc", 18)
    font_mod = ImageFont.truetype("msyh.ttc", 15)
    font_desc = ImageFont.truetype("msyh.ttc", 12)
    font_sub = ImageFont.truetype("msyh.ttc", 11)
except Exception:
    font_title = ImageFont.load_default()
    font_layer = ImageFont.load_default()
    font_mod = ImageFont.load_default()
    font_desc = ImageFont.load_default()
    font_sub = ImageFont.load_default()

colors = {
    "l1": ("#e3f2fd", "#1565c0", "#0d47a1"),
    "l2": ("#e8f5e9", "#2e7d32", "#1b5e20"),
    "l3": ("#f3e5f5", "#6a1b9a", "#4a148c"),
    "l4": ("#eceff1", "#455a64", "#263238"),
    "ext": ("#fff3e0", "#ef6c00", "#e65100"),
}

cx = W // 2
layer_w = 1100
layer_h = 175
module_w = 210
module_h = 115
module_gap = 30
layer_gap = 110
entry_w = 200
entry_h = 90


def text_size(text, font):
    bbox = draw.textbbox((0, 0), text, font=font)
    return bbox[2] - bbox[0], bbox[3] - bbox[1]


def draw_round_rect(draw, xy, fill, outline, width, radius=8):
    draw.rounded_rectangle(xy, radius=radius, fill=fill, outline=outline, width=width)


def draw_line_seg(draw, x1, y1, x2, y2, color, dashed=False, width=2):
    total_len = math.hypot(x2 - x1, y2 - y1)
    if total_len == 0:
        return
    if dashed:
        dash, gap = 6, 5
        seg = dash + gap
        t = 0
        while t < total_len:
            t1 = min(t, total_len)
            t2 = min(t + dash, total_len)
            sx = x1 + (x2 - x1) * (t1 / total_len)
            sy = y1 + (y2 - y1) * (t1 / total_len)
            ex = x1 + (x2 - x1) * (t2 / total_len)
            ey = y1 + (y2 - y1) * (t2 / total_len)
            draw.line([(sx, sy), (ex, ey)], fill=color, width=width)
            t += seg
    else:
        draw.line([(x1, y1), (x2, y2)], fill=color, width=width)


def draw_arrow_head(draw, x, y, angle, color, length=9):
    arr_angle = math.pi / 6
    x1 = x - length * math.cos(angle - arr_angle)
    y1 = y - length * math.sin(angle - arr_angle)
    x2 = x - length * math.cos(angle + arr_angle)
    y2 = y - length * math.sin(angle + arr_angle)
    draw.polygon([(x, y), (x1, y1), (x2, y2)], fill=color)


def draw_arrow(draw, x1, y1, x2, y2, color="#78909c", dashed=False, width=2):
    draw_line_seg(draw, x1, y1, x2, y2, color, dashed, width)
    angle = math.atan2(y2 - y1, x2 - x1)
    draw_arrow_head(draw, x2, y2, angle, color)


layers = [
    ("接入层 · 对外门面", "l1", [
        ("CLI Channel", "核心阶段内置渠道", "oryxos-channel-cli"),
        ("Web Service", "REST API 对外暴露", "oryxos-web"),
    ]),
    ("引擎层 · Agent 大脑", "l2", [
        ("ReActLoop", "自实现 Reason + Act", "oryxos-core"),
        ("PromptBuilder", "构造 ReAct 提示词", "oryxos-core"),
        ("ToolExecutor", "Tool 实际调度执行", "oryxos-core"),
    ]),
    ("能力层 · 三大核心能力", "l3", [
        ("Provider", "对接 LLM", "oryxos-provider"),
        ("Memory", "三层记忆统一门面", "oryxos-memory"),
        ("Tool", "内置 Tool + Plugin 接入", "oryxos-tool"),
    ]),
    ("基础层 · 工程地基", "l4", [
        ("Profile / Bootstrap", "Skill 加载与启动", "oryxos-core"),
        ("Session 存储", "会话与对话历史", "oryxos-storage"),
        ("SQLite", "审计 day one 落库", "oryxos-storage"),
        ("配置与密钥", "Provider 映射与密钥", "oryxos-core"),
    ]),
]

top_y = 110
layer_positions = []
for i, (name, key, mods) in enumerate(layers):
    y = top_y + i * (layer_h + layer_gap)
    layer_positions.append((name, key, mods, y))

module_centers = {}
for name, key, mods, y in layer_positions:
    x1 = cx - layer_w // 2
    y1 = y
    x2 = cx + layer_w // 2
    y2 = y + layer_h
    draw_round_rect(draw, (x1, y1, x2, y2), "#fffde7", "#f0f4c3", 2)
    tw, th = text_size(name, font_layer)
    draw.text((cx - tw // 2, y1 + 12), name, fill="#37474f", font=font_layer)

    total_w = len(mods) * module_w + (len(mods) - 1) * module_gap
    start_x = cx - total_w // 2
    my = y1 + 45
    fill, stroke, textc = colors[key]

    for idx, (mname, mdesc, msub) in enumerate(mods):
        mx = start_x + idx * (module_w + module_gap)
        draw_round_rect(draw, (mx, my, mx + module_w, my + module_h), fill, stroke, 2)

        tw, th = text_size(mname, font_mod)
        draw.text((mx + (module_w - tw) // 2, my + 20), mname, fill=textc, font=font_mod)
        dw, dh = text_size(mdesc, font_desc)
        draw.text((mx + (module_w - dw) // 2, my + 45), mdesc, fill="#546e7a", font=font_desc)
        sw, sh = text_size(msub, font_sub)
        draw.text((mx + (module_w - sw) // 2, my + module_h - 22), msub, fill="#78909c", font=font_sub)

        module_centers[mname] = {
            "x": mx + module_w // 2,
            "y": my + module_h // 2,
            "top": my,
            "bottom": my + module_h,
            "left": mx,
            "right": mx + module_w,
        }

# 启动入口
entry_x = 70
entry_y = layer_positions[0][3] + 55
draw.text((entry_x, entry_y - 32), "启动入口", fill="#37474f", font=font_layer)
entries = [
    ("oryxos-cli", "Picocli 入口"),
    ("oryxos-boot", "fat JAR"),
]
entry_right_points = []
for idx, (ename, esub) in enumerate(entries):
    ey = entry_y + idx * (entry_h + 30)
    fill, stroke, textc = colors["ext"]
    draw_round_rect(draw, (entry_x, ey, entry_x + entry_w, ey + entry_h), fill, stroke, 2)
    tw, th = text_size(ename, font_mod)
    draw.text((entry_x + (entry_w - tw) // 2, ey + 24), ename, fill=textc, font=font_mod)
    sw, sh = text_size(esub, font_sub)
    draw.text((entry_x + (entry_w - sw) // 2, ey + entry_h - 24), esub, fill="#78909c", font=font_sub)
    entry_right_points.append((entry_x + entry_w, ey + entry_h // 2))

# 外部依赖
ext_x = W - 70 - entry_w
ext_y = layer_positions[2][3] + 55
draw.text((ext_x, ext_y - 32), "外部依赖", fill="#37474f", font=font_layer)
exts = [
    "LLM 厂商 API",
    "外部 MCP Server",
]
ext_left_points = []
for idx, ename in enumerate(exts):
    ey = ext_y + idx * (entry_h + 30)
    fill, stroke, textc = colors["ext"]
    draw_round_rect(draw, (ext_x, ey, ext_x + entry_w, ey + entry_h), fill, stroke, 2)
    tw, th = text_size(ename, font_mod)
    draw.text((ext_x + (entry_w - tw) // 2, ey + (entry_h - th) // 2), ename, fill=textc, font=font_mod)
    ext_left_points.append((ext_x, ey + entry_h // 2))

# 层间主链路：粗垂直箭头
for i in range(len(layer_positions) - 1):
    y1 = layer_positions[i][3] + layer_h
    y2 = layer_positions[i + 1][3]
    draw_arrow(draw, cx - 2, y1 + 4, cx - 2, y2 - 4, color="#37474f", width=3)
    draw_arrow(draw, cx + 2, y1 + 4, cx + 2, y2 - 4, color="#37474f", width=3)

# 启动入口 -> 接入层（L 形路径，避免穿过模块）
layer_left = cx - layer_w // 2
for i, (px, py) in enumerate(entry_right_points):
    target = "CLI Channel" if i == 0 else "Web Service"
    tx = module_centers[target]["left"] - 4
    ty = module_centers[target]["y"]
    mid_x = (px + tx) // 2
    # 水平 -> 垂直 -> 水平
    draw_line_seg(draw, px, py, mid_x, py, color="#90a4ae", dashed=True, width=1)
    draw_line_seg(draw, mid_x, py, mid_x, ty, color="#90a4ae", dashed=True, width=1)
    draw_arrow(draw, mid_x, ty, tx, ty, color="#90a4ae", dashed=True, width=1)

# 接入层 -> 引擎层
for src in ["CLI Channel", "Web Service"]:
    draw_arrow(draw, module_centers[src]["x"], module_centers[src]["bottom"] + 2,
               module_centers["ReActLoop"]["x"], module_centers["ReActLoop"]["top"] - 6, color="#78909c")

# 引擎层 -> 能力层
draw_arrow(draw, module_centers["ReActLoop"]["x"], module_centers["ReActLoop"]["bottom"] + 2,
           module_centers["Provider"]["x"], module_centers["Provider"]["top"] - 6, color="#78909c")
draw_arrow(draw, module_centers["PromptBuilder"]["x"], module_centers["PromptBuilder"]["bottom"] + 2,
           module_centers["Memory"]["x"], module_centers["Memory"]["top"] - 6, color="#78909c")
draw_arrow(draw, module_centers["ToolExecutor"]["x"], module_centers["ToolExecutor"]["bottom"] + 2,
           module_centers["Tool"]["x"], module_centers["Tool"]["top"] - 6, color="#78909c")

# 能力层 -> 外部依赖
draw_arrow(draw, module_centers["Provider"]["right"] + 2, module_centers["Provider"]["y"],
           ext_left_points[0][0] - 2, ext_left_points[0][1], color="#90a4ae", dashed=True)
draw_arrow(draw, module_centers["Tool"]["right"] + 2, module_centers["Tool"]["y"],
           ext_left_points[1][0] - 2, ext_left_points[1][1], color="#90a4ae", dashed=True)

# 基础层支撑虚线（L 形路径避免交叉）
support_routes = [
    ("Profile / Bootstrap", "PromptBuilder", "left"),
    ("Session 存储", "Memory", "center"),
    ("SQLite", "ToolExecutor", "right"),
    ("配置与密钥", "Provider", "right"),
]
for src, dst, side in support_routes:
    sx = module_centers[src]["x"]
    sy = module_centers[src]["top"]
    dx = module_centers[dst]["x"]
    dy = module_centers[dst]["bottom"]
    if side == "left":
        offset = 45
        mx = sx - offset
        draw_line_seg(draw, sx, sy, mx, sy, color="#b0bec5", dashed=True, width=1)
        draw_line_seg(draw, mx, sy, mx, dy, color="#b0bec5", dashed=True, width=1)
        draw_arrow(draw, mx, dy, dx, dy, color="#b0bec5", dashed=True, width=1)
    elif side == "right":
        offset = 45
        mx = sx + offset
        draw_line_seg(draw, sx, sy, mx, sy, color="#b0bec5", dashed=True, width=1)
        draw_line_seg(draw, mx, sy, mx, dy, color="#b0bec5", dashed=True, width=1)
        draw_arrow(draw, mx, dy, dx, dy, color="#b0bec5", dashed=True, width=1)
    else:
        draw_arrow(draw, sx, sy, dx, dy, color="#b0bec5", dashed=True, width=1)

# 标题
title = "OryxOS 系统架构图"
tw, th = text_size(title, font_title)
draw.text((cx - tw // 2, 40), title, fill="#263238", font=font_title)

# 保存
out_png = "g:\\code\\ORYXOS\\docs\\images\\architecture.png"
img.save(out_png, "PNG")

# 生成简化 SVG
svg_parts = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    f'<svg width="{W}" height="{H}" xmlns="http://www.w3.org/2000/svg">',
    f'<rect width="{W}" height="{H}" fill="#ffffff"/>',
    f'<text x="{cx}" y="70" text-anchor="middle" font-size="32" fill="#263238" font-family="sans-serif">{title}</text>',
]
for name, key, mods, y in layer_positions:
    x1 = cx - layer_w // 2
    y1 = y
    svg_parts.append(f'<rect x="{x1}" y="{y1}" width="{layer_w}" height="{layer_h}" rx="8" ry="8" fill="#fffde7" stroke="#f0f4c3" stroke-width="2"/>')
    svg_parts.append(f'<text x="{cx}" y="{y1+28}" text-anchor="middle" font-size="18" fill="#37474f" font-family="sans-serif">{name}</text>')
    fill, stroke, textc = colors[key]
    total_w = len(mods) * module_w + (len(mods) - 1) * module_gap
    start_x = cx - total_w // 2
    my = y1 + 45
    for idx, (mname, mdesc, msub) in enumerate(mods):
        mx = start_x + idx * (module_w + module_gap)
        svg_parts.append(f'<rect x="{mx}" y="{my}" width="{module_w}" height="{module_h}" rx="8" ry="8" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
        svg_parts.append(f'<text x="{mx+module_w//2}" y="{my+33}" text-anchor="middle" font-size="15" fill="{textc}" font-family="sans-serif">{mname}</text>')
        svg_parts.append(f'<text x="{mx+module_w//2}" y="{my+58}" text-anchor="middle" font-size="12" fill="#546e7a" font-family="sans-serif">{mdesc}</text>')
        svg_parts.append(f'<text x="{mx+module_w//2}" y="{my+module_h-10}" text-anchor="middle" font-size="11" fill="#78909c" font-family="sans-serif">{msub}</text>')

svg_parts.append(f'<text x="{entry_x}" y="{entry_y-12}" font-size="18" fill="#37474f" font-family="sans-serif">启动入口</text>')
for idx, (ename, esub) in enumerate(entries):
    ey = entry_y + idx * (entry_h + 30)
    fill, stroke, textc = colors["ext"]
    svg_parts.append(f'<rect x="{entry_x}" y="{ey}" width="{entry_w}" height="{entry_h}" rx="8" ry="8" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
    svg_parts.append(f'<text x="{entry_x+entry_w//2}" y="{ey+42}" text-anchor="middle" font-size="15" fill="{textc}" font-family="sans-serif">{ename}</text>')
    svg_parts.append(f'<text x="{entry_x+entry_w//2}" y="{ey+entry_h-10}" text-anchor="middle" font-size="11" fill="#78909c" font-family="sans-serif">{esub}</text>')

svg_parts.append(f'<text x="{ext_x}" y="{ext_y-12}" font-size="18" fill="#37474f" font-family="sans-serif">外部依赖</text>')
for idx, ename in enumerate(exts):
    ey = ext_y + idx * (entry_h + 30)
    fill, stroke, textc = colors["ext"]
    svg_parts.append(f'<rect x="{ext_x}" y="{ey}" width="{entry_w}" height="{entry_h}" rx="8" ry="8" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
    svg_parts.append(f'<text x="{ext_x+entry_w//2}" y="{ey+entry_h//2+5}" text-anchor="middle" font-size="15" fill="{textc}" font-family="sans-serif">{ename}</text>')

svg_parts.append('</svg>')
with open("g:\\code\\ORYXOS\\docs\\images\\architecture.svg", "w", encoding="utf-8") as f:
    f.write("\n".join(svg_parts))

print("Saved:", out_png)
