package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class WifiNameMatcherTest {
    @Test public void emptyFilterAcceptsAnyNetworkWithoutKnowingItsName() {
        for(String filter:new String[]{null,"","  ","　"}) {
            assertTrue(WifiNameMatcher.validFilter(filter));
            assertTrue(WifiNameMatcher.matches(filter,null));
            assertTrue(WifiNameMatcher.matches(filter,"<unknown ssid>"));
        }
    }
    @Test public void ignoresCaseWhitespaceAndPunctuation() {
        assertTrue(WifiNameMatcher.matches("Inspire Creativity-Guest","\"inspire_creativity.guest\""));
        assertTrue(WifiNameMatcher.matches("Guest-(WiFi)","guest WiFi"));
    }
    @Test public void requiresCompleteNameInsteadOfSubstring() {
        assertFalse(WifiNameMatcher.matches("Guest1","Guest10"));
        assertFalse(WifiNameMatcher.matches("Guest","Office-Guest"));
    }
    @Test public void preservesChineseDigitsAndNormalizesFullWidthCharacters() {
        assertTrue(WifiNameMatcher.matches("访客ＷｉＦｉ－１","访客wifi_1"));
        assertFalse(WifiNameMatcher.matches("访客wifi1","访客wifi2"));
    }
    @Test public void namedFilterFailsClosedWhenSsidIsUnavailable() {
        assertFalse(WifiNameMatcher.matches("Guest",null));
        assertFalse(WifiNameMatcher.matches("unknownssid","\"<unknown ssid>\""));
        assertFalse(WifiNameMatcher.matches("Guest",""));
    }
    @Test public void punctuationOnlyIsNotTreatedAsAllNetworks() {
        for(String filter:new String[]{"---","._()","！！"}) {
            assertFalse(WifiNameMatcher.validFilter(filter));
            assertFalse(WifiNameMatcher.matches(filter,"Guest"));
        }
    }
    @Test public void accentedNamesKeepMeaningfulLetters() {
        assertTrue(WifiNameMatcher.matches("CAFÉ","cafe\u0301"));
        assertFalse(WifiNameMatcher.matches("Cafe","Café"));
    }
}
