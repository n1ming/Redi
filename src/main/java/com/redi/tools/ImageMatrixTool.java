package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * image_matrix:把图片(jpg/png/bmp/gif)转成“像素矩阵”供模型解读——细致版。
 *
 * <p>细节增强(相对固定 16 色的初版):
 * 1) <b>自适应调色板</b>:中位切分(median-cut)对图片自身取色,颜色数 = size×2(默认 64 色),
 *    复杂图也能接近还原,不再被固定调色板抹平渐变;
 * 2) 默认最长边 32(可调 8~64),复杂图建议 40~64;
 * 3) 主色统计沿用:让模型先看大盘再读矩阵。</p>
 *
 * <p>输出解读方法同前:低分辨率色块图,读构图/形状/明暗/色区/UI 布局;辨不了小字细纹理。</p>
 */
public final class ImageMatrixTool implements AgentTool {

    @Override
    public String name() {
        return "image_matrix";
    }

    @Override
    public String description() {
        return "把图片转成像素矩阵供你解读内容:路径 path 必填(jpg/png/bmp/gif);size 可选,最长边缩放到"
                + "多少像素(默认 32,范围 8~64)。颜色为自适应调色板(按图片自身取色,默认 64 色,复杂图也接近还原),"
                + "每像素一个索引字符(0-Z)逐行输出,附调色板(索引=RGB 与色名近似)与主色占比。"
                + "解读方法:当低分辨率色块图读——构图、形状、明暗、色区、UI 布局、文字块位置;"
                + "辨不了小字与细纹理。玩家 @图片路径 导入时同样得到此矩阵。";
    }

