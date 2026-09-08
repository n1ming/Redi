package com.redi.kb;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 内置中文知识库:加载 assets/redi/kb/ 下 3 个 MD 文件,按 "## 小节" 分节并检索。
 *
 * <p>加载:单例 + 懒加载,首次检索时经 getResourceAsStream 读为 UTF-8 文本
 * (开发环境 resources 在文件系统、打包后在 jar 里,该方式两者皆可)。
 * 任何文件加载失败都只是“该文件缺席”:整体标记,检索时返回友好错误,绝不抛异常。</p>
 *
 * <p>检索打分:query 按空格拆词(整句同时作为一个整体词,ASCII 转小写、CJK 原样),
 * 每个词对小节做大小写不敏感的 contains:节标题命中 +3、正文命中 +1,按总分降序取前 limit 节。
 * 加载完成后所有数据只读,天然线程安全。</p>
 */
public final class KnowledgeBase {

    private static final KnowledgeBase INSTANCE = new KnowledgeBase();

    public static KnowledgeBase get() {
        return INSTANCE;
    }

    private KnowledgeBase() {
    }

    /** 知识库文件清单:classpath 资源路径 + 显示名(硬编码,只读)。 */
    private static final String[][] KB_FILES = {
            {"/assets/redi/kb/commands.md", "指令速查"},
            {"/assets/redi/kb/selectors.md", "选择器与坐标"},
            {"/assets/redi/kb/items_basics.md", "物品与组件"},
    };

    /** 每节正文的最大输出长度,超出截断。 */
    private static final int MAX_BODY_CHARS = 1200;

    /** 一个知识库小节(加载后不可变)。 */
    private static final class Section {
        final String fileName;  // 文件基础名,如 commands(供 file 过滤)
        final String displayName; // 显示名,如 指令速查
        final String title;     // 节标题("## " 后的文本)
        final String body;      // 节正文

        Section(String fileName, String displayName, String title, String body) {
            this.fileName = fileName;
            this.displayName = displayName;
            this.title = title;
            this.body = body;
        }
    }

    /** 全部小节;null = 尚未加载。加载后只读。 */
    private volatile List<Section> sections;

    /** 加载是否失败过(影响检索返回的错误文案)。 */
    private volatile boolean loadFailed = false;

    /** 懒加载:只执行一次;失败时 sections 置为空列表并标记 loadFailed。 */
    private void ensureLoaded() {
        List<Section> s = sections;
        if (s != null) return;
        synchronized (this) {
            if (sections != null) return;
            List<Section> out = new ArrayList<>();
            boolean anyFailed = false;
            for (String[] f : KB_FILES) {
                try (InputStream in = KnowledgeBase.class.getResourceAsStream(f[0])) {
                    if (in == null) {
                        anyFailed = true;
                        continue;
                    }
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    out.addAll(parseSections(text, baseName(f[0]), f[1]));
                } catch (Exception e) {
                    anyFailed = true;
                }
            }
            this.sections = List.copyOf(out);
            this.loadFailed = anyFailed;
        }
    }

