package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redi.agent.AgentTool;
import com.redi.agent.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * read_guidebook:结构化解析模组内置的帕秋莉手册(Patchouli 手册——大量模组把
 * “玩法/进度/物品图鉴/怪物图鉴”写进这本书,而非代码)。
 * 数据全部来自本地模组包文件(磁盘上的 jar),纯文件 IO,不切主线程、不联网。
 *
 * <p>四种用法(按参数自动分流):不带 book 列手册;带 book 列分类;
 * 带 book+query 全文搜索条目;带 book+entry 读单条目全文;带 book+category 列分类下条目。
 * 名称/正文字符串若本身是语言键(模组 i18n 手册),用 jar 内 zh_cn/en_us 语言文件静态解析,
 * 中文翻译存在则直接给中文。</p>
 */
public final class GuidebookTool implements AgentTool {

    /** (modid|book) → 已解析条目缓存,避免每次搜索重读上千个 zip 条目。 */
    private static final Map<String, List<Entry>> CACHE = new HashMap<>();
    /** 同一手册的搜索重扫成本高且不会变(jar 运行期只读),缓存上限给足。 */
    private static final int CACHE_MAX = 8;

    private static final class Entry {
        String path;     // entries/ 下相对路径(去 .json),如 crafts/arca_compass
        String category; // 完整 category id,如 goety:crafts
        String name;     // 解析语言键后的名称
        String text;     // 拼接的全部页面文本(搜索用)
    }

    @Override
    public String name() {
        return "read_guidebook";
    }

    @Override
    public String description() {
        return "读取模组内置的帕秋莉手册(Patchouli guide book,很多模组的玩法百科:进度流程/仪式/物品图鉴/怪物图鉴)。"
                + "被问“某模组怎么玩/怎么入门/某机制怎么做”时,在 mod_overview 之后用它读手册相关条目,这是最权威的玩法来源。"
                + "参数:modid 必填;book 可选(手册 id,不填=列出该模组全部手册);"
                + "category 可选(分类 id,列出该分类下条目);entry 可选(条目路径,读单条目全文,如 crafts/arca_compass);"
                + "query 可选(关键词全文搜索,中英文都可,命中条目名优先);limit 可选(搜索返回条数,默认 8)。"
                + "手册原文多为英文,返回后你需翻译成中文再答玩家。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject p = new JsonObject();
        p.add("modid", strProp("模组 id,如 goety"));
        p.add("book", strProp("手册 id,不填则列出全部手册"));
        p.add("category", strProp("分类 id(如 goety:crafts),列出该分类条目"));
        p.add("entry", strProp("条目路径(如 crafts/arca_compass),读单条目全文"));
        p.add("query", strProp("关键词,全文搜索条目"));
        p.add("limit", strProp("搜索返回条数,默认 8,最大 15"));
        schema.add("properties", p);
        JsonArray required = new JsonArray();
        required.add("modid");
        schema.add("required", required);
        return schema;
    }

