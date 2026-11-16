package dev.darkerdawnfr.darknames;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coloured text for item names: hex colours (§x§R§R§G§G§B§B), gradients and animation frames.
 * Typed names can use &a-style codes, &#RRGGBB, <#RRGGBB> and <gradient:#from:#to>text</gradient>.
 */
public final class Colors {
    private Colors() {
    }

    public static final char S = '§';

    /** A style: what colours a name gets. kind = solid, gradient, anim (with an animation id), or custom (the typed codes). */
    public record Style(String kind, String id, List<String> colors) {
        public String save() {
            return kind + ":" + id + ":" + String.join(",", colors);
        }

        public static Style load(String s) {
            if (s == null) return null;
            String[] p = s.split(":", 3);
            if (p.length < 3) return null;
            List<String> c = new ArrayList<>();
            for (String x : p[2].split(",")) if (!x.isBlank()) c.add(x.trim());
            return new Style(p[0], p[1], c);
        }
    }

    // ---------------------------------------------------------------- presets

    /** name -> hex */
    public static final Map<String, String> SOLIDS = new LinkedHashMap<>();
    /** name -> colours (2 or more) */
    public static final Map<String, List<String>> GRADIENTS = new LinkedHashMap<>();
    /** id -> {name, description} */
    public static final Map<String, String[]> ANIMATIONS = new LinkedHashMap<>();

    static {
        for (String[] c : new String[][]{
                {"White", "#FFFFFF"}, {"Gray", "#AAAAAA"}, {"Red", "#FF5555"}, {"Gold", "#FFAA00"}, {"Yellow", "#FFFF55"},
                {"Green", "#55FF55"}, {"Aqua", "#55FFFF"}, {"Blue", "#5555FF"}, {"Pink", "#FF55FF"},
                {"Dark Red", "#AA0000"}, {"Dark Green", "#00AA00"}, {"Dark Aqua", "#00AAAA"}, {"Dark Blue", "#0000AA"},
                {"Purple", "#AA00AA"}, {"Black", "#2B2B2B"}, {"Hot Pink", "#FF69B4"}, {"Orange", "#FF8C00"}, {"Mint", "#3EB489"}})
            SOLIDS.put(c[0], c[1]);
        GRADIENTS.put("Sunset", List.of("#FF512F", "#F09819"));
        GRADIENTS.put("Fire", List.of("#FF0000", "#FF8000", "#FFE000"));
        GRADIENTS.put("Ocean", List.of("#00C6FF", "#0072FF"));
        GRADIENTS.put("Ice", List.of("#FFFFFF", "#9BE8FF", "#3AA0FF"));
        GRADIENTS.put("Toxic", List.of("#B4FF00", "#00C853"));
        GRADIENTS.put("Candy", List.of("#FF6EC7", "#7873F5"));
        GRADIENTS.put("Galaxy", List.of("#7F00FF", "#E100FF", "#00D4FF"));
        GRADIENTS.put("Royal", List.of("#FFD700", "#FF8C00", "#B8860B"));
        GRADIENTS.put("Rainbow", List.of("#FF0000", "#FF8000", "#FFFF00", "#00FF00", "#00FFFF", "#0000FF", "#FF00FF"));
        ANIMATIONS.put("rainbow", new String[]{"Rainbow Wave", "Every colour, moving across the name"});
        ANIMATIONS.put("wave", new String[]{"Gradient Wave", "Your gradient scrolls along the name"});
        ANIMATIONS.put("pulse", new String[]{"Pulse", "Fades between two colours"});
        ANIMATIONS.put("shine", new String[]{"Shine", "A white shine sweeps across"});
        ANIMATIONS.put("flicker", new String[]{"Fire Flicker", "Red, orange and yellow, like fire"});
        ANIMATIONS.put("glitch", new String[]{"Glitch", "Letters scramble now and then"});
    }

    // ---------------------------------------------------------------- basics

