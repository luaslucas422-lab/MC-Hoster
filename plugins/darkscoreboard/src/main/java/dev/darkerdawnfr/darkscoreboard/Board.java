package dev.darkerdawnfr.darkscoreboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;

/**
 * One player's sidebar. Each line is a team prefix on an invisible entry, so lines can repeat,
 * be empty, and change without flickering.
 */
public class Board {
    public static final int MAX_LINES = 15;
    private final Scoreboard sb;
    private final Objective obj;
    private int shown;

    public Board(boolean hideNumbers) {
        sb = Bukkit.getScoreboardManager().getNewScoreboard();
        obj = sb.registerNewObjective("darksb", Criteria.DUMMY, " ");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        if (hideNumbers) {
            try {
                PaperNumbers.hide(obj);
            } catch (Throwable ignored) {
                // Spigot or an older Paper: the red numbers stay
            }
        }
    }

    public Scoreboard scoreboard() {
        return sb;
    }

    private static String entry(int i) {
        return ChatColor.values()[i].toString() + ChatColor.RESET;
    }

    public void update(String title, List<String> lines) {
        if (!title.equals(obj.getDisplayName())) obj.setDisplayName(title.isEmpty() ? " " : title);
        int n = Math.min(MAX_LINES, lines.size());
        for (int i = 0; i < n; i++) {
            String e = entry(i);
            Team t = sb.getTeam("dsb" + i);
            if (t == null) {
                t = sb.registerNewTeam("dsb" + i);
                t.addEntry(e);
            }
            String text = lines.get(i);
            if (!text.equals(t.getPrefix())) {
                try {
                    t.setPrefix(text);
                } catch (IllegalArgumentException tooLong) {
                    t.setPrefix(text.substring(0, Math.min(text.length(), 64)));
                }
            }
            obj.getScore(e).setScore(n - i);
        }
        for (int i = n; i < shown; i++) sb.resetScores(entry(i));
        shown = n;
    }

    public int lines() {
        return sb.getEntries().size();
    }

    private static final class PaperNumbers {
        static void hide(Objective o) {
            o.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        }
    }
}
