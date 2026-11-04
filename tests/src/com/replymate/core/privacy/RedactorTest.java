package com.replymate.core.privacy;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/** P21-production §6: the Diagnostics screen/Copy pipeline must hand the owner a
 *  report they can paste anywhere — no keys, no contact names, no phone numbers
 *  or chat ids. These pins cover the real shapes from the assistant ledger
 *  ("learned · Amara · …") and the layering with Secrets. */
public class RedactorTest {

    private static Set<String> names(String... ns) {
        Set<String> s = new HashSet<String>();
        for (String n : ns) s.add(n);
        return s;
    }

    @Test public void contactNamesAreMaskedWholeWordCaseInsensitive() {
        String out = Redactor.redactForSharing(
            "learned · Amara · prefers pidgin\nNOTIFY #3 for Amara refreshed silently",
            names("Amara"));
        assertFalse(out.contains("Amara"));
        assertTrue(out.contains("[name]"));
        assertTrue(out.contains("learned"));
        assertTrue(out.contains("refreshed silently"));
    }

    @Test public void fullNamesMaskBeforeTheirFragments() {
        String out = Redactor.redactForSharing(
            "contact Amara Speaker pinged; Amara said thanks; Speaker corner",
            names("Amara Speaker"));
        assertFalse("the full name is gone", out.contains("Amara Speaker"));
        assertFalse("individual fragments are gone too", out.contains("Amara"));
        assertFalse(out.contains("Speaker"));
    }

    @Test public void wordsThatMerelyContainANameSurvive() {
        // word-boundary: "Amarah"/"Amaras" are not the contact "Amara".
        String out = Redactor.redactForSharing("Amaras tip jar", names("Amara"));
        assertTrue(out.contains("Amaras"));
    }

    @Test public void shortNamesAreIgnoredSoEnglishSurvives() {
        String out = Redactor.redactForSharing(
            "am checking the plan at 21:03", names("Am", "Al"));
        assertEquals("am checking the plan at 21:03", out);
    }

    @Test public void longDigitRunsAreMaskedAsNums() {
        String out = Redactor.redactForSharing(
            "chat 234801234567 grew; id 10606301543 uploaded; recv 12 parsed 9",
            names("Amara"));
        assertFalse(out.contains("234801234567"));
        assertFalse(out.contains("10606301543"));
        assertTrue(out.contains("[num]"));
        assertTrue("small counters stay readable", out.contains("recv 12 parsed 9"));
    }

    @Test public void secretsAreMaskedAsBefore() {
        String out = Redactor.redactForSharing(
            "key=AIzaSyAuditOnly0123456789abcd failed; Bearer sk-abcdef0123456789abcdef",
            names("Amara"));
        assertFalse(out.contains("AIzaSyAuditOnly0123456789abcd"));
        assertFalse(out.contains("sk-abcdef0123456789abcdef"));
    }

    @Test public void nullAndEmptyNeverThrow() {
        assertEquals("", Redactor.redactForSharing(null, names("Amara")));
        assertEquals("", Redactor.redactForSharing("", names("Amara")));
        assertEquals("plain", Redactor.redactForSharing("plain", null));
    }

    @Test public void namesUnderThreeLettersAndBlankNamesAreSkipped() {
        assertEquals("Al phoned", Redactor.redactForSharing("Al phoned", names("Al", "  ")));
    }

    @Test public void redactionSurvivesRealLedgerLinePunctuation() {
        String out = Redactor.redactForSharing(
            "Nov 4, 21:03 · GENERATE · Amara·ok · reply drafted",
            names("Amara"));
        assertFalse(out.contains("Amara"));
        assertTrue(out.contains("GENERATE"));
    }
}
