package com.redi.tools;

import com.redi.config.AgentConfig;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * web_search / web_read 共用的只读 HTTP 抓取与 HTML→文本辅助。
 * 只发 GET,从不携带本地数据;代理取设置页 proxyHost/proxyPort,
 * 未配置时直连,直连失败再试本机 127.0.0.1:7897(玩家环境常见的本地代理端口)。
 */
final class WebFetch {

    private WebFetch() {
    }

    static final class Hit {
        final String title;
        final String url;
        final String snippet;

        Hit(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }

    private static HttpClient client(boolean viaProxy) {
        HttpClient.Builder cb = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (viaProxy) {
            cb.proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", 7897)));
        }
        return cb.build();
    }

    /** 依次尝试:设置里的代理(若配)→ 直连 → 127.0.0.1:7897;全失败抛最后一个异常。 */
    static String get(String url, int timeoutSeconds) throws Exception {
        AgentConfig cfg = AgentConfig.get();
        Exception last = null;
        boolean[] attempts = new boolean[2];
        attempts[0] = false;
        attempts[1] = true;
        if (cfg.proxyHost != null && !cfg.proxyHost.isBlank() && cfg.proxyPort > 0) {
            attempts = new boolean[]{true, false, true}; // 配了代理:先配置代理再 7897(直连多半不通)
        }
        for (boolean viaProxy : attempts) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
                        .header("User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .GET()
                        .build();
                HttpResponse<String> resp = client(viaProxy)
                        .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return resp.body();
                }
                last = new Exception("HTTP " + resp.statusCode() + (viaProxy ? "(经代理)" : "(直连)"));
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("请求失败");
    }

    // ---------------- Bing 网页搜索 ----------------

    private static final java.util.regex.Pattern BING_LI = java.util.regex.Pattern
            .compile("<li class=\"b_algo\".*?</li>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern BING_A = java.util.regex.Pattern
            .compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern BING_P = java.util.regex.Pattern
            .compile("<p[^>]*>(.*?)</p>", java.util.regex.Pattern.DOTALL);

    /** Bing 搜索(google/ddg 在无代理环境基本不可达,必应对直连最友好),返回去重结果。 */
    static List<Hit> search(String query, int limit) throws Exception {
        String url = "https://www.bing.com/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&setlang=zh-hans&count=20";
        String html = get(url, 15);
        List<Hit> out = new ArrayList<>();
        var li = BING_LI.matcher(html);
        while (li.find() && out.size() < limit) {
            String block = li.group();
            var a = BING_A.matcher(block);
            if (!a.find()) continue;
            String u = a.group(1);
            String title = stripTags(a.group(2));
            var p = BING_P.matcher(block);
            String snippet = p.find() ? stripTags(p.group(1)) : "";
            if (u.startsWith("/")) continue; // 站内跳转丢弃
            out.add(new Hit(title, u, snippet));
        }
        return out;
    }

    // ---------------- HTML → 纯文本 ----------------

    static String stripTags(String html) {
        String s = html.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|li|tr|h[1-6]|section|article)>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&mdash;", "—").replace("&middot;", "·");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" ?\\n ?", "\n");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }
}
