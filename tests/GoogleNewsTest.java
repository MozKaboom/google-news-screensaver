// SPDX-License-Identifier: Apache-2.0
package kiosk.googlenews;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/** Exercises the feed parser and the plugin against a small feed shaped like Google News' RSS. */
public final class GoogleNewsTest {
    static final class Host implements PluginHost {
        final List<String> documents = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String status;
        volatile boolean statusError;
        boolean removed;
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
        public void status(String message, boolean error) { status = message; statusError = error; }
        public void publishScreensaver(String key, String title, String html) {
            assert GoogleNewsPlugin.KEY.equals(key) && GoogleNewsPlugin.TITLE.equals(title);
            assert html.getBytes(StandardCharsets.UTF_8).length <= 512 * 1024;
            documents.add(html);
        }
        public void removeScreensaver(String key) { removed = true; }
        public InputStream openAsset(String name) {
            assert GoogleNewsPlugin.TEMPLATE.equals(name);
            return new ByteArrayInputStream("<script>__GN_DATA__</script>".getBytes(StandardCharsets.UTF_8));
        }
    }

    static String item(String id, String title, String site, String source, String description) {
        return "<item><title>" + title + "</title><link>https://news.google.com/rss/articles/" + id + "?oc=5</link>"
            + "<guid isPermaLink=\"false\">" + id + "</guid><pubDate>Sun, 01 Nov 2026 21:21:46 GMT</pubDate>"
            + "<description>" + description + "</description><source url=\"" + site + "\">" + source + "</source></item>";
    }

    static String li(String headline, String outlet) {
        return "&lt;li&gt;&lt;a href=\"https://news.google.com/rss/articles/x\" target=\"_blank\"&gt;" + headline
            + "&lt;/a&gt;&amp;nbsp;&amp;nbsp;&lt;font color=\"#6f6f6f\"&gt;" + outlet + "&lt;/font&gt;&lt;/li&gt;";
    }

    static String feed() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>Top stories</title>"
            + item("a1", "Nine killed in jet crash &amp; fire - BBC", "https://www.bbc.co.uk", "BBC",
                "&lt;ol&gt;" + li("Nine killed", "BBC") + li("All nine died", "The Guardian") + li("Jet crash", "Sky News")
                    + li("Pilot&amp;#39;s last words", "The Guardian") + "&lt;/ol&gt;")
            + item("a2", "Budget unlocks \u00a310bn | London Evening Standard - Evening Standard", "https://www.standard.co.uk", "Evening Standard",
                "&lt;a href=\"x\"&gt;Budget unlocks&lt;/a&gt;&amp;nbsp;&amp;nbsp;&lt;font color=\"#6f6f6f\"&gt;Evening Standard&lt;/font&gt;")
            + item("a1", "Duplicate - BBC", "https://www.bbc.co.uk", "BBC", "")
            + item("a3", "No outlet here", "http://insecure.example", "Example", "")
            + "</channel></rss>";
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        assert HeadlineFeed.url("United Kingdom", "Top stories", "").equals("https://news.google.com/rss?hl=en-GB&gl=GB&ceid=GB:en");
        assert HeadlineFeed.url("United States", "Sport", " ").equals("https://news.google.com/rss/headlines/section/topic/SPORTS?hl=en-US&gl=US&ceid=US:en");
        assert HeadlineFeed.url("Nowhere", "Nothing", "Arsenal & Spurs").equals("https://news.google.com/rss/search?q=Arsenal+%26+Spurs&hl=en-GB&gl=GB&ceid=GB:en");
        assert HeadlineFeed.url("\u65e5\u672c", "Technology", null).equals("https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=ja&gl=JP&ceid=JP:ja");
        assert HeadlineFeed.heading("World", "").equals("World");
        assert HeadlineFeed.unescape("&amp;lt;&#39;&#x41;&bogus;").equals("&lt;'A&bogus;");

