package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

public class SeasonRuleTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    public void rangeIsInclusive() {
        SeasonRule r = SeasonRule.range(3, 20, 6, 20);
        assertFalse(r.matches(d(2026, 3, 19)));
        assertTrue(r.matches(d(2026, 3, 20)));
        assertTrue(r.matches(d(2026, 6, 20)));
        assertFalse(r.matches(d(2026, 6, 21)));
    }

    @Test
    public void rangeWrapsTheYearEnd() {
        SeasonRule r = SeasonRule.range(12, 31, 1, 2);
        assertFalse(r.matches(d(2026, 12, 30)));
        assertTrue(r.matches(d(2026, 12, 31)));
        assertTrue(r.matches(d(2027, 1, 1)));
        assertTrue(r.matches(d(2027, 1, 2)));
        assertFalse(r.matches(d(2027, 1, 3)));
    }

    @Test
    public void leapDay() {
        SeasonRule single = SeasonRule.range(2, 29, 2, 29);
        assertTrue(single.matches(d(2028, 2, 29)));
        assertFalse(single.matches(d(2027, 2, 28)));
        assertFalse(single.matches(d(2027, 3, 1)));
        SeasonRule across = SeasonRule.range(2, 28, 3, 1);
        assertTrue(across.matches(d(2027, 2, 28)));
        assertTrue(across.matches(d(2027, 3, 1)));
        assertTrue(across.matches(d(2028, 2, 29)));
    }

    @Test
    public void nthWeekdayWithWindow() {
        // Third Saturday of September 2026 is the 19th.
        SeasonRule r = SeasonRule.nthWeekday(9, DayOfWeek.SATURDAY, 3, 1, 1);
        assertFalse(r.matches(d(2026, 9, 17)));
        assertTrue(r.matches(d(2026, 9, 18)));
        assertTrue(r.matches(d(2026, 9, 19)));
        assertTrue(r.matches(d(2026, 9, 20)));
        assertFalse(r.matches(d(2026, 9, 21)));
    }

    @Test
    public void lastWeekday() {
        // Last Monday of May 2026 is the 25th.
        SeasonRule r = SeasonRule.nthWeekday(5, DayOfWeek.MONDAY, -1, 0, 0);
        assertTrue(r.matches(d(2026, 5, 25)));
        assertFalse(r.matches(d(2026, 5, 18)));
        assertFalse(r.matches(d(2026, 6, 1)));
    }

    @Test
    public void missingFifthWeekdayNeverMatches() {
        // February 2027 has only four Mondays; the fifth must not leak into March.
        SeasonRule r = SeasonRule.nthWeekday(2, DayOfWeek.MONDAY, 5, 0, 0);
        assertFalse(r.matches(d(2027, 3, 1)));
        assertFalse(r.matches(d(2027, 2, 22)));
        assertTrue(SeasonRule.nthWeekday(2, DayOfWeek.MONDAY, 4, 0, 0).matches(d(2027, 2, 22)));
    }

    @Test
    public void nthWeekdayWindowCrossesNewYear() {
        // Last Thursday of December 2026 is the 31st; two days after reaches 2027-01-02.
        SeasonRule r = SeasonRule.nthWeekday(12, DayOfWeek.THURSDAY, -1, 0, 2);
        assertTrue(r.matches(d(2026, 12, 31)));
        assertTrue(r.matches(d(2027, 1, 2)));
        assertFalse(r.matches(d(2027, 1, 3)));
    }

    @Test
    public void easterDates() {
        assertEquals(d(2026, 4, 5), SeasonRule.easterSunday(2026));
        assertEquals(d(2027, 3, 28), SeasonRule.easterSunday(2027));
        assertEquals(d(2028, 4, 16), SeasonRule.easterSunday(2028));
    }

    @Test
    public void easterOffsets() {
        SeasonRule holyWeek = SeasonRule.easter(-7, 0);
        assertFalse(holyWeek.matches(d(2026, 3, 28)));
        assertTrue(holyWeek.matches(d(2026, 3, 29)));
        assertTrue(holyWeek.matches(d(2026, 4, 5)));
        assertFalse(holyWeek.matches(d(2026, 4, 6)));
        // The window can sit in the previous year's Easter.
        assertTrue(SeasonRule.easter(110, 120).matches(d(2026, 7, 25)));
    }

    @Test
    public void lunarNewYear() {
        SeasonRule r = SeasonRule.lunarNewYear(3);
        assertFalse(r.matches(d(2027, 2, 5)));
        assertTrue(r.matches(d(2027, 2, 6)));
        assertTrue(r.matches(d(2027, 2, 7)));
        assertTrue(r.matches(d(2027, 2, 8)));
        assertFalse(r.matches(d(2027, 2, 9)));
    }

    @Test
    public void resolvePicksHighestPriorityThenFirstListed() {
        SeasonPack.Season winter = SeasonPack.Season.of("winter", 0, SeasonRule.range(12, 21, 3, 19));
        SeasonPack.Season ny = SeasonPack.Season.of("ny", 10, SeasonRule.range(12, 31, 1, 2));
        SeasonPack.Season tie = SeasonPack.Season.of("tie", 10, SeasonRule.range(1, 1, 1, 1));
        SeasonPack.Season high = SeasonPack.Season.of("valentine", 11, SeasonRule.range(2, 14, 2, 14));
        SeasonPack pack = new SeasonPack(Arrays.asList(winter, ny, tie, high), Arrays.<String>asList());
        assertSame(winter, pack.resolve(d(2027, 1, 15)));
        assertSame(ny, pack.resolve(d(2027, 1, 1)));
        assertSame(high, pack.resolve(d(2027, 2, 14)));
        assertNull(pack.resolve(d(2027, 7, 1)));
    }

    @Test
    public void localizedNameFallbacks() {
        Map<String, String> n = new LinkedHashMap<>();
        n.put("de", "Weihnachten");
        n.put("es", "Navidad");
        n.put("es-419", "Navidad LA");
        n.put("en", "Christmas");
        assertEquals("Navidad LA", SeasonPack.localizedName(n, Locale.forLanguageTag("es-419")));
        assertEquals("Navidad", SeasonPack.localizedName(n, Locale.forLanguageTag("es-CO")));
        assertEquals("Christmas", SeasonPack.localizedName(n, Locale.forLanguageTag("fr")));
        n.remove("en");
        assertEquals("Weihnachten", SeasonPack.localizedName(n, Locale.forLanguageTag("fr")));
        assertEquals("", SeasonPack.localizedName(new LinkedHashMap<String, String>(), Locale.ENGLISH));
    }

    @Test
    public void folderNamesMustBeBare() {
        assertTrue(SeasonPack.validFolder("christmas"));
        assertTrue(SeasonPack.validFolder("new-year_2.x"));
        assertFalse(SeasonPack.validFolder(""));
        assertFalse(SeasonPack.validFolder(null));
        assertFalse(SeasonPack.validFolder(".hidden"));
        assertFalse(SeasonPack.validFolder(".."));
        assertFalse(SeasonPack.validFolder("a/b"));
        assertFalse(SeasonPack.validFolder("a\b"));
        assertFalse(SeasonPack.validFolder("a b"));
        assertFalse(SeasonPack.validFolder(String.format("%065d", 0)));
    }

    @Test
    public void seasonOfUsesFolderAsId() {
        SeasonPack.Season s = SeasonPack.Season.of("halloween", 3, SeasonRule.range(10, 1, 10, 31));
        assertEquals("halloween", s.id);
        assertEquals("halloween", s.folder);
        assertEquals("", s.skin);
    }
}
