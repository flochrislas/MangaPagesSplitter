package mangapagessplitter;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalOrderTest {

    private static List<String> sorted(String... in) {
        List<String> l = Arrays.asList(in);
        l.sort(NaturalOrder.INSTANCE);
        return l;
    }

    @Test
    void numbersCompareByValue() {
        assertEquals(Arrays.asList("1.png", "2.png", "10.png", "100.png"),
                sorted("10.png", "1.png", "100.png", "2.png"));
    }

    @Test
    void leadingZerosDoNotChangeTheValueOrder() {
        assertEquals(Arrays.asList("001.png", "2.png", "010.png"),
                sorted("010.png", "2.png", "001.png"));
    }

    @Test
    void textIsCaseInsensitive() {
        assertEquals(Arrays.asList("Page-1.jpg", "page-2.jpg", "Page-10.jpg"),
                sorted("Page-10.jpg", "page-2.jpg", "Page-1.jpg"));
    }

    @Test
    void mixedSegmentsCompareLeftToRight() {
        assertEquals(Arrays.asList("ch1/p2.png", "ch1/p10.png", "ch2/p1.png"),
                sorted("ch2/p1.png", "ch1/p10.png", "ch1/p2.png"));
    }

    @Test
    void equalValuesFallBackToPlainOrderSoTheOrderIsTotal() {
        assertTrue(NaturalOrder.INSTANCE.compare("01.png", "1.png") != 0);
        assertEquals(0, NaturalOrder.INSTANCE.compare("a.png", "a.png"));
    }
}