    @Override
    public JsonObject schema() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "图片路径,如 E:\\screens\\a.png");
        JsonObject size = new JsonObject();
        size.addProperty("type", "integer");
        size.addProperty("description", "最长边缩放像素数,默认 32,范围 8~64;复杂图建议 40~64");
        JsonObject props = new JsonObject();
        props.add("path", path);
        props.add("size", size);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("path");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String raw = com.redi.agent.ToolRegistry.argStr(args, "path");
        if (raw == null || raw.isBlank()) {
            return "缺少参数 path。";
        }
        int size = 32;
        try {
            if (args.has("size") && args.get("size").isJsonPrimitive()) {
                size = Math.max(8, Math.min(64, args.get("size").getAsInt()));
            }
        } catch (Exception ignored) {
        }
        Path p = Path.of(raw.trim());
        if (!p.isAbsolute()) {
            p = Path.of("").toAbsolutePath().resolve(p);
        }
        return matrix(p, size);
    }

    /** 生成像素矩阵文本(@图片导入共用)。 */
    static String matrix(Path p, int maxSide) {
        try {
            if (!Files.isRegularFile(p)) {
                return "图片不存在: " + p;
            }
            BufferedImage src = ImageIO.read(p.toFile());
            if (src == null) {
                return "不是可解码的图片(jpg/png/bmp/gif): " + p.getFileName();
            }
            int ow = src.getWidth(), oh = src.getHeight();
            double scale = (double) maxSide / Math.max(ow, oh);
            int w = Math.max(1, (int) Math.round(ow * scale));
            int h = Math.max(1, (int) Math.round(oh * scale));
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();

            // ---- 自适应调色板:中位切分 ----
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = small.getRGB(x, y);
                }
            }
            int colorCount = Math.min(64, Math.max(8, maxSide * 2));
            List<int[]> palette = medianCut(pixels, colorCount);

            // 像素 → 最近调色板索引
            char[] symbols = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
                    'G', 'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
                    'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'm', 'n', 'o',
                    'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '!', '#', '$', '%', '&'};
            int[] idxOf = new int[w * h];
            int[] counts = new int[palette.size()];
            for (int i = 0; i < pixels.length; i++) {
                int bi = nearest(pixels[i], palette);
                idxOf[i] = bi;
                counts[bi]++;
            }
            if (palette.size() > symbols.length) {
                palette = palette.subList(0, symbols.length); // 中位切分可能少产生一箱,截断到符号表长
            }

            StringBuilder sb = new StringBuilder();
            sb.append("图片 ").append(p.getFileName()).append(" (原 ").append(ow).append('x').append(oh)
                    .append(" → 矩阵 ").append(w).append('x').append(h)
                    .append(", 自适应调色板 ").append(palette.size()).append(" 色):\n");
            sb.append("调色板(索引字符=RGB,矩阵按索引读):\n");
            // 按占比降序输出调色板,让模型先看主要颜色
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < palette.size(); i++) order.add(i);
            order.sort(Comparator.comparingInt((Integer i) -> counts[i]).reversed());
            for (int i : order) {
                int[] c = palette.get(i);
                String nm = approxName(c[0], c[1], c[2]);
                sb.append(' ').append(symbols[i]).append('=').append(nm)
                        .append(String.format(Locale.ROOT, "(#%02X%02X%02X, %s)", c[0], c[1], c[2],
                                pct(counts[i], w * h))).append('\n');
            }
            sb.append("矩阵(每字符一像素,行=x,列=y;左→右,上→下):\n");
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    sb.append(symbols[idxOf[y * w + x]]);
                }
                sb.append('\n');
            }
            return com.redi.agent.ToolRegistry.trunc(sb.toString(), 6000);
        } catch (Throwable t) {
            return "生成像素矩阵失败: " + t;
        }
    }

    /** 中位切分:把像素集合递归按最大色差通道对半拆,直到够 colorCount 个箱,取箱内均值。 */
    private static List<int[]> medianCut(int[] pixels, int colorCount) {
        List<List<int[]>> boxes = new ArrayList<>();
        List<int[]> first = new ArrayList<>(pixels.length);
        for (int v : pixels) first.add(new int[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF});
        boxes.add(first);
        while (boxes.size() < colorCount) {
            // 找“最宽”的箱(色差最大)
            List<int[]> widest = null;
            int widestRange = 0;
            for (List<int[]> box : boxes) {
                if (box.size() < 2) continue;
                int range = boxRange(box);
                if (range > widestRange) {
                    widestRange = range;
                    widest = box;
                }
            }
            if (widest == null || widestRange <= 2) break; // 颜色已收敛
            // 按最大色差通道排序取中位
            int ch = widestChannel(widest);
            widest.sort(Comparator.comparingInt(px -> px[ch]));
            int mid = widest.size() / 2;
            boxes.add(new ArrayList<>(widest.subList(mid, widest.size())));
            widest.subList(mid, widest.size()).clear();
        }
        List<int[]> palette = new ArrayList<>();
        for (List<int[]> box : boxes) {
            if (box.isEmpty()) continue;
            long r = 0, g = 0, b = 0;
            for (int[] px : box) {
                r += px[0];
                g += px[1];
                b += px[2];
            }
            palette.add(new int[]{(int) (r / box.size()), (int) (g / box.size()), (int) (b / box.size())});
        }
        return palette;
    }

    /** 箱内最大通道跨度。 */
    private static int boxRange(List<int[]> box) {
        int[] min = {255, 255, 255};
        int[] max = {0, 0, 0};
        for (int[] px : box) {
            for (int c = 0; c < 3; c++) {
                if (px[c] < min[c]) min[c] = px[c];
                if (px[c] > max[c]) max[c] = px[c];
            }
        }
        return Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
    }

    /** 箱内最大色差通道编号。 */
    private static int widestChannel(List<int[]> box) {
        int[] min = {255, 255, 255};
        int[] max = {0, 0, 0};
        for (int[] px : box) {
            for (int c = 0; c < 3; c++) {
                if (px[c] < min[c]) min[c] = px[c];
                if (px[c] > max[c]) max[c] = px[c];
            }
        }
        int ch = 0;
        if (max[1] - min[1] >= max[0] - min[0] && max[1] - min[1] >= max[2] - min[2]) ch = 1;
        if (max[2] - min[2] >= max[ch] - min[ch]) ch = 2;
        return ch;
    }

    /** 最近调色板色(加权欧氏)。 */
    private static int nearest(int rgb, List<int[]> palette) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < palette.size(); i++) {
            int[] c = palette.get(i);
            double dr = r - c[0], dg = g - c[1], db = b - c[2];
            double d = 0.30 * dr * dr + 0.59 * dg * dg + 0.11 * db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** RGB 的中文近似色名(供模型理解)。 */
    private static String approxName(int r, int g, int b) {
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        int sat = mx == 0 ? 0 : (mx - mn) * 100 / mx;
        if (sat < 15) {
            if (mx < 45) return "黑";
            if (mx < 105) return "深灰";
            if (mx < 165) return "灰";
            if (mx < 225) return "银灰";
            return "白";
        }
        String base;
        if (r >= g && r >= b) {
            base = g >= r * 3 / 5 ? (b >= g ? "粉" : "橙黄") : "红";
        } else if (g >= r && g >= b) {
            base = b >= g ? "青" : (r >= g * 3 / 5 ? "黄绿" : "绿");
        } else {
            base = r >= b * 3 / 5 ? "紫" : (g >= b * 2 / 5 ? "天蓝" : "蓝");
        }
        return (mx < 105 ? "深" : mx < 185 ? "" : "亮") + base;
    }

    private static String pct(long part, long total) {
        return (part * 100 / total) + "%";
    }
}
