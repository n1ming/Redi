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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * image_matrix:把图片(jpg/png/bmp/gif)转成“像素矩阵”供模型解读——
 * 缩放到小尺寸后量化为 16 色调色板,每像素一个字符(0-9A-F)逐行输出,
 * 附调色板映射与主色统计。模型据此读出构图、形状、UI 布局、文字块位置等。
 * 精度是量化近似,适合解读结构,不适合辨精细纹理。
 */
public final class ImageMatrixTool implements AgentTool {

    /** 16 色调色板:索引字符 → {名称, r,g,b}。 */
    private static final Object[][] PALETTE = {
            {'0', "黑", 0x00, 0x00, 0x00},
            {'1', "深灰", 0x40, 0x40, 0x40},
            {'2', "灰", 0x80, 0x80, 0x80},
            {'3', "银", 0xC0, 0xC0, 0xC0},
            {'4', "白", 0xFF, 0xFF, 0xFF},
            {'5', "深红", 0x80, 0x00, 0x00},
            {'6', "红", 0xFF, 0x00, 0x00},
            {'7', "橙", 0xFF, 0x80, 0x00},
            {'8', "黄", 0xFF, 0xFF, 0x00},
            {'9', "深绿", 0x00, 0x80, 0x00},
            {'A', "绿", 0x00, 0xFF, 0x00},
            {'B', "深青", 0x00, 0x80, 0x80},
            {'C', "青", 0x00, 0xFF, 0xFF},
            {'D', "蓝", 0x00, 0x00, 0xFF},
            {'E', "紫", 0x80, 0x00, 0xFF},
            {'F', "粉", 0xFF, 0x69, 0xB4},
    };

    @Override
    public String name() {
        return "image_matrix";
    }

    @Override
    public String description() {
        return "把图片转成像素矩阵供你解读内容:路径 path 必填(jpg/png/bmp/gif);size 可选,最长边缩放到"
                + "多少像素(默认 24,范围 8~48)。输出:调色板映射 + 每像素一个调色板字符的逐行矩阵 + 主色统计。"
                + "解读方法:把它当低分辨率色块图读——形状、构图、明暗分布、大块色区、UI 布局与文字块位置都能看出;"
                + "它不是原图,辨不了小字与细纹理。玩家 @图片路径 导入时同样会得到此矩阵。";
    }

    @Override
    public JsonObject schema() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "图片路径,如 E:\\screens\\a.png");
        JsonObject size = new JsonObject();
        size.addProperty("type", "integer");
        size.addProperty("description", "最长边缩放像素数,默认 24,范围 8~48");
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
        int size = 24;
        try {
            if (args.has("size") && args.get("size").isJsonPrimitive()) {
                size = Math.max(8, Math.min(48, args.get("size").getAsInt()));
            }
        } catch (Exception ignored) {
        }
        Path p = Path.of(raw.trim());
        if (!p.isAbsolute()) {
            p = Path.of("").toAbsolutePath().resolve(p);
        }
        return matrix(p, size);
    }

    /** 生成像素矩阵文本(ReadFileTool 的 @图片导入共用)。 */
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

            int[] counts = new int[16];
            StringBuilder[] rows = new StringBuilder[h];
            for (int y = 0; y < h; y++) {
                rows[y] = new StringBuilder(w);
                for (int x = 0; x < w; x++) {
                    int rgb = small.getRGB(x, y);
                    int idx = nearest(rgb);
                    counts[idx]++;
                    rows[y].append(PALETTE[idx][0]);
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("图片 ").append(p.getFileName()).append(" (原 ").append(ow).append('x').append(oh)
                    .append(" → 矩阵 ").append(w).append('x').append(h).append("):\n");
            sb.append("调色板(每字符一像素):\n");
            for (Object[] c : PALETTE) {
                sb.append(' ').append(c[0]).append('=').append(c[1])
                        .append(String.format(Locale.ROOT, "(#%02X%02X%02X)", (int) c[2], (int) c[3], (int) c[4]))
                        .append('\n');
            }
            sb.append("矩阵(左→右,上→下):\n");
            for (StringBuilder row : rows) {
                sb.append(row).append('\n');
            }
            int total = w * h;
            Map<Character, Integer> top = new LinkedHashMap<>();
            for (int i = 0; i < 16; i++) {
                if (counts[i] * 100L / total >= 8) {
                    top.put((Character) PALETTE[i][0], counts[i] * 100 / total);
                }
            }
            sb.append("主色占比: ");
            boolean first = true;
            for (Map.Entry<Character, Integer> e : top.entrySet()) {
                if (!first) sb.append(", ");
                String nm = "";
                for (Object[] c : PALETTE) {
                    if (c[0] == e.getKey()) {
                        nm = (String) c[1];
                        break;
                    }
                }
                sb.append(nm).append('(').append(e.getKey()).append(")~").append(e.getValue()).append('%');
                first = false;
            }
            sb.append('\n');
            return com.redi.agent.ToolRegistry.trunc(sb.toString(), 6000);
        } catch (Throwable t) {
            return "生成像素矩阵失败: " + t;
        }
    }

    /** 最近色量化(加权欧氏距离)。 */
    private static int nearest(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int pr = (int) PALETTE[i][2], pg = (int) PALETTE[i][3], pb = (int) PALETTE[i][4];
            double dr = r - pr, dg = g - pg, db = b - pb;
            double d = 0.30 * dr * dr + 0.59 * dg * dg + 0.11 * db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
