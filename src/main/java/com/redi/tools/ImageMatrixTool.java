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
        JsonObject mode = new JsonObject();
        mode.addProperty("type", "string");
        JsonArray modes = new JsonArray();
        modes.add("matrix");
        modes.add("build");
        modes.add("both");
        mode.add("enum", modes);
        mode.addProperty("description", "matrix=仅像素矩阵;build=调色板方块映射+逐行施工单(像素画建造任务用这个);"
                + "both=矩阵+施工单(默认)");
        JsonObject props = new JsonObject();
        props.add("path", path);
        props.add("size", size);
        props.add("mode", mode);
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
        String mode = ToolRegistry.argStr(args, "mode");
        Path p = Path.of(raw.trim());
        if (!p.isAbsolute()) {
            p = Path.of("").toAbsolutePath().resolve(p);
        }
        return matrix(p, size, mode == null || mode.isBlank() ? "both" : mode.trim().toLowerCase(Locale.ROOT));
    }

    /** 生成像素矩阵文本(@图片导入共用)。 */
    static String matrix(Path p, int maxSide, String mode) {
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
            boolean wantBuild = mode.equals("build") || mode.equals("both");
            boolean wantMatrix = mode.equals("matrix") || mode.equals("both");
            sb.append("调色板(索引=近似色名 RGB→参考方块, 占比):\n");
            for (int i : order) {
                if (counts[i] == 0) continue;
                int[] c = palette.get(i);
                String blk = SYMBOLS[i] == ' ' ? "air" : nearestBlock(c[0], c[1], c[2]);
                sb.append(' ').append(SYMBOLS[i]).append('=').append(approxName(c[0], c[1], c[2]))
                        .append(String.format(Locale.ROOT, "(#%02X%02X%02X)", c[0], c[1], c[2]))
                        .append("→").append(blk)
                        .append(", ").append(pct(counts[i], totalOpaque)).append('\n');
            }
            if (wantBuild) {
                sb.append('\n').append(buildPlan(grid, palette, SYMBOLS));
            }
            if (wantMatrix) {
                sb.append("\n矩阵(每字符一像素,左→右,上→下;空格=透明):\n");
                for (int y = 0; y < h; y++) {
                    sb.append(grid[y]).append('\n');
                }
            }
            return ToolRegistry.trunc(sb.toString(), 9000);
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

    // ---------------------------------------------------------------- MC 方块映射

    /** 建造用的 MC 方块调色板(名称 + RGB),覆盖常见色域(混凝土系为主)。 */
    private static final Object[][] MC_BLOCKS = {
            {"white_concrete", 207, 213, 214},
            {"light_gray_concrete", 125, 125, 115},
            {"gray_concrete", 54, 57, 61},
            {"black_concrete", 8, 10, 15},
            {"brown_concrete", 96, 59, 31},
            {"red_concrete", 142, 33, 33},
            {"orange_concrete", 224, 97, 0},
            {"yellow_concrete", 240, 175, 21},
            {"lime_concrete", 94, 168, 24},
            {"green_concrete", 84, 109, 27},
            {"cyan_concrete", 21, 119, 136},
            {"light_blue_concrete", 125, 187, 221},
            {"blue_concrete", 44, 46, 143},
            {"purple_concrete", 100, 31, 156},
            {"magenta_concrete", 191, 70, 165},
            {"pink_concrete", 237, 141, 172},
            {"gold_block", 246, 208, 61},
            {"iron_block", 220, 220, 220},
            {"netherite_block", 68, 58, 64},
            {"terracotta", 152, 94, 67},
            {"white_terracotta", 209, 178, 161},
            {"brown_terracotta", 77, 51, 35},
            {"yellow_terracotta", 186, 133, 35},
            {"red_sand", 190, 102, 33},
            {"sand", 219, 207, 163},
            {"oak_planks", 162, 130, 78},
            {"spruce_planks", 114, 84, 48},
            {"coal_block", 11, 11, 11},
            {"lapis_block", 31, 60, 126},
            {"emerald_block", 42, 203, 96},
    };

    /** 调色板 RGB → 最近的 MC 方块名。 */
    private static String nearestBlock(int r, int g, int b) {
        String best = "white_concrete";
        double bestD = Double.MAX_VALUE;
        for (Object[] blk : MC_BLOCKS) {
            double dr = r - (int) blk[1], dg = g - (int) blk[2], db = b - (int) blk[3];
            double d = 0.30 * dr * dr + 0.59 * dg * dg + 0.11 * db * db;
            if (d < bestD) {
                bestD = d;
                best = (String) blk[0];
            }
        }
        return best;
    }

    /**
     * 像素画施工单:调色板色 → MC 方块映射 + 每行 RLE 游程。
     * 模型按行执行 /fill 或逐段放置,零色彩判断、零转写错误。
     */
    private static String buildPlan(char[][] grid, List<int[]> palette, char[] symbols) {
        // 先把每格解析为方块名(不同调色板索引可能映射到同一方块,按方块名合并游程)
        String[][] names = new String[grid.length][];
        for (int y = 0; y < grid.length; y++) {
            names[y] = new String[grid[y].length];
            for (int x = 0; x < grid[y].length; x++) {
                names[y][x] = blockNameFor(symbols, palette, grid[y][x]);
            }
        }
        StringBuilder sb = new StringBuilder("施工单(每行从左到右的游程;建议逐行 /fill 或分段放置):\n");
        for (int y = 0; y < names.length; y++) {
            sb.append(String.format(Locale.ROOT, "第%02d行: ", y + 1));
            String prev = null;
            int run = 0;
            boolean first = true;
            for (int x = 0; x < names[y].length; x++) {
                String n = names[y][x];
                if (prev == null) {
                    prev = n;
                    run = 1;
                } else if (n.equals(prev)) {
                    run++;
                } else {
                    sb.append(first ? "" : ", ").append(prev);
                    if (run > 1) sb.append('×').append(run);
                    first = false;
                    prev = n;
                    run = 1;
                }
            }
            if (prev != null) {
                sb.append(first ? "" : ", ").append(prev);
                if (run > 1) sb.append('×').append(run);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String blockNameFor(char[] symbols, List<int[]> palette, char sym) {
        for (int i = 0; i < symbols.length; i++) {
            if (symbols[i] == sym && i < palette.size()) {
                int[] c = palette.get(i);
                // 透明(空格符)→ 空气
                return sym == ' ' ? "air" : nearestBlock(c[0], c[1], c[2]);
            }
        }
        return "air";
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
