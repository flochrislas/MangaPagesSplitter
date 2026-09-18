package mangapagessplitter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchOptionsTest {

    private static BatchOptions.Builder valid() {
        return BatchOptions.builder().rootFolder("/some/folder");
    }

    @Test
    void defaultsMatchAFreshUi() {
        BatchOptions o = valid().build();
        assertEquals(SplitMode.WIDE_ONLY, o.splitMode());
        assertEquals(OutputFormat.CBZ, o.outputFormat());
        assertTrue(o.japaneseManga());
        assertFalse(o.smartAutoCrop());
        assertEquals(5, o.smartAutoCropSensitivity());
        assertFalse(o.deleteOriginals());
    }

    @Test
    void blankRootFolderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BatchOptions.builder().rootFolder("  ").build());
    }

    @Test
    void negativeCountsAndMarginsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> valid().skipImagesFromStart(-1).build());
        assertThrows(IllegalArgumentException.class, () -> valid().skipImagesFromEnd(-1).build());
        assertThrows(IllegalArgumentException.class, () -> valid().cropLeft(-1).build());
        assertThrows(IllegalArgumentException.class, () -> valid().cropBottom(-1).build());
    }

    @Test
    void sensitivityMustBeWithinRange() {
        assertThrows(IllegalArgumentException.class, () -> valid().smartAutoCropSensitivity(0).build());
        assertThrows(IllegalArgumentException.class, () -> valid().smartAutoCropSensitivity(11).build());
        assertEquals(10, valid().smartAutoCropSensitivity(10).build().smartAutoCropSensitivity());
    }

    @Test
    void smartAutoCropZeroesManualMargins() {
        BatchOptions o = valid().crop(5, 6, 7, 8).smartAutoCrop(true).build();
        assertEquals(0, o.cropLeft());
        assertEquals(0, o.cropRight());
        assertEquals(0, o.cropTop());
        assertEquals(0, o.cropBottom());
    }

    @Test
    void manualMarginsSurviveWhenSmartAutoCropIsOff() {
        BatchOptions o = valid().crop(5, 6, 7, 8).build();
        assertEquals(5, o.cropLeft());
        assertEquals(8, o.cropBottom());
    }

    @Test
    void blankCustomTitleDisablesTheOption() {
        BatchOptions o = valid().useCustomTitle(true).customTitle("   ").build();
        assertFalse(o.useCustomTitle());
        assertEquals("", o.customTitle());
    }

    @Test
    void customTitleIsTrimmedAndOnlyKeptWhenEnabled() {
        assertEquals("Vol", valid().useCustomTitle(true).customTitle("  Vol ").build().customTitle());
        assertEquals("", valid().useCustomTitle(false).customTitle("Vol").build().customTitle());
    }

    @Test
    void outputFormatHelpers() {
        assertEquals("cbz", OutputFormat.CBZ.extension());
        assertEquals("", OutputFormat.FOLDER.extension());
        assertFalse(OutputFormat.FOLDER.isArchive());
        assertTrue(OutputFormat.CBR.needsRar());
        assertFalse(OutputFormat.ZIP.needsRar());
        assertSame(OutputFormat.CBZ, OutputFormat.CBR.fallbackWithoutRar());
        assertSame(OutputFormat.ZIP, OutputFormat.RAR.fallbackWithoutRar());
        assertSame(OutputFormat.FOLDER, OutputFormat.FOLDER.fallbackWithoutRar());
    }

    @Test
    void splitModeSplits() {
        assertTrue(SplitMode.WIDE_ONLY.splits());
        assertTrue(SplitMode.ALL.splits());
        assertFalse(SplitMode.NEVER.splits());
    }
}
