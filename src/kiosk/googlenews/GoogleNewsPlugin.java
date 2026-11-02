// SPDX-License-Identifier: Apache-2.0
package kiosk.googlenews;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * A screensaver that cycles through the headlines on Google News. A worker thread fetches the
 * RSS feed and the outlets' logos, then publishes them as one inline document. Callbacks only
 * schedule work, so they return well inside the three-second limit.
 */
public final class GoogleNewsPlugin implements KioskPlugin {
    static final String KEY = "headlines";
    static final String TITLE = "Headlines";
    static final String TEMPLATE = "screensaver/index.html";
    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;
    private static final int MAX_LOGO_BYTES = 64 * 1024;
    /** Google's favicon service, the one Google News itself uses for outlet logos. */
    private static final String LOGO = "https://encrypted-tbn0.gstatic.com/faviconV2?client=NEWS_360&size=96&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=";

    private PluginHost host;
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> refreshTask;
    private String template;

    // Written on the callback thread, read on the worker.
    private volatile Settings settings;
    // Only touched on the worker.
    private List<Map<String, Object>> stories = Collections.emptyList();
    private Map<String, String> logoCache = Collections.emptyMap(); // site to data URI
    private Map<String, String> logos = Collections.emptyMap(); // story ID to data URI
    private long updated;
    private String error;
    private String lastHtml;

    /** Network access, replaced in tests. */
    interface Fetcher {
        String feed(String url) throws IOException;
        byte[] image(String url) throws IOException;
    }

    Fetcher fetcher = new Fetcher() {
        @Override public String feed(String url) throws IOException {
            return Net.text(url, MAX_FEED_BYTES);
        }
        @Override public byte[] image(String url) throws IOException {
            return Net.bytes(url, MAX_LOGO_BYTES, "image/png,image/*");
        }
    };

