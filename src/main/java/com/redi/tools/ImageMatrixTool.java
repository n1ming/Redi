package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ToolRegistry;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * image_matrix:图片 → 像素矩阵,供模型解读。算法移植自 PixelTool(pixeltool.art)
 * 的 pixelArt.worker:直方图去重 → 加权中位切分调色板(按像素数加权分裂/均值)
 * → 亮度加权距离映射;透明像素(alpha<128)不参与量化,矩阵中以空格表示。
 *
 * <p>默认最长边 32(8~64 可调),调色板色数 = 矩阵尺寸×2(8~64)。
 * 输出:按亮度排序的调色板(索引=RGB+近似色名+占比)+ 逐行索引矩阵 + 主色统计。</p>
 */
public final class ImageMatrixTool implements AgentTool {

    /** 矩阵索引字符表(64 个,与调色板色数上限一致)。 */
    private static final char[] SYMBOLS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
            'G', 'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
            'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'm', 'n', 'o',
            'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '!', '#', '$', '%', '&'};

    @Override
    public String name() {
        return "image_matrix";
    }

    @Override
    public String description() {
        return "把图片转成像素矩阵供你解读内容:路径 path 必填(jpg/png/bmp/gif);size 可选,最长边缩放到"
                + "多少像素(默认 32,范围 8~64)。颜色为自适应调色板(PixelTool 同款加权中位切分,"
                + "色数=尺寸×2),每像素一个索引字符逐行输出,透明像素为空格;附按亮度排序的调色板与主色占比。"
                + "解读方法:当低分辨率色块图读——构图、形状、明暗、色区、UI 布局、文字块位置;辨不了小字细纹理。"
                + "MC 素材用途时建议 size=16 或 32(物品贴图惯例)。玩家 @图片路径 导入时同样得到此矩阵。";
    }