    private static JsonObject strProp(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    // ------------------------------------------------------------------

    @Override
    public String execute(JsonObject args) {
        String modid = ToolRegistry.argStr(args, "modid");
        if (modid == null || modid.isBlank()) {
            return "参数错误:modid 必填(先用 list_mods 查看全部模组 id)。";
        }
        if (ToolRegistry.modFilePath(modid) == null) {
            return "没有找到已加载的模组:" + modid;
        }
        try {
            List<String[]> books = findBooks(modid); // {bookId, bookJsonPath}
            if (books.isEmpty()) {
                return "模组 " + modid + " 没有内置帕秋莉手册。";
            }
            String book = ToolRegistry.argStr(args, "book");
            if (book == null || book.isBlank()) {
                return listBooks(modid, books);
            }
            String bookJson = null;
            for (String[] b : books) {
                if (b[0].equalsIgnoreCase(book)) {
                    bookJson = b[1];
                    break;
                }
            }
            if (bookJson == null) {
                StringBuilder sb = new StringBuilder("手册 " + book + " 不存在。可用手册:");
                for (String[] b : books) sb.append(' ').append(b[0]);
                return sb.toString();
            }
            // book.json 可能在 assets/ 或 data/ 下,而条目/分类在另一半;
            // 统一用 "patchouli_books/<book>/" 做跨根前缀
            String prefix = bookJson.substring(bookJson.indexOf("patchouli_books/"));
            Map<String, String> lang = loadLang(modid, prefix);
            List<Entry> entries = entries(modid, book, prefix, lang);

            String query = ToolRegistry.argStr(args, "query");
            String entry = ToolRegistry.argStr(args, "entry");
            String category = ToolRegistry.argStr(args, "category");
            if (entry != null && !entry.isBlank()) {
                return readEntry(entries, entry, modid, lang);
            }
            if (query != null && !query.isBlank()) {
                return search(entries, query, intArg(args, "limit", 8, 15));
            }
            if (category != null && !category.isBlank()) {
                return listCategory(entries, category);
            }
            return listCategories(modid, book, prefix, lang, entries);
        } catch (Exception e) {
            return "工具执行出错: " + e;
        }
    }

    private static int intArg(JsonObject args, String key, int def, int max) {
        try {
            if (args != null && args.has(key) && args.get(key).isJsonPrimitive()) {
                return Math.max(1, Math.min(max, args.get(key).getAsInt()));
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    /** 找模组包里全部帕秋莉手册 {bookId, bookJson内部路径}(assets/ 与 data/ 都扫)。 */
    private static List<String[]> findBooks(String modid) {
        List<String[]> out = new ArrayList<>();
        for (String n : ToolRegistry.listModEntries(modid)) {
            int i = n.indexOf("patchouli_books/");
            if (i < 0 || !n.endsWith("book.json")) continue;
            String rest = n.substring(i + "patchouli_books/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) continue;
            String id = rest.substring(0, slash);
            if (!rest.substring(slash + 1).equals("book.json")) continue;
            out.add(new String[]{id, n});
        }
        return out;
    }

    /** 手册书名/导语 + 分类名都可能写成语言键;合并 jar 内 zh_cn(优先)与 en_us 语言表做静态解析。 */
    private static Map<String, String> loadLang(String modid, String prefix) {
        Map<String, String> m = new HashMap<>();
        for (String lc : new String[]{"en_us", "zh_cn"}) { // zh_cn 后放,同键覆盖 = 中文优先
            for (String base : new String[]{"assets/" + modid + "/lang/", "data/" + modid + "/lang/"}) {
                try {
                    byte[] b = ToolRegistry.readModBytes(modid, base + lc + ".json");
                    if (b == null) continue;
                    JsonObject o = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                        if (e.getValue().isJsonPrimitive()) {
                            m.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return m;
    }

    /** 语言键解析:长得像键且在语言表里 → 翻译;否则原样返回。 */
    private static String tr(String s, Map<String, String> lang) {
        if (s == null) return "";
        String t = lang.get(s);
        return t != null ? t : s;
    }

    /** 解析并缓存整本手册的全部条目。 */
    private static List<Entry> entries(String modid, String book, String prefix, Map<String, String> lang) {
        String key = modid + "|" + book;
        synchronized (CACHE) {
            List<Entry> hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        List<Entry> out = new ArrayList<>();
        String entriesDir = null;
        for (String n : ToolRegistry.listModEntries(modid)) {
            if (!n.startsWith(prefix) || !n.contains("/entries/") || !n.endsWith(".json")) continue;
            if (entriesDir == null) {
                int i = n.indexOf("/entries/");
                entriesDir = n.substring(0, i + "/entries/".length());
            }
        }
        if (entriesDir != null) {
            for (String n : ToolRegistry.listModEntries(modid)) {
                if (!n.startsWith(entriesDir) || !n.endsWith(".json")) continue;
                try {
                    byte[] b = ToolRegistry.readModBytes(modid, n);
                    if (b == null) continue;
                    JsonObject o = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
                    Entry e = new Entry();
                    e.path = n.substring(entriesDir.length(), n.length() - ".json".length());
                    e.category = o.has("category") && o.get("category").isJsonPrimitive()
                            ? o.get("category").getAsString() : "";
                    e.name = tr(o.has("name") && o.get("name").isJsonPrimitive()
                            ? o.get("name").getAsString() : e.path, lang);
                    StringBuilder tx = new StringBuilder();
                    if (o.has("pages") && o.get("pages").isJsonArray()) {
                        for (var pg : o.getAsJsonArray("pages")) {
                            if (!pg.isJsonObject()) continue;
                            JsonObject p = pg.getAsJsonObject();
                            String type = p.has("type") ? p.get("type").getAsString() : "";
                            if (p.has("text") && p.get("text").isJsonPrimitive()) {
                                tx.append(tr(p.get("text").getAsString(), lang)).append('\n');
                            }
                            if (p.has("recipe") && p.get("recipe").isJsonPrimitive()) {
                                tx.append("[配方: ").append(p.get("recipe").getAsString()).append("]\n");
                            }
                            if (p.has("item") && p.get("item").isJsonPrimitive()) {
                                tx.append("[物品: ").append(p.get("item").getAsString()).append("]\n");
                            }
                            if (p.has("entity") && p.get("entity").isJsonPrimitive()) {
                                tx.append("[实体: ").append(p.get("entity").getAsString()).append("]\n");
                            }
                            if (!type.isBlank() && (tx.length() == 0 || type.startsWith("patchouli:crafting")
                                    || type.startsWith("patchouli:smelting"))) {
                                tx.append("[页面类型: ").append(type).append("]\n");
                            }
                        }
                    }
                    e.text = tx.toString();
                    out.add(e);
                } catch (Exception ignored) {
                }
            }
        }
        synchronized (CACHE) {
            if (CACHE.size() >= CACHE_MAX) {
                CACHE.clear(); // 简单防胀:超过上限整表清空(手册内容运行期不变,重建即可)
            }
            CACHE.put(key, out);
        }
        return out;
    }

    private static String listBooks(String modid, List<String[]> books) {
        StringBuilder sb = new StringBuilder("模组 " + modid + " 内置帕秋莉手册:\n");
        for (String[] b : books) {
            String prefix = b[1].substring(b[1].indexOf("patchouli_books/"));
            Map<String, String> lang = loadLang(modid, prefix);
            String title = "", landing = "";
            try {
                byte[] bj = ToolRegistry.readModBytes(modid, b[1]);
                if (bj != null) {
                    JsonObject o = JsonParser.parseString(new String(bj, StandardCharsets.UTF_8)).getAsJsonObject();
                    title = tr(o.has("name") ? o.get("name").getAsString() : "", lang);
                    landing = tr(o.has("landing_text") ? o.get("landing_text").getAsString() : "", lang);
                }
            } catch (Exception ignored) {
            }
            sb.append("- ").append(b[0]).append(title.isEmpty() ? "" : "(" + title + ")").append('\n');
            if (!landing.isEmpty()) {
                sb.append("  ").append(ToolRegistry.trunc(landing, 120)).append('\n');
            }
        }
        sb.append("用 book 参数指定手册继续:可再带 query 搜索 / category 列条目 / entry 读全文。");
        return ToolRegistry.trunc(sb.toString(), 2000);
    }

    private static String listCategories(String modid, String book, String prefix,
                                         Map<String, String> lang, List<Entry> entries) {
        // 分类树:parent 归属 + 每类条目数
        Map<String, String[]> cats = new HashMap<>(); // id → {name, parent}
        for (String n : ToolRegistry.listModEntries(modid)) {
            if (!n.startsWith(prefix) || !n.contains("/categories/") || !n.endsWith(".json")) continue;
            try {
                byte[] b = ToolRegistry.readModBytes(modid, n);
                if (b == null) continue;
                JsonObject o = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
                String id = modid + ":" + n.substring(n.indexOf("/categories/") + "/categories/".length(),
                        n.length() - ".json".length()); // 分类 id 保留斜杠:goety:crafts/artifice
                String name = tr(o.has("name") ? o.get("name").getAsString() : id, lang);
                String parent = o.has("parent") ? o.get("parent").getAsString() : "";
                cats.put(id, new String[]{name, parent});
            } catch (Exception ignored) {
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Entry e : entries) {
            counts.merge(e.category, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("手册 ").append(book).append(" 共 ")
                .append(entries.size()).append(" 个条目。分类:\n");
        List<String> ids = new ArrayList<>(cats.keySet());
        ids.sort(String::compareTo);
        for (String id : ids) {
            String[] c = cats.get(id);
            int depth = 0;
            String p = c[1];
            while (p != null && !p.isEmpty() && cats.containsKey(p) && depth < 4) {
                p = cats.get(p)[1];
                depth++;
            }
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append("- ").append(c[0]).append('(').append(counts.getOrDefault(id, 0)).append(" 条) ")
                    .append(id).append('\n');
        }
        sb.append("用 category=<分类id> 列条目,entry=<路径> 读全文,query=<词> 全文搜索。");
        return ToolRegistry.trunc(sb.toString(), 5000);
    }

    private static String listCategory(List<Entry> entries, String category) {
        String want = category.contains(":") ? category
                : entries.isEmpty() ? category
                : entries.get(0).category.substring(0, entries.get(0).category.indexOf(':') + 1) + category;
        StringBuilder sb = new StringBuilder("分类 " + want + " 的条目:\n");
        int n = 0;
        for (Entry e : entries) {
            if (!e.category.equals(want)) continue;
            sb.append("- ").append(e.name).append(" [").append(e.path).append("]\n");
            if (++n > 60) {
                sb.append("(只列前 60 条,可加 query 搜索缩小范围)\n");
                break;
            }
        }
        if (n == 0) return "分类 " + want + " 下没有条目。";
        return ToolRegistry.trunc(sb.toString(), 5000);
    }

    private static String search(List<Entry> entries, String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT);
        String[] terms = q.split("\\s+");
        List<Entry> named = new ArrayList<>();
        List<Entry> texted = new ArrayList<>();
        for (Entry e : entries) {
            String name = e.name.toLowerCase(Locale.ROOT);
            String text = e.text == null ? "" : e.text.toLowerCase(Locale.ROOT);
            boolean inName = true;
            boolean inText = true;
            for (String t : terms) {
                if (!name.contains(t)) inName = false;
                if (!text.contains(t)) inText = false;
                if (!inName && !inText) break;
            }
            if (inName) named.add(e);
            else if (inText) texted.add(e);
        }
        if (named.isEmpty() && texted.isEmpty()) {
            return "手册中没有条目命中 \"" + query + "\"。可换同义词/英文名再试。";
        }
        StringBuilder sb = new StringBuilder("手册搜索 \"" + query + "\" 命中 ")
                .append(named.size() + texted.size()).append(" 条:\n");
        int shown = 0;
        for (Entry e : named) {
            sb.append("- ").append(e.name).append(" [").append(e.path).append("]\n");
            if (++shown >= limit) break;
        }
        for (Entry e : texted) {
            if (shown >= limit) break;
            String snippet = e.text == null ? "" : e.text.replaceAll("\n+", " ");
            int i = snippet.toLowerCase(Locale.ROOT).indexOf(terms[0]);
            String ctx = i >= 0 ? snippet.substring(Math.max(0, i - 30), Math.min(snippet.length(), i + 90)) : "";
            sb.append("- ").append(e.name).append(" [").append(e.path).append("] …")
                    .append(ctx).append("…\n");
            shown++;
        }
        sb.append("用 entry=<方括号内路径> 读全文。");
        return ToolRegistry.trunc(sb.toString(), 5000);
    }

    private static String readEntry(List<Entry> entries, String entry, String modid, Map<String, String> lang) {
        String want = entry.toLowerCase(Locale.ROOT);
        Entry hit = null;
        for (Entry e : entries) {
            String p = e.path.toLowerCase(Locale.ROOT);
            if (p.equals(want) || p.equals(want + ".json")
                    || p.endsWith("/" + want) || p.endsWith("/" + want + ".json")) {
                hit = e;
                break;
            }
        }
        if (hit == null) {
            for (Entry e : entries) {
                if (e.name.equalsIgnoreCase(entry)) {
                    hit = e;
                    break;
                }
            }
        }
        if (hit == null) {
            return "手册里没有条目 \"" + entry + "\"。用 query 搜索确认准确路径。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("条目: ").append(hit.name).append('\n');
        sb.append("路径: ").append(hit.path).append("  分类: ").append(hit.category).append('\n');
        sb.append(hit.text == null || hit.text.isBlank() ? "(该条目没有文本页)" : hit.text.trim());
        return ToolRegistry.trunc(sb.toString(), 6000);
    }
}
