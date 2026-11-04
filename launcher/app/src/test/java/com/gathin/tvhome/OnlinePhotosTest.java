package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OnlinePhotosTest {

    @Test
    public void luminanceRanksDarkBelowLight() {
        assertEquals(0, OnlinePhotos.luminance("#000000"), 1e-9);
        assertEquals(1, OnlinePhotos.luminance("#FFFFFF"), 1e-9);
        assertTrue(OnlinePhotos.luminance("#0C1A26") < 0.72);   // a night photo stays in front
        assertTrue(OnlinePhotos.luminance("#F2EEE6") > 0.72);   // a bright one goes to the back
        assertEquals(0, OnlinePhotos.luminance("null"), 1e-9);
    }

    @Test
    public void setNamesAreBareFolderNames() {
        assertEquals("saver-theme-new-year-night", OnlinePhotos.setName("saver-theme-new-year-night"));
        assertEquals("a_b_c", OnlinePhotos.setName("a/b c"));
        assertEquals("_.._x", OnlinePhotos.setName("/../x"));
    }
}
