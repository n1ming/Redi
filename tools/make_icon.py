# -*- coding: utf-8 -*-
# 生成 Redi 应用图标(32x32):暗色圆角底 + 发光红石火把 + 火花。
# 仅用旧版 Pillow 也支持的基础 API(rectangle/ellipse/point)。
# 运行:python tools/make_icon.py
from PIL import Image, ImageDraw

SIZE = 32
img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 暗色底盘 + 1px 描边(圆角用手工切角实现)
d.rectangle([2, 0, SIZE - 3, SIZE - 1], fill=(23, 23, 28, 255))
d.rectangle([0, 2, SIZE - 1, SIZE - 3], fill=(23, 23, 28, 255))
for cx, cy in ((0, 0), (SIZE - 1, 0), (0, SIZE - 1), (SIZE - 1, SIZE - 1)):
    d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=(23, 23, 28, 255))
d.rectangle([2, 0, SIZE - 3, SIZE - 1], outline=(90, 90, 100, 255))
d.rectangle([0, 2, SIZE - 1, SIZE - 3], outline=(90, 90, 100, 255))

# 红石火把:木棍 + 发光红石头部
for y in range(15, 29):  # 木棍
    d.point((15, y), fill=(107, 66, 38, 255))
    d.point((16, y), fill=(139, 90, 43, 255))

cx, cy = 15.5, 9.5  # 头部辉光(径向渐变,只叠加在底盘不透明像素上)
for y in range(SIZE):
    for x in range(SIZE):
        dist2 = (x - cx) ** 2 + (y - cy) ** 2
        if dist2 <= 81:
            a = max(0, int(90 * (1 - dist2 / 81.0) ** 2))
            if a <= 0:
                continue
            px = img.getpixel((x, y))
            if px[3] == 0:
                continue
            if 13 <= x <= 18 and 15 <= y <= 28:
                continue  # 不染木棍
            r = min(255, px[0] + a)
            g = max(0, px[1] - a // 2)
            b = max(0, px[2] - a // 2)
            img.putpixel((x, y), (r, g, b, 255))

d.rectangle([10, 4, 21, 14], fill=(154, 21, 21, 255))   # 头部主体
d.rectangle([11, 5, 20, 13], fill=(230, 46, 46, 255))   # 亮面
d.ellipse([13, 6, 17, 10], fill=(255, 120, 110, 255))   # 高光核
d.point((14, 7), fill=(255, 210, 200, 255))

for (sx, sy, c) in [(24, 6, (255, 90, 80, 220)), (7, 12, (255, 90, 80, 200)),   # 火花
                    (25, 17, (230, 46, 46, 190)), (6, 24, (230, 46, 46, 170))]:
    d.point((sx, sy), fill=c)
    d.point((sx + 1, sy), fill=(180, 40, 40, 140))

img.save('src/main/resources/assets/mcagent/textures/gui/agent_icon.png')
print('icon written: 32x32 redstone torch')