    public static String hex(String hex) {
        String h = hex.replace("#", "").toUpperCase(Locale.ROOT);
        StringBuilder b = new StringBuilder().append(S).append('x');
        for (char c : h.toCharArray()) b.append(S).append(c);
        return b.toString();
    }

    public static String hex(Color c) {
        return hex(String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
    }

    public static Color parse(String hex) {
        return Color.decode(hex.startsWith("#") ? hex : "#" + hex);
    }

    public static boolean isHex(String s) {
        return s != null && s.matches("#?[0-9A-Fa-f]{6}");
    }

    /** Formats like "lno": l bold, o italic, n underline, m strikethrough, k magic. */
    public static String formats(String f) {
        StringBuilder b = new StringBuilder();
        if (f != null) for (char c : f.toCharArray()) if ("lonmk".indexOf(c) >= 0) b.append(S).append(c);
        return b.toString();
    }

    public static String strip(String s) {
        return s == null ? "" : s.replaceAll("(?i)" + S + "[0-9A-FK-ORX]", "");
    }

    static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** The colour at t (0..1) along several colours. */
    static Color along(List<Color> cs, double t) {
        if (cs.size() == 1) return cs.get(0);
        t = Math.max(0, Math.min(1, t));
        double pos = t * (cs.size() - 1);
        int i = Math.min(cs.size() - 2, (int) Math.floor(pos));
        return mix(cs.get(i), cs.get(i + 1), pos - i);
    }

    /** Colours per letter: each letter its own colour, then the formats. Spaces get none. */
    static String paint(String text, java.util.function.IntFunction<Color> colorAt, String fmt) {
        StringBuilder b = new StringBuilder();
        int n = text.codePointCount(0, text.length()), i = 0;
        for (int off = 0; off < text.length(); ) {
            int cp = text.codePointAt(off);
            off += Character.charCount(cp);
            if (Character.isWhitespace(cp)) b.appendCodePoint(cp);
            else b.append(hex(colorAt.apply(i))).append(formats(fmt)).appendCodePoint(cp);
            i++;
        }
        return b.toString();
    }

    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    // ---------------------------------------------------------------- styles

    public static String solid(String text, String hex, String fmt) {
        return hex(hex) + formats(fmt) + text;
    }

    public static String gradient(String text, List<String> hexes, String fmt) {
        List<Color> cs = new ArrayList<>();
        for (String h : hexes) cs.add(parse(h));
        int n = Math.max(1, length(text) - 1);
        return paint(text, i -> along(cs, (double) i / n), fmt);
    }

    /** One frame of an animation. frame goes up by 1 every update. */
    public static String frame(String id, String text, List<String> hexes, String fmt, long frame) {
        int n = Math.max(1, length(text));
        List<Color> cs = new ArrayList<>();
        for (String h : hexes) cs.add(parse(h));
        switch (id) {
            case "rainbow" -> {
                return paint(text, i -> Color.getHSBColor((float) (((i * 0.06) - frame * 0.03) % 1 + 1) % 1, 0.85f, 1f), fmt);
            }
            case "wave" -> {
                List<Color> loop = new ArrayList<>(cs.isEmpty() ? List.of(parse("#7F00FF"), parse("#00D4FF")) : cs);
                loop.add(loop.get(0)); // so it wraps around smoothly
                return paint(text, i -> along(loop, (((double) i / Math.max(n, 8) - frame * 0.04) % 1 + 1) % 1), fmt);
            }
            case "pulse" -> {
                Color a = cs.isEmpty() ? parse("#FF55FF") : cs.get(0), b = cs.size() > 1 ? cs.get(1) : parse("#5555FF");
                double t = (Math.sin(frame * 0.25) + 1) / 2;
                return solid(text, String.format("#%06X", mix(a, b, t).getRGB() & 0xFFFFFF), fmt);
            }
            case "shine" -> {
                Color base = cs.isEmpty() ? parse("#FFAA00") : cs.get(0);
                int width = 3, cycle = n + 10;
                double head = frame % cycle - 3;
                return paint(text, i -> {
                    double d = Math.abs(i - head);
                    return d < width ? mix(base, Color.WHITE, 1 - d / width) : base;
                }, fmt);
            }
            case "flicker" -> {
                Color[] fire = {parse("#FF2000"), parse("#FF7A00"), parse("#FFD000")};
                return paint(text, i -> {
                    Random r = new Random((frame / 2) * 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L);
                    return mix(fire[r.nextInt(3)], fire[r.nextInt(3)], r.nextDouble());
                }, fmt);
            }
            case "glitch" -> {
                Color base = cs.isEmpty() ? parse("#55FFFF") : cs.get(0);
                Random r = new Random((frame / 3) * 0x9E3779B97F4A7C15L + 12345);
                boolean on = r.nextInt(3) == 0; // now and then
                StringBuilder b = new StringBuilder();
                int i = 0;
                for (int off = 0; off < text.length(); ) {
                    int cp = text.codePointAt(off);
                    off += Character.charCount(cp);
                    boolean scramble = on && !Character.isWhitespace(cp) && r.nextInt(3) == 0;
                    b.append(hex(scramble ? parse("#FFFFFF") : base)).append(formats(fmt)).append(scramble ? S + "k" : "").appendCodePoint(cp);
                    i++;
                }
                return b.toString();
            }
            default -> {
                return gradient(text, hexes.isEmpty() ? List.of("#FFFFFF") : hexes, fmt);
            }
        }
    }

    // ---------------------------------------------------------------- typed codes

    private static final Pattern GRADIENT_TAG = Pattern.compile("(?i)<gradient:(#?[0-9a-f]{6}(?::#?[0-9a-f]{6})+)>(.*?)</gradient>");
    private static final Pattern HEX_AMP = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Pattern HEX_TAG = Pattern.compile("(?i)<#([0-9a-f]{6})>");

    /** &a codes, &#RRGGBB, <#RRGGBB> and <gradient:#a:#b>text</gradient> into colours. */
    public static String codes(String s) {
        Matcher g = GRADIENT_TAG.matcher(s);
        StringBuilder out = new StringBuilder();
        while (g.find()) {
            List<String> hs = new ArrayList<>();
            for (String h : g.group(1).split(":")) hs.add(h.startsWith("#") ? h : "#" + h);
            g.appendReplacement(out, Matcher.quoteReplacement(gradient(g.group(2), hs, "")));
        }
        g.appendTail(out);
        String t = out.toString();
        Matcher m = HEX_AMP.matcher(t);
        StringBuilder o2 = new StringBuilder();
        while (m.find()) m.appendReplacement(o2, Matcher.quoteReplacement(hex(m.group(1))));
        m.appendTail(o2);
        Matcher m2 = HEX_TAG.matcher(o2.toString());
        StringBuilder o3 = new StringBuilder();
        while (m2.find()) m2.appendReplacement(o3, Matcher.quoteReplacement(hex(m2.group(1))));
        m2.appendTail(o3);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', o3.toString());
    }

    /** True if the typed text has colour codes of its own. */
    public static boolean hasCodes(String s) {
        return s.matches("(?is).*(&[0-9a-fk-or]|&#[0-9a-f]{6}|<#[0-9a-f]{6}>|<gradient:).*");
    }

    /** The name for a style (frame is used by animations). */
    public static String render(String text, Style st, String fmt, long frame) {
        if (st == null) return solid(text, "#FFFFFF", fmt);
        return switch (st.kind()) {
            case "solid" -> solid(text, st.colors().isEmpty() ? "#FFFFFF" : st.colors().get(0), fmt);
            case "gradient" -> gradient(text, st.colors().isEmpty() ? List.of("#FFFFFF", "#AAAAAA") : st.colors(), fmt);
            case "anim" -> frame(st.id(), text, st.colors(), fmt, frame);
            case "custom" -> codes(text);
            default -> text;
        };
    }
}
