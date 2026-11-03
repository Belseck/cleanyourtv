package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One date rule of a season pack. The matching logic is pure java.time so it is unit-tested without a device;
 * only {@link #fromJson} touches org.json.
 */
final class SeasonRule {

    private static final int RANGE = 0, NTH_WEEKDAY = 1, LUNAR_NEW_YEAR = 2, EASTER = 3;
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})-(\\d{1,2})");

    private final int kind;
    // Meaning depends on kind: range = fromMd, toMd; nth = month, weekday, n, before, after; easter = from, to;
    // lunar = days.
    private final int a, b, c, d, e;

    private SeasonRule(int kind, int a, int b, int c, int d, int e) {
        this.kind = kind;
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.e = e;
    }

    /** Inclusive month/day range; wraps the year end when from is after to. */
    static SeasonRule range(int fromMonth, int fromDay, int toMonth, int toDay) {
        return new SeasonRule(RANGE, fromMonth * 100 + fromDay, toMonth * 100 + toDay, 0, 0, 0);
    }

    /** The n-th (1..5, or -1 = last) weekday of a month, widened by days on each side. */
    static SeasonRule nthWeekday(int month, DayOfWeek weekday, int n, int daysBefore, int daysAfter) {
        return new SeasonRule(NTH_WEEKDAY, month, weekday.getValue(), n, daysBefore, daysAfter);
    }

    /** The first {@code days} days of the first (non-leap) lunar month, Chinese calendar. */
    static SeasonRule lunarNewYear(int days) {
        return new SeasonRule(LUNAR_NEW_YEAR, days, 0, 0, 0, 0);
    }

    /** Days relative to Western Easter Sunday, inclusive. */
    static SeasonRule easter(int fromOffset, int toOffset) {
        return new SeasonRule(EASTER, fromOffset, toOffset, 0, 0, 0);
    }

    boolean matches(LocalDate date) {
        switch (kind) {
            case RANGE: {
                int md = date.getMonthValue() * 100 + date.getDayOfMonth();
                return a <= b ? md >= a && md <= b : md >= a || md <= b;
            }
            case NTH_WEEKDAY:
                // Neighbouring years too: the window can spill over New Year (e.g. a December rule).
                for (int y = date.getYear() - 1; y <= date.getYear() + 1; y++) {
                    LocalDate first = LocalDate.of(y, a, 1);
                    DayOfWeek wd = DayOfWeek.of(b);
                    LocalDate anchor = c == -1 ? first.with(TemporalAdjusters.lastInMonth(wd))
                            : first.with(TemporalAdjusters.dayOfWeekInMonth(c, wd));
                    if (anchor.getMonthValue() != a) continue; // a 5th weekday that does not exist
                    long off = date.toEpochDay() - anchor.toEpochDay();
                    if (off >= -d && off <= e) return true;
                }
                return false;
            case LUNAR_NEW_YEAR: {
                int[] l = Lunar.fromSolar(date.getDayOfMonth(), date.getMonthValue(), date.getYear(), 8);
                return l[1] == 1 && l[3] == 0 && l[0] <= a;
            }
            default:
                // Offsets reach up to 120 days, so Easter of the neighbouring years can still be in range.
                for (int y = date.getYear() - 1; y <= date.getYear() + 1; y++) {
                    long off = date.toEpochDay() - easterSunday(y).toEpochDay();
                    if (off >= a && off <= b) return true;
                }
                return false;
        }
    }

    /** Western Easter Sunday, Anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    static LocalDate easterSunday(int year) {
        int g = year % 19, cent = year / 100, yy = year % 100;
        int dd = cent / 4, ee = cent % 4, f = (cent + 8) / 25, gg = (cent - f + 1) / 3;
        int h = (19 * g + cent - dd - gg + 15) % 30;
        int i = yy / 4, k = yy % 4;
        int l = (32 + 2 * ee + 2 * i - h - k) % 7;
        int m = (g + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }

    static SeasonRule fromJson(JSONObject dates) throws IllegalArgumentException {
        if (dates == null) throw new IllegalArgumentException("missing \"dates\"");
        try {
            String rule = dates.has("rule") ? dates.getString("rule") : "range";
            switch (rule) {
                case "range": {
                    int[] from = monthDay(dates.getString("from"), "from");
                    int[] to = monthDay(dates.getString("to"), "to");
                    return range(from[0], from[1], to[0], to[1]);
                }
                case "nth-weekday": {
                    int month = dates.getInt("month");
                    if (month < 1 || month > 12) throw new IllegalArgumentException("month must be 1-12");
                    DayOfWeek wd;
                    try {
                        wd = DayOfWeek.valueOf(dates.getString("weekday").trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException x) {
                        throw new IllegalArgumentException("unknown weekday \"" + dates.getString("weekday") + "\"");
                    }
                    int n = dates.getInt("n");
                    if (n != -1 && (n < 1 || n > 5)) throw new IllegalArgumentException("n must be 1-5 or -1");
                    int before = dates.optInt("days_before", 0), after = dates.optInt("days_after", 0);
                    if (before < 0 || before > 30 || after < 0 || after > 30) {
                        throw new IllegalArgumentException("days_before/days_after must be 0-30");
                    }
                    return nthWeekday(month, wd, n, before, after);
                }
                case "lunar-new-year": {
                    int days = dates.getInt("days");
                    if (days < 1 || days > 15) throw new IllegalArgumentException("days must be 1-15");
                    return lunarNewYear(days);
                }
                case "easter": {
                    int from = dates.getInt("from"), to = dates.getInt("to");
                    if (Math.abs(from) > 120 || Math.abs(to) > 120) {
                        throw new IllegalArgumentException("easter offsets must be within 120 days");
                    }
                    if (from > to) throw new IllegalArgumentException("easter \"from\" is after \"to\"");
                    return easter(from, to);
                }
                default:
                    throw new IllegalArgumentException("unknown rule \"" + rule + "\"");
            }
        } catch (JSONException x) {
            throw new IllegalArgumentException("bad dates: " + x.getMessage());
        }
    }

    private static int[] monthDay(String s, String field) {
        Matcher m = MONTH_DAY.matcher(s.trim());
        if (!m.matches()) throw new IllegalArgumentException("\"" + field + "\" must look like MM-DD, got \"" + s + "\"");
        int month = Integer.parseInt(m.group(1)), day = Integer.parseInt(m.group(2));
        // 2000 is a leap year, so 02-29 is accepted.
        if (month < 1 || month > 12 || day < 1 || day > YearMonth.of(2000, month).lengthOfMonth()) {
            throw new IllegalArgumentException("\"" + field + "\" is not a valid date: \"" + s + "\"");
        }
        return new int[]{month, day};
    }
}