    /**
     * 把一个 MD 文件文本按 "\n## " 分节。第 0 段是文件导语(标题行以 "#" 开头),
     * 其后每段首行即节标题,其余为正文。
     */
    private static List<Section> parseSections(String text, String fileName, String displayName) {
        List<Section> out = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n");
        String[] parts = normalized.split("\n## ");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].strip();
            if (part.isEmpty()) continue;
            int nl = part.indexOf('\n');
            String titleLine = nl < 0 ? part : part.substring(0, nl);
            String body = nl < 0 ? "" : part.substring(nl + 1).strip();
            String title;
            if (i == 0) {
                // 文件导语:首行是 "# 一级标题",去掉井号作为节标题
                title = titleLine.replaceFirst("^#+\\s*", "").strip();
            } else {
                title = titleLine.strip();
            }
            if (title.isEmpty() && body.isEmpty()) continue;
            out.add(new Section(fileName, displayName, title, body));
        }
        return out;
    }

    /** "/assets/redi/kb/commands.md" → "commands"。 */
    private static String baseName(String resource) {
        String p = resource;
        int slash = p.lastIndexOf('/');
        if (slash >= 0) p = p.substring(slash + 1);
        if (p.toLowerCase(Locale.ROOT).endsWith(".md")) p = p.substring(0, p.length() - 3);
        return p;
    }

    /**
     * 检索知识库。不抛异常;失败/无命中都返回友好文本。
     *
     * @param query      检索词,可多个(空格分隔);空则返回提示
     * @param fileFilter 可选文件过滤:支持基础名(commands/selectors/items_basics)、
     *                   带 .md 的文件名、显示名,均按前缀匹配;null/空白 = 全库
     * @param limit      返回节数上限;<=0 用默认 4,>8 收到 8
     */
    public String search(String query, String fileFilter, int limit) {
        try {
            ensureLoaded();
            List<Section> all = sections;
            if (all == null || all.isEmpty()) {
                return loadFailed
                        ? "(知识库加载失败:内置知识库资源缺失或不可读。)"
                        : "(知识库为空。)";
            }
            if (query == null || query.isBlank()) {
                return "(缺少检索词 query,请给出要查的关键词,如 “execute”、“选择器”、“食物”)。";
            }
            String filter = fileFilter == null ? "" : fileFilter.strip().toLowerCase(Locale.ROOT);
            if (filter.endsWith(".md")) filter = filter.substring(0, filter.length() - 3);
            boolean filtered = !filter.isEmpty();

            List<String> tokens = tokenize(query);
            int n = limit <= 0 ? 4 : Math.min(limit, 8);

            // 过滤 + 打分:标题命中 ×3、正文命中 ×1(大小写不敏感 contains)
            List<Section> pool = new ArrayList<>();
            for (Section s : all) {
                if (filtered && !matchesFile(s, filter)) continue;
                pool.add(s);
            }
            if (filtered && pool.isEmpty()) {
                return "(知识库中没有名为 “" + fileFilter.strip() + "” 的文件;可用:commands、selectors、items_basics。)";
            }

            List<Section> hits = new ArrayList<>();
            for (Section s : pool) {
                if (score(s, tokens) > 0) hits.add(s);
            }
            if (hits.isEmpty()) return "(知识库中没有匹配内容)";
            hits.sort((a, b) -> Integer.compare(score(b, tokens), score(a, tokens)));

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(n, hits.size()); i++) {
                Section s = hits.get(i);
                if (i > 0) sb.append("\n\n");
                sb.append("【").append(s.displayName).append(" · ").append(s.title).append("】\n");
                sb.append(s.body.length() > MAX_BODY_CHARS
                        ? s.body.substring(0, MAX_BODY_CHARS) + "(已截断)"
                        : s.body);
            }
            return sb.toString();
        } catch (Exception e) {
            return "(知识库检索出错: " + e + ")";
        }
    }

    /**
     * 分词:query 按空白拆 + 整句也作为一个整体词;
     * ASCII 转小写(CJK 不受 toLowerCase 影响,原样保留),去重保序。
     */
    private static List<String> tokenize(String query) {
        Set<String> out = new LinkedHashSet<>();
        String whole = query.strip().toLowerCase(Locale.ROOT);
        if (!whole.isEmpty()) out.add(whole);
        for (String w : whole.split("\\s+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return new ArrayList<>(out);
    }

    /** 小节打分:每个词标题命中 +3、正文命中 +1。 */
    private static int score(Section s, List<String> tokens) {
        String title = s.title.toLowerCase(Locale.ROOT);
        String body = s.body.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String t : tokens) {
            if (title.contains(t)) score += 3;
            if (body.contains(t)) score += 1;
        }
        return score;
    }

    /** 文件过滤:基础名或显示名(转小写)以 filter 为前缀即命中。 */
    private static boolean matchesFile(Section s, String filter) {
        return s.fileName.toLowerCase(Locale.ROOT).startsWith(filter)
                || s.displayName.toLowerCase(Locale.ROOT).startsWith(filter);
    }
}
