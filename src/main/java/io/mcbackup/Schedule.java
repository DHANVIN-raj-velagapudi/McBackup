package io.mcbackup;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/** Pure scheduling rules, kept separate so they can be tested. */
public final class Schedule {
    private Schedule() { }

    public record State(String lastHourlyKey, LocalDate lastDaily, LocalDate lastWeekly) {
        public static State empty() {
            return new State(null, null, null);
        }
    }

    public static String hourKey(ZonedDateTime t) {
        return t.toLocalDate() + "T" + String.format("%02d", t.getHour());
    }

    /**
     * Which job (if any) should run now. Daily and weekly are "once per day / week at or after their time",
     * so a server that was offline at midnight simply runs them when it comes back.
     */
    public static Tier due(ZonedDateTime now, State s, BackupConfig c) {
        LocalDate today = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        if (!today.equals(s.lastDaily()) && !t.isBefore(c.dailyTime)) return Tier.DAILY;
        if (now.getDayOfWeek() == c.weeklyDay && !today.equals(s.lastWeekly()) && !t.isBefore(c.weeklyTime)) return Tier.WEEKLY;
        if (now.getMinute() >= c.hourlyMinute && !hourKey(now).equals(s.lastHourlyKey())) return Tier.HOURLY;
        return null;
    }

    /** State after a successful job. A daily/weekly snapshot also counts as this hour's hourly. */
    public static State after(State s, Tier tier, ZonedDateTime when) {
        String hk = hourKey(when);
        return switch (tier) {
            case HOURLY -> new State(hk, s.lastDaily(), s.lastWeekly());
            case DAILY -> new State(hk, when.toLocalDate(), s.lastWeekly());
            case WEEKLY -> new State(hk, s.lastDaily(), when.toLocalDate());
        };
    }
}