    @Override
    public void start(PluginHost host, Map<String, Object> values) throws Exception {
        this.host = host;
        this.settings = new Settings(values);
        if (host.isHeadless()) return;
        template = readTemplate(host);
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "google-news-screensaver");
            thread.setDaemon(true);
            return thread;
        });
        worker.execute(this::render);
        scheduleRefresh(0);
    }

    @Override
    public void configure(Map<String, Object> values) {
        Settings previous = settings;
        Settings next = new Settings(values);
        settings = next;
        if (worker == null || previous == null) return;
        if (next.refreshMinutes != previous.refreshMinutes || next.storyCount != previous.storyCount
                || !next.feedUrl().equals(previous.feedUrl()) || (next.showLogos && !previous.showLogos)) {
            scheduleRefresh(0);
        } else if (!next.equals(previous)) {
            worker.execute(this::render);
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("refresh".equals(command) && worker != null) scheduleRefresh(0);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {}

    @Override
    public void stop() throws Exception {
        ScheduledExecutorService executor = worker;
        worker = null;
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
        try {
            if (host != null && !host.isHeadless()) host.removeScreensaver(KEY);
        } catch (RuntimeException ignored) {
            // The host removes the renderer itself when the session ends.
        }
    }

    private synchronized void scheduleRefresh(long delayMinutes) {
        if (worker == null) return;
        if (refreshTask != null) refreshTask.cancel(false);
        long period = settings.refreshMinutes;
        refreshTask = worker.scheduleWithFixedDelay(this::refresh, delayMinutes, period, TimeUnit.MINUTES);
    }

    /** Fetches the feed and logos, then publishes. Runs on the worker. */
    void refresh() {
        Settings current = settings;
        try {
            List<Map<String, Object>> fresh = HeadlineFeed.parse(fetcher.feed(current.feedUrl()), current.storyCount);
            if (fresh.isEmpty()) {
                if (current.search.isEmpty()) throw new IOException("No stories found on Google News");
                // A search with no results is an answer, not a failure.
            }
            // Logos already fetched for the same outlet are reused, so a refresh only downloads new ones.
            Map<String, String> cache = new HashMap<>();
            Map<String, String> byStory = new HashMap<>();
            if (current.showLogos) {
                for (Map<String, Object> story : fresh) {
                    if (Thread.currentThread().isInterrupted()) return;
                    String site = (String) story.get("site");
                    if (site == null) continue;
                    String uri = cache.containsKey(site) ? cache.get(site) : logoCache.get(site);
                    if (uri == null) {
                        try {
                            byte[] bytes = fetcher.image(LOGO + URLEncoder.encode(site, "UTF-8"));
                            uri = Renderer.dataUri(imageType(bytes), bytes);
                        } catch (IOException e) {
                            // Google answers 404 for outlets it has no logo for.
                            uri = "";
                        }
                    }
                    cache.put(site, uri);
                    if (!uri.isEmpty()) byStory.put((String) story.get("id"), uri);
                }
            }
            logoCache = cache;
            logos = byStory;
            stories = fresh;
            updated = System.currentTimeMillis();
            error = null;
            render();
            String when = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(updated));
            status(fresh.isEmpty() ? "No stories match the search, updated " + when : fresh.size() + " stories, updated " + when, false);
        } catch (IOException | RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) return;
            error = "Couldn't reach Google News";
            host.log("Refresh failed: " + e);
            status("Refresh failed: " + e.getMessage() + (stories.isEmpty() ? "" : ". Showing the last stories."), true);
            render();
        }
    }

    /** Publishes the current stories with the current settings, unless nothing changed. Runs on the worker. */
    void render() {
        if (template == null) return;
        Settings current = settings;
        Map<String, String> shown = current.showLogos ? logos : Collections.<String, String>emptyMap();
        String html = Renderer.html(template, stories, shown, current.options(), updated, error);
        if (html.equals(lastHtml)) return;
        lastHtml = html;
        host.publishScreensaver(KEY, TITLE, html);
    }

    private void status(String message, boolean isError) {
        try {
            host.status(message, isError);
        } catch (RuntimeException ignored) {
            // Status text is a nicety.
        }
    }

    static String imageType(byte[] bytes) {
        if (bytes.length > 3 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P') return "image/png";
        if (bytes.length > 11 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[8] == 'W' && bytes[9] == 'E') return "image/webp";
        if (bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I') return "image/gif";
        if (bytes.length > 3 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 1) return "image/x-icon";
        return "image/jpeg";
    }

    static String readTemplate(PluginHost host) throws IOException {
        try (InputStream in = host.openAsset(TEMPLATE)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** The validated settings. KS checks ranges, but clamp anyway so a bad value can't stall the worker. */
    static final class Settings {
        final String edition;
        final String section;
        final String search;
        final int storyCount;
        final int perScreen;
        final int secondsPerStory;
        final int refreshMinutes;
        final boolean showLogos;
        final boolean showCoverage;
        final boolean showClock;

        Settings(Map<String, Object> values) {
            edition = choice(values.get("edition"), HeadlineFeed.EDITIONS.keySet(), "United Kingdom");
            section = choice(values.get("section"), HeadlineFeed.SECTIONS.keySet(), "Top stories");
            search = values.get("search") instanceof String ? ((String) values.get("search")).trim() : "";
            storyCount = number(values.get("storyCount"), 10, 3, 20);
            perScreen = number(values.get("perScreen"), 1, 1, 4);
            secondsPerStory = number(values.get("secondsPerStory"), 12, 5, 120);
            refreshMinutes = number(values.get("refreshMinutes"), 30, 10, 240);
            showLogos = flag(values.get("showLogos"), true);
            showCoverage = flag(values.get("showCoverage"), true);
            showClock = flag(values.get("showClock"), true);
        }

        String feedUrl() {
            return HeadlineFeed.url(edition, section, search);
        }

        Map<String, Object> options() {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("heading", HeadlineFeed.heading(section, search));
            options.put("edition", edition);
            options.put("language", HeadlineFeed.EDITIONS.get(edition)[0]);
            options.put("perScreen", perScreen);
            options.put("secondsPerStory", secondsPerStory);
            options.put("showCoverage", showCoverage);
            options.put("showClock", showClock);
            return options;
        }

        private static String choice(Object value, java.util.Set<String> allowed, String fallback) {
            return value instanceof String && allowed.contains(value) ? (String) value : fallback;
        }

        private static int number(Object value, int fallback, int min, int max) {
            if (!(value instanceof Number)) return fallback;
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d)) return fallback;
            return (int) Math.max(min, Math.min(max, Math.round(d)));
        }

        private static boolean flag(Object value, boolean fallback) {
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Settings)) return false;
            Settings o = (Settings) other;
            return edition.equals(o.edition) && section.equals(o.section) && search.equals(o.search)
                && storyCount == o.storyCount && perScreen == o.perScreen && secondsPerStory == o.secondsPerStory && refreshMinutes == o.refreshMinutes
                && showLogos == o.showLogos && showCoverage == o.showCoverage && showClock == o.showClock;
        }

        @Override public int hashCode() {
            return Objects.hash(edition, section, search, storyCount, perScreen, secondsPerStory, refreshMinutes, showLogos, showCoverage, showClock);
        }
    }
}
