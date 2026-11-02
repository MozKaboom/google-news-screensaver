// SPDX-License-Identifier: Apache-2.0
package kiosk.googlenews;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Google News' public RSS feeds. Each item is one story: its headline as
 * {@code Headline - Outlet}, the outlet in {@code <source url="...">}, and, for stories with
 * wider coverage, a description that lists other outlets' headlines as an HTML list.
 */
final class HeadlineFeed {
    static final String BASE = "https://news.google.com/rss";

    /** Edition names as the settings show them, with Google News' language, country and edition codes. */
    static final Map<String, String[]> EDITIONS = new LinkedHashMap<>();
    static {
        edition("United Kingdom", "en-GB", "GB", "GB:en");
        edition("United States", "en-US", "US", "US:en");
        edition("Ireland", "en-IE", "IE", "IE:en");
        edition("Canada", "en-CA", "CA", "CA:en");
        edition("Australia", "en-AU", "AU", "AU:en");
        edition("New Zealand", "en-NZ", "NZ", "NZ:en");
        edition("India", "en-IN", "IN", "IN:en");
        edition("Singapore", "en-SG", "SG", "SG:en");
        edition("South Africa", "en-ZA", "ZA", "ZA:en");
        edition("Nigeria", "en-NG", "NG", "NG:en");
        edition("Philippines", "en-PH", "PH", "PH:en");
        edition("Deutschland", "de", "DE", "DE:de");
        edition("France", "fr", "FR", "FR:fr");
        edition("Espa\u00f1a", "es", "ES", "ES:es");
        edition("Italia", "it", "IT", "IT:it");
        edition("Nederland", "nl", "NL", "NL:nl");
        edition("Portugal", "pt-PT", "PT", "PT:pt-150");
        edition("Brasil", "pt-BR", "BR", "BR:pt-419");
        edition("M\u00e9xico", "es-419", "MX", "MX:es-419");
        edition("\u65e5\u672c", "ja", "JP", "JP:ja");
    }

    /** Section names as the settings show them, with Google News' topic IDs. Top stories has none. */
    static final Map<String, String> SECTIONS = new LinkedHashMap<>();
    static {
        SECTIONS.put("Top stories", null);
        SECTIONS.put("World", "WORLD");
        SECTIONS.put("Your country", "NATION");
        SECTIONS.put("Business", "BUSINESS");
        SECTIONS.put("Technology", "TECHNOLOGY");
        SECTIONS.put("Entertainment", "ENTERTAINMENT");
        SECTIONS.put("Sport", "SPORTS");
        SECTIONS.put("Science", "SCIENCE");
        SECTIONS.put("Health", "HEALTH");
    }

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern SOURCE = Pattern.compile("<source url=\"([^\"]*)\">(.*?)</source>", Pattern.DOTALL);
    private static final Pattern OUTLET = Pattern.compile("<font[^>]*>(.*?)</font>", Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);");

    private HeadlineFeed() {}

    private static void edition(String name, String language, String country, String id) {
        EDITIONS.put(name, new String[] {language, country, id});
    }

    /** The feed for an edition and a section, or for a search when {@code search} isn't blank. */
    static String url(String edition, String section, String search) {
        String[] codes = EDITIONS.containsKey(edition) ? EDITIONS.get(edition) : EDITIONS.get("United Kingdom");
        String query = "hl=" + codes[0] + "&gl=" + codes[1] + "&ceid=" + codes[2];
        String terms = search == null ? "" : search.trim();
        if (!terms.isEmpty()) return BASE + "/search?q=" + encode(terms) + "&" + query;
        String topic = SECTIONS.get(section);
        return topic == null ? BASE + "?" + query : BASE + "/headlines/section/topic/" + topic + "?" + query;
    }

    /** The heading the screensaver shows for these settings, such as {@code Top stories} or a search. */
    static String heading(String section, String search) {
        String terms = search == null ? "" : search.trim();
        if (!terms.isEmpty()) return "\u201c" + terms + "\u201d";
        return SECTIONS.containsKey(section) ? section : "Top stories";
    }

    /** At most {@code limit} stories in feed order. */
    static List<Map<String, Object>> parse(String xml, int limit) {
        List<Map<String, Object>> stories = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher item = ITEM.matcher(xml);
        while (item.find() && stories.size() < limit) {
            String body = item.group(1);
            String title = text(element(body, "title"));
            if (title == null) continue;
            String id = text(element(body, "guid"));
            if (id == null) id = text(element(body, "link"));
            if (id == null) id = title;
            if (!seen.add(id)) continue;

            String source = null, site = null;
            Matcher m = SOURCE.matcher(body);
            if (m.find()) {
                site = unescape(m.group(1)).trim();
                source = text(m.group(2));
            }
            // Titles end with " - Outlet". Drop it, as the outlet shows on its own.
            if (source != null && title.endsWith(" - " + source) && title.length() > source.length() + 3) {
                title = title.substring(0, title.length() - source.length() - 3).trim();
            }
            // Many outlets also end the headline with their name, as in "Headline | ITV News".
            int bar = title.lastIndexOf(" | ");
            if (bar > 0 && title.length() - bar <= 40 && title.substring(bar + 3).split(" ").length <= 4) {
                title = title.substring(0, bar).trim();
            }

            // The description is escaped HTML: a list of headlines, each with its outlet in a <font>.
            Set<String> outlets = new LinkedHashSet<>();
            int count = 0;
            String description = element(body, "description");
            if (description != null) {
                Matcher outlet = OUTLET.matcher(unescape(description));
                while (outlet.find()) {
                    String name = text(outlet.group(1));
                    if (name == null) continue;
                    count++;
                    if (!name.equals(source)) outlets.add(name);
                }
            }

            Map<String, Object> story = new LinkedHashMap<>();
            story.put("id", id);
            story.put("title", title);
            story.put("source", source);
            story.put("site", site != null && site.startsWith("https://") ? site : null);
            story.put("start", text(element(body, "pubDate")));
            story.put("sources", count > 1 ? count : null);
            story.put("outlets", new ArrayList<>(outlets));
            stories.add(story);
        }
        return stories;
    }

    private static String element(String body, String name) {
        int open = body.indexOf("<" + name);
        if (open < 0) return null;
        int start = body.indexOf('>', open);
        int end = body.indexOf("</" + name + ">", start);
        if (start < 0 || end < 0) return null;
        String value = body.substring(start + 1, end);
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) value = value.substring(9, value.length() - 3);
        return value;
    }

    /** XML text to plain text: entities decoded, tags dropped, whitespace collapsed. Null when empty. */
    static String text(String value) {
        if (value == null) return null;
        String s = TAGS.matcher(unescape(value)).replaceAll(" ");
        s = s.replace((char) 0xa0, ' ').replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    static String unescape(String value) {
        if (value.indexOf('&') < 0) return value;
        Matcher m = ENTITY.matcher(value);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            String replacement;
            if (name.startsWith("#")) {
                try {
                    int code = name.startsWith("#x") || name.startsWith("#X")
                        ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                    replacement = new String(Character.toChars(code));
                } catch (IllegalArgumentException e) {
                    replacement = m.group(0);
                }
            } else {
                switch (name.toLowerCase(Locale.ROOT)) {
                    case "amp": replacement = "&"; break;
                    case "lt": replacement = "<"; break;
                    case "gt": replacement = ">"; break;
                    case "quot": replacement = "\""; break;
                    case "apos": replacement = "'"; break;
                    case "nbsp": replacement = String.valueOf((char) 0xa0); break;
                    default: replacement = m.group(0);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String encode(String terms) {
        try {
            return URLEncoder.encode(terms, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