        List<Map<String, Object>> stories = HeadlineFeed.parse(feed(), 10);
        assert stories.size() == 3 : stories;
        Map<String, Object> first = stories.get(0);
        assert "a1".equals(first.get("id")) && "Nine killed in jet crash & fire".equals(first.get("title")) : first;
        assert "BBC".equals(first.get("source")) && "https://www.bbc.co.uk".equals(first.get("site"));
        assert Integer.valueOf(4).equals(first.get("sources")) : first;
        assert first.get("outlets").equals(java.util.Arrays.asList("The Guardian", "Sky News")) : first;
        assert "Sun, 01 Nov 2026 21:21:46 GMT".equals(first.get("start"));
        Map<String, Object> second = stories.get(1);
        assert "Budget unlocks \u00a310bn".equals(second.get("title")) && second.get("sources") == null;
        assert ((List<Object>) second.get("outlets")).isEmpty();
        assert stories.get(2).get("site") == null && "No outlet here".equals(stories.get(2).get("title"));
        assert HeadlineFeed.parse(feed(), 2).size() == 2;
        assert HeadlineFeed.parse("<rss></rss>", 10).isEmpty();

        // The plugin publishes a loading document, then the stories with their logos.
        Host host = new Host();
        GoogleNewsPlugin plugin = new GoogleNewsPlugin();
        final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        plugin.fetcher = new GoogleNewsPlugin.Fetcher() {
            public String feed(String url) { requests.add(url); return GoogleNewsTest.feed(); }
            public byte[] image(String url) throws IOException {
                requests.add(url);
                if (url.contains("standard")) throw new IOException("HTTP 404");
                return new byte[] {(byte) 0x89, 'P', 'N', 'G'};
            }
        };
        Map<String, Object> settings = new HashMap<>();
        settings.put("edition", "Ireland");
        settings.put("secondsPerStory", 20);
        settings.put("perScreen", 9);
        plugin.start(host, settings);
        waitFor(() -> host.documents.size() >= 2 && host.status != null);
        assert requests.get(0).equals("https://news.google.com/rss?hl=en-IE&gl=IE&ceid=IE:en") : requests;
        assert host.documents.get(0).contains("\"stories\":[]");
        String shown = host.documents.get(host.documents.size() - 1);
        assert shown.contains("Nine killed in jet crash") && shown.contains("data:image/png;base64,iVBORw==") : shown;
        assert shown.contains("\"heading\":\"Top stories\"") && shown.contains("\"edition\":\"Ireland\"") && shown.contains("\"secondsPerStory\":20");
        assert shown.contains("\"perScreen\":4") : shown;
        assert host.status.startsWith("3 stories") && !host.statusError : host.status;
        int logoRequests = requests.size() - 1;
        assert logoRequests == 2 : requests;

        // A display-only change re-renders without fetching.
        int published = host.documents.size();
        settings.put("showClock", false);
        plugin.configure(settings);
        waitFor(() -> host.documents.size() > published);
        assert host.documents.get(host.documents.size() - 1).contains("\"showClock\":false");
        assert requests.size() == logoRequests + 1;

        // A new section refetches, and known logos come from the cache.
        settings.put("section", "Business");
        plugin.configure(settings);
        waitFor(() -> requests.size() > logoRequests + 1 && host.documents.get(host.documents.size() - 1).contains("\"heading\":\"Business\""));
        assert requests.get(requests.size() - 1).contains("/topic/BUSINESS?") : requests;

        // A failed refresh keeps the last stories on screen.
        plugin.fetcher = new GoogleNewsPlugin.Fetcher() {
            public String feed(String url) throws IOException { throw new IOException("offline"); }
            public byte[] image(String url) throws IOException { throw new IOException("offline"); }
        };
        plugin.refresh();
        String stale = host.documents.get(host.documents.size() - 1);
        assert stale.contains("Nine killed") && stale.contains("Couldn't reach Google News") : stale;
        assert host.statusError;

        plugin.stop();
        assert host.removed;
        System.out.println("GoogleNewsTest passed");
    }

    interface Condition { boolean met(); }

    static void waitFor(Condition condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.met(); i++) Thread.sleep(25);
        assert condition.met() : "Timed out";
    }
}