    @Override
    public JsonObject schema() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "图片路径,如 E:\\screens\\a.png");
        JsonObject size = new JsonObject();
        size.addProperty("type", "integer");
        size.addProperty("description", "最长边缩放像素数,默认 32,范围 8~64;MC 素材建议 16/32");
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
        String raw = ToolRegistry.argStr(args, "path");
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
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();

            // ---- 直方图(跳过 alpha<128 的透明像素)----
            Map<Integer, long[]> hist = new LinkedHashMap<>(); // rgb → {r,g,b,count}
            boolean[][] opaque = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = small.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    if (a < 128) {
                        continue; // PixelTool 同款:半透明以下视为透明
                    }
                    opaque[y][x] = true;
                    int r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    long[] e = hist.computeIfAbsent((r << 16) | (gr << 8) | b,
                            k -> new long[]{r, gr, b, 0});
                    e[3]++;
                }
            }
            if (hist.isEmpty()) {
                return "图片整体透明,无内容可解读: " + p.getFileName();
            }

            // ---- 加权中位切分调色板(色数 = min(64, 尺寸×2))----
            int colorCount = Math.max(8, Math.min(64, Math.min(maxSide * 2, hist.size())));
            List<Box> boxes = new ArrayList<>();
            boxes.add(new Box(hist.values()));
            while (boxes.size() < colorCount) {
                Box widest = null;
                int widestRange = 0;
                for (Box b : boxes) {
                    if (b.entries.size() >= 2 && b.range > widestRange) {
                        widestRange = b.range;
                        widest = b;
                    }
                }
                if (widest == null || widestRange <= 2) {
                    break; // 颜色已收敛
                }
                int ch = widest.channel;
                widest.entries.sort(Comparator.comparingLong(e -> e.get(ch)));
                long total = 0;
                for (BoxEntry e : widest.entries) total += e.count;
                long acc = 0;
                int mid = widest.entries.size() - 1;
                for (int i = 0; i < widest.entries.size(); i++) {
                    acc += widest.entries.get(i).count;
                    if (acc >= total / 2) {
                        mid = Math.max(1, i);
                        break;
                    }
                }
                List<BoxEntry> tail = new ArrayList<>(widest.entries.subList(mid, widest.entries.size()));
                widest.entries.subList(mid, widest.entries.size()).clear();
                widest.recompute();
                boxes.add(new Box(tail));
            }
            List<int[]> palette = new ArrayList<>();
            for (Box b : boxes) palette.add(b.color);

            // ---- 像素映射(亮度加权距离;透明=空格)----
            int[] counts = new int[palette.size()];
            char[][] grid = new char[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!opaque[y][x]) {
                        grid[y][x] = ' ';
                        continue;
                    }
                    int argb = small.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    int bi = 0;
                    double bestD = Double.MAX_VALUE;
                    for (int i = 0; i < palette.size(); i++) {
                        int[] c = palette.get(i);
                        int n = (c[0] + c[1] + c[2]) / 3;
                        double dr = r - c[0], dg = gr - c[1], db = b - c[2];
                        // PixelTool 同款亮度加权距离:绿 4 倍,红/蓝随调色板色亮度调整
                        double d = (2 + n / 256.0) * dr * dr + 4 * dg * dg + (2 + (255 - n) / 256.0) * db * db;
                        if (d < bestD) {
                            bestD = d;
                            bi = i;
                        }
                    }
                    grid[y][x] = SYMBOLS[bi];
                    counts[bi]++;
                }
            }

            // ---- 输出:调色板按亮度降序 + 矩阵 + 主色 ----
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < palette.size(); i++) order.add(i);
            order.sort(Comparator.comparingInt((Integer i) -> lum(palette.get(i))).reversed());
            long totalOpaque = 0;
            for (int c : counts) totalOpaque += c;

            StringBuilder sb = new StringBuilder();
            sb.append("图片 ").append(p.getFileName()).append(" (原 ").append(ow).append('x').append(oh)
                    .append(" → 矩阵 ").append(w).append('x').append(h)
                    .append(", 自适应调色板 ").append(palette.size()).append(" 色;空格=透明):\n");
            sb.append("调色板(索引=近似色名 RGB, 占比):\n");
            for (int i : order) {
                if (counts[i] == 0) continue;
                int[] c = palette.get(i);
                sb.append(' ').append(SYMBOLS[i]).append('=').append(approxName(c[0], c[1], c[2]))
                        .append(String.format(Locale.ROOT, "(#%02X%02X%02X, %s)", c[0], c[1], c[2],
                                pct(counts[i], totalOpaque))).append('\n');
            }
            sb.append("矩阵(每字符一像素,左→右,上→下;空格=透明):\n");
            for (int y = 0; y < h; y++) {
                sb.append(grid[y]).append('\n');
            }
            return ToolRegistry.trunc(sb.toString(), 6000);
        } catch (Throwable t) {
            return "生成像素矩阵失败: " + t;
        }
    }

    private static int lum(int[] c) {
        return (int) (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]);
    }

    private static String pct(long part, long total) {
        return total == 0 ? "0%" : (part * 100 / total) + "%";
    }

    /** RGB 的中文近似色名。 */
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

    // ---------------------------------------------------------------- 加权中位切分

    /** 直方图条目:r,g,b + 像素计数(可按通道索引取值)。 */
    static final class BoxEntry {
        final long r, g, b, count;

        BoxEntry(long r, long g, long b, long count) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.count = count;
        }

        long get(int ch) {
            return ch == 0 ? r : ch == 1 ? g : b;
        }
    }

    /** 中位切分的“箱”:条目集合 + 缓存的跨度/主通道/加权均值色。 */
    static final class Box {
        final List<BoxEntry> entries;
        final int range;
        final int channel;
        int[] color;

        Box(Iterable<long[]> histValues) {
            entries = new ArrayList<>();
            for (long[] e : histValues) {
                entries.add(new BoxEntry(e[0], e[1], e[2], e[3]));
            }
            int[] mm = minMax();
            channel = widestChannel(mm);
            range = mm[channel + 3] - mm[channel];
            color = weightedAvg();
        }

        Box(List<BoxEntry> es) {
            entries = es;
            int[] mm = minMax();
            channel = widestChannel(mm);
            range = mm[channel + 3] - mm[channel];
            color = weightedAvg();
        }

        /** 分裂后剩余部分重算加权均值色。 */
        void recompute() {
            color = weightedAvg();
        }

        private int[] weightedAvg() {
            long r = 0, g = 0, b = 0, w = 0;
            for (BoxEntry e : entries) {
                r += e.r * e.count;
                g += e.g * e.count;
                b += e.b * e.count;
                w += e.count;
            }
            return w == 0 ? new int[]{0, 0, 0}
                    : new int[]{(int) (r / w), (int) (g / w), (int) (b / w)};
        }

        private int[] minMax() {
            int[] n = {255, 255, 255};
            int[] x = {0, 0, 0};
            for (BoxEntry e : entries) {
                for (int c = 0; c < 3; c++) {
                    long v = e.get(c);
                    if (v < n[c]) n[c] = (int) v;
                    if (v > x[c]) x[c] = (int) v;
                }
            }
            return new int[]{n[0], n[1], n[2], x[0], x[1], x[2]};
        }

        private int widestChannel(int[] mm) {
            int ch = 0;
            if (mm[4] - mm[1] >= mm[5] - mm[2] && mm[4] - mm[1] >= mm[3] - mm[0]) ch = 1;
            if (mm[5] - mm[2] >= mm[ch + 3] - mm[ch]) ch = 2;
            return ch;
        }
    }
}
