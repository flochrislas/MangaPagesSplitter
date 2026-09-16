package mangapagessplitter;

import java.util.Comparator;

/**
 * "Natural" string order: runs of digits compare by numeric value, everything else
 * case-insensitively, so {@code 2.png} sorts before {@code 10.png} and
 * {@code Page-2} before {@code page-10}. Ties are broken by plain string order so the
 * result is a total order.
 */
public final class NaturalOrder implements Comparator<String> {

    public static final NaturalOrder INSTANCE = new NaturalOrder();

    private NaturalOrder() {}

    @Override
    public int compare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = stripLeadingZeros(a.substring(si, i));
                String nb = stripLeadingZeros(b.substring(sj, j));
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        int c = (a.length() - i) - (b.length() - j);
        return c != 0 ? c : a.compareTo(b);
    }

    private static String stripLeadingZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') k++;
        return digits.substring(k);
    }
}
