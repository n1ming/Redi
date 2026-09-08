package com.redi.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.redi.RediMod;
import com.redi.llm.LlmMessage;
import com.redi.llm.ToolCall;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话历史持久化:每个会话存为 config/redi/history/session_yyyyMMdd_HHmmss.json
 * (与 {@link com.redi.config.AgentConfig} 同一套相对工作目录定位)。落盘格式:
 * <pre>{ "title": "…", "savedAt": 1757136000000,
 *        "messages": [ {"role":"USER","text":"…","ts":1757135999000}, … ] }</pre>
 * role 取 {@link ChatModel.Role} 的 name(),读取时把 USER/ASSISTANT/ERROR/NOTE 全部还原。
 *
 * <p>线程安全:所有公开方法 static synchronized(saveCurrent 可能在关屏时由渲染线程调用,
 * list 由历史页渲染线程调用);文件 IO 全部吞异常,失败只记日志、绝不向上抛。</p>
 *
 * <p>标题 = 快照里第一条 USER 消息前 20 字符(没有 USER 消息则「空会话」)。
 * {@link #revision()} 供视图比对决定是否重载列表(save/delete 成功都会推进)。</p>
 */
public final class ChatStore {

    /** 列表行元数据:fileName 为纯文件名,供 {@link #loadInto(String)} / {@link #delete(String)} 使用。 */
    public record SessionMeta(String fileName, String title, long savedAt, int messageCount) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path DIR = Path.of("config", "redi", "history");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String EXT = ".json";
    /** 标题兜底:快照里没有 USER 消息时用。 */
    private static final String EMPTY_TITLE = "空会话";

    // ---- GSON 落盘模型(字段名即 JSON 键) ----
    private static final class Stored {
        String title;
        long savedAt;
        List<StoredMsg> messages;
        /** 结构化 LLM 上下文(按轮组织);旧格式存档没有此字段(载入时不回灌上下文)。 */
        List<List<StoredLlm>> rounds;
    }

    private static final class StoredMsg {
        String role;
        String text;
        long ts;
    }

    /** LlmMessage 的落盘形态。 */
    private static final class StoredLlm {
        String role;
        String content;
        String toolCallId;
        List<StoredCall> toolCalls;
    }

    /** ToolCall 的落盘形态。 */
    private static final class StoredCall {
        String id;
        String name;
        String arguments;
    }

    /** 列表内容版本号:save/delete 成功后递增;视图比对它实现自动刷新。 */
    private static volatile long revision = 0L;

    /**
     * 当前会话正在写的文件(本轮游戏内复用,原地更新,不再每次关屏都堆一个新文件)。
     * 新对话 → 置空(下次保存开新文件);载入某会话 → 指向该文件(继续写回原会话)。
     */
    private static Path currentFile;

    private ChatStore() {
    }

    /** 当前列表版本号(视图据此决定要不要重载列表;恒单调递增)。 */
    public static long revision() {
        return revision;
    }

    /**
     * 把 ChatModel 当前快照存为新会话文件;内容为空则不落盘。
     * 同一秒多次保存时文件名秒数顺延,绝不覆盖已有会话。
     */
    public static synchronized void saveCurrent() {
        try {
            List<ChatModel.Msg> snap = ChatModel.get().snapshot();
            if (snap.isEmpty()) {
                return;
            }
            Stored s = new Stored();
            s.title = deriveTitle(snap);
            s.savedAt = System.currentTimeMillis();
            s.messages = new ArrayList<>(snap.size());
            for (ChatModel.Msg m : snap) {
                StoredMsg sm = new StoredMsg();
                sm.role = (m.role() == null ? ChatModel.Role.NOTE : m.role()).name();
                sm.text = m.text() == null ? "" : m.text();
                sm.ts = m.ts();
                s.messages.add(sm);
            }
            s.rounds = storeRounds(AgentEngine.get().snapshotRounds());
            Files.createDirectories(DIR);
            // 本轮游戏内原地更新同一个文件(频繁保存:每轮问答结束/关屏/退游戏都会触发)
            if (currentFile == null || !Files.isRegularFile(currentFile)) {
                currentFile = uniqueFile();
            }
            Files.writeString(currentFile, GSON.toJson(s), StandardCharsets.UTF_8);
            revision++;
        } catch (Exception e) {
            RediMod.LOGGER.warn("[redi] 保存历史会话失败: {}", e.toString());
        }
    }

    /**
     * 列出全部会话,按保存时间倒序;目录不存在/文件损坏时友好回退(损坏文件退化为
     * 标题「(无法读取)」的可删除占位行),绝不抛异常。
     */
    public static synchronized List<SessionMeta> list() {
        List<SessionMeta> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(DIR)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(EXT))
                    .forEach(p -> out.add(metaOf(p)));
        } catch (Exception e) {
            // 目录还不存在等情况:返回空列表即可
        }
        out.sort(Comparator.comparingLong(SessionMeta::savedAt).reversed());
        return out;
    }

    /**
     * 载入最近一次保存的会话({@link #list()} 按保存时间倒序,取第一个):
     * 游戏启动后首次打开助手时用,恢复“玩家退出时的会话”(聊天记录+模型上下文)。
     * 没有任何会话文件时不做任何事,返回 false。
     */
    public static synchronized boolean loadLatest() {
        List<SessionMeta> all = list();
        if (all.isEmpty()) {
            return false;
        }
        // 静默回灌(不加“已载入”提示):启动自动恢复,避免提示随每次启动堆积
        loadIntoInternal(all.get(0).fileName(), true);
        return true;
    }

    /** 开启新对话:断开与当前会话文件的绑定(下次保存开新文件)。 */
    public static synchronized void markNewSession() {
        currentFile = null;
    }

    /**
     * 读指定会话文件并整体替换当前显示记录(ChatModel.replaceAll;
     * 只替换显示记录,不触碰 AgentEngine 的对话上下文)。防路径穿越:只接受纯文件名。
     */
    public static synchronized void loadInto(String fileName) {
        loadIntoInternal(fileName, false);
    }

    /** loadInto 的实现:quiet=true 时不追加“已载入”提示(启动自动恢复用)。 */
    private static synchronized void loadIntoInternal(String fileName, boolean quiet) {        try {
            Path p = safeResolve(fileName);
            if (p == null) {
                return;
            }
            Stored s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Stored.class);
            if (s == null || s.messages == null) {
                return;
            }
            List<ChatModel.Msg> msgs = new ArrayList<>(s.messages.size());
            for (StoredMsg sm : s.messages) {
                if (sm == null) {
                    continue;
                }
                msgs.add(new ChatModel.Msg(parseRole(sm.role), sm.text == null ? "" : sm.text, sm.ts));
            }
            ChatModel.get().replaceAll(msgs);
            // 回灌结构化上下文:切到历史会话后模型“记得”这段对话,说“继续”能接上。
            // 任务进行中不回灌(避免污染正在跑的任务的上下文)
            if (!ChatModel.get().busy()) {
                AgentEngine.get().restoreRounds(loadRounds(s.rounds), quiet);
            }
            // 之后继续写回该会话文件(原地更新)
            currentFile = p;
        } catch (Exception e) {
            RediMod.LOGGER.warn("[redi] 读取历史会话失败: {}", e.toString());
        }
    }

    /** 删除会话文件(只删文件,不动当前显示记录);非法文件名或删除失败静默忽略。 */
    public static synchronized void delete(String fileName) {
        try {
            Path p = safeResolve(fileName);
            if (p != null && Files.deleteIfExists(p)) {
                if (p.equals(currentFile)) {
                    currentFile = null; // 删掉的正是当前绑定文件:下次保存开新文件
                }
                revision++;
            }
        } catch (Exception e) {
            RediMod.LOGGER.warn("[redi] 删除历史会话失败: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- 内部实现

    /** 引擎 rounds → 落盘形态(role/content/toolCalls 全量保存)。 */
    private static List<List<StoredLlm>> storeRounds(List<List<LlmMessage>> rounds) {
        List<List<StoredLlm>> out = new ArrayList<>(rounds.size());
        for (List<LlmMessage> r : rounds) {
            List<StoredLlm> sr = new ArrayList<>(r.size());
            for (LlmMessage m : r) {
                StoredLlm sm = new StoredLlm();
                sm.role = m.role();
                sm.content = m.content();
                sm.toolCallId = m.toolCallId();
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    sm.toolCalls = new ArrayList<>(m.toolCalls().size());
                    for (ToolCall tc : m.toolCalls()) {
                        StoredCall sc = new StoredCall();
                        sc.id = tc.id();
                        sc.name = tc.name();
                        sc.arguments = tc.argumentsJson();
                        sm.toolCalls.add(sc);
                    }
                }
                sr.add(sm);
            }
            out.add(sr);
        }
        return out;
    }

    /** 落盘形态 → 引擎 rounds;畸形条目跳过,空存档返回 null(载入时不动当前上下文)。 */
    private static List<List<LlmMessage>> loadRounds(List<List<StoredLlm>> stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        List<List<LlmMessage>> out = new ArrayList<>(stored.size());
        for (List<StoredLlm> sr : stored) {
            if (sr == null || sr.isEmpty()) {
                continue;
            }
            List<LlmMessage> r = new ArrayList<>(sr.size());
            for (StoredLlm sm : sr) {
                if (sm == null || sm.role == null) {
                    continue;
                }
                List<ToolCall> calls = null;
                if (sm.toolCalls != null && !sm.toolCalls.isEmpty()) {
                    calls = new ArrayList<>(sm.toolCalls.size());
                    for (StoredCall sc : sm.toolCalls) {
                        if (sc != null && sc.name != null && !sc.name.isBlank()) {
                            calls.add(new ToolCall(sc.id == null ? "" : sc.id, sc.name,
                                    sc.arguments == null || sc.arguments.isBlank() ? "{}" : sc.arguments));
                        }
                    }
                    if (calls.isEmpty()) {
                        calls = null;
                    }
                }
                r.add(new LlmMessage(sm.role, sm.content == null ? "" : sm.content, calls, sm.toolCallId));
            }
            if (!r.isEmpty()) {
                out.add(r);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 标题 = 第一条 USER 消息(trim 后)前 20 字符;没有 USER 消息则「空会话」。 */
    private static String deriveTitle(List<ChatModel.Msg> snap) {
        for (ChatModel.Msg m : snap) {
            if (m.role() == ChatModel.Role.USER) {
                String text = m.text() == null ? "" : m.text().trim();
                if (text.isEmpty()) {
                    return EMPTY_TITLE;
                }
                return text.length() <= 20 ? text : text.substring(0, 20);
            }
        }
        return EMPTY_TITLE;
    }

    /** 生成未占用的 session_yyyyMMdd_HHmmss.json:同一秒重复保存时秒数顺延(最多 2 分钟),极端情况加纳秒尾。 */
    private static Path uniqueFile() {
        LocalDateTime t = LocalDateTime.now();
        for (int i = 0; i < 120; i++) {
            Path p = DIR.resolve("session_" + STAMP.format(t.plusSeconds(i)) + EXT);
            if (!Files.exists(p)) {
                return p;
            }
        }
        return DIR.resolve("session_" + STAMP.format(LocalDateTime.now()) + "_" + System.nanoTime() + EXT);
    }

    /** 单个文件 → 元数据;解析失败时退化为可删除的占位行(时间取文件修改时间)。 */
    private static SessionMeta metaOf(Path p) {
        String fileName = p.getFileName().toString();
        try {
            Stored s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Stored.class);
            if (s == null) {
                throw new IllegalStateException("empty json");
            }
            int count = s.messages == null ? 0 : s.messages.size();
            long savedAt = s.savedAt > 0 ? s.savedAt : Files.getLastModifiedTime(p).toMillis();
            String title = s.title == null || s.title.isBlank() ? EMPTY_TITLE : s.title;
            return new SessionMeta(fileName, title, savedAt, count);
        } catch (Exception e) {
            long fallback = 0L;
            try {
                fallback = Files.getLastModifiedTime(p).toMillis();
            } catch (IOException io) {
                // 取不到时间就 0,排最后,不影响删除
            }
            return new SessionMeta(fileName, "(无法读取)", fallback, 0);
        }
    }

    /** 宽容解析:未知/缺失角色按 NOTE 还原(不丢消息)。 */
    private static ChatModel.Role parseRole(String raw) {
        if (raw != null) {
            for (ChatModel.Role r : ChatModel.Role.values()) {
                if (r.name().equalsIgnoreCase(raw)) {
                    return r;
                }
            }
        }
        return ChatModel.Role.NOTE;
    }

    /** 防路径穿越:只接受本目录下的纯 .json 文件名(无路径分隔符/盘符/..),非法返回 null。 */
    private static Path safeResolve(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.trim();
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || name.equals(".") || name.equals("..")
                || !name.endsWith(EXT)) {
            return null;
        }
        Path p = DIR.resolve(name).normalize();
        return p.getParent() != null && p.getParent().equals(DIR) ? p : null;
    }
}
