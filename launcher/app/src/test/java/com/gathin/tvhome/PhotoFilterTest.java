package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PhotoFilterTest {

    private static final List<String> AUTUMN = Arrays.asList("autumn forest red leaves", "fall foliage orange trees",
            "autumn lake reflection", "fallen leaves path", "misty autumn morning");
    private static final List<String> HALLOWEEN = Arrays.asList("halloween pumpkins night", "haunted forest fog",
            "jack o lantern", "full moon spooky");

    @Test
    public void autumnKeepsOutChristmasAndOtherSeasons() {
        List<String> out = PhotoSets.excludeFor(AUTUMN);
        assertTrue(OnlinePhotos.excluded("Christmas tree with red ornaments", out));
        assertTrue(OnlinePhotos.excluded("withering trees near snow-covered mountains", out));
        assertTrue(OnlinePhotos.excluded("cherry blossom in spring", out));
        assertFalse(OnlinePhotos.excluded("orange and red autumn leaves on a lake", out));
    }

    @Test
    public void aHolidayKeepsItsOwnWordsAndTheSeasons() {
        List<String> out = PhotoSets.excludeFor(HALLOWEEN);
        assertFalse(OnlinePhotos.excluded("carved pumpkin among autumn leaves", out));
        assertTrue(OnlinePhotos.excluded("santa claus figurine", out));
    }

    @Test
    public void wordsMatchWholeNotInsideOthers() {
        List<String> out = PhotoSets.excludeFor(AUTUMN);
        assertFalse(OnlinePhotos.excluded("a nice ICEland waterfall", out));   // "ice" is not "iceland"
        assertTrue(OnlinePhotos.excluded("thin ice on the pond", out));
    }
}
