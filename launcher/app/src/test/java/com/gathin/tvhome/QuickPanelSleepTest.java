package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QuickPanelSleepTest {
    @Test
    public void theSleepTileStepsThroughTheLengthsThenOff() {
        assertEquals(15, QuickSettingsActivity.nextSleepMinutes(0));
        assertEquals(15, QuickSettingsActivity.nextSleepMinutes(14));
        assertEquals(60, QuickSettingsActivity.nextSleepMinutes(45));   // a running timer moves to the next length
        assertEquals(0, QuickSettingsActivity.nextSleepMinutes(120));
    }
}
