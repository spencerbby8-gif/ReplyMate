package com.replymate.data;

import com.replymate.core.listener.WatchedApps;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P3-repair regression guard: the Android 11+ package-visibility fix lives in the
 *  manifest (<queries>), while the app catalog lives in code (WatchedApps). These two
 *  MUST stay in sync or on-device detection silently lies again ("WhatsApp not
 *  installed" bug). This static guard binds them together — new provider = one
 *  catalog line + one queries line, enforced. */
public class ManifestCatalogTest {

    private static String manifest() throws Exception {
        String src = System.getProperty("replymate.src", "/home/user/ReplyMate/src");
        File f = new File(new File(src).getParentFile(), "AndroidManifest.xml");
        assertTrue("manifest not next to src/: " + f.getAbsolutePath(), f.isFile());
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    /** Package names inside the <queries>…</queries> block only. */
    private static List<String> queriesPackages(String xml) {
        int start = xml.indexOf("<queries>");
        int end = xml.indexOf("</queries>");
        assertTrue("manifest must declare a <queries> block (package visibility fix)",
            start >= 0 && end > start);
        String block = xml.substring(start, end);
        List<String> out = new ArrayList<String>();
        for (String line : block.split("\\n")) {
            int i = line.indexOf("android:name=\"");
            if (i < 0) continue;
            int j = line.indexOf('"', i + "android:name=\"".length());
            out.add(line.substring(i + "android:name=\"".length(), j));
        }
        return out;
    }

    @Test public void everyCatalogPackageIsVisibleToPackageManager() throws Exception {
        List<String> visible = queriesPackages(manifest());
        for (WatchedApps.AppDef def : WatchedApps.all()) {
            assertTrue(def.packageName + " (catalog) missing from manifest <queries> — "
                + "it will report as 'not installed' on Android 11+",
                visible.contains(def.packageName));
        }
    }

    @Test public void queriesBlockHasNoStrayEntries() throws Exception {
        List<String> visible = queriesPackages(manifest());
        List<String> allowed = new ArrayList<String>();
        for (WatchedApps.AppDef def : WatchedApps.all()) allowed.add(def.packageName);
        allowed.add("com.android.vending");      // official store page for missing apps
        for (String p : visible) {
            assertTrue("queries entry not in the watched catalog (remove it or add the provider): " + p,
                allowed.contains(p));
        }
        assertEquals("exactly the catalog + vending, each once",
            allowed.size(), visible.size());
    }

    @Test public void permissionsStayMinimal() throws Exception {
        String xml = manifest();
        assertTrue(xml.contains("android.permission.INTERNET"));
        assertTrue(xml.contains("android.permission.POST_NOTIFICATIONS"));
        // Anything beyond these two needs an explicit owner decision.
        assertFalse(xml.contains("android.permission.QUERY_ALL_PACKAGES"));
        assertFalse(xml.contains("android.permission.READ_CONTACTS"));
        assertFalse(xml.contains("android.permission.ACCESS_FINE_LOCATION"));
        assertFalse(xml.contains(".accessibilityservice.AccessibilityService"));
    }

    @Test public void allUiScreensAndListenerAreRegistered() throws Exception {
        String xml = manifest();
        for (String cls : new String[] {
                ".ui.HomeActivity", ".ui.SettingsActivity", ".ui.ProfileActivity",
                ".ui.VoiceActivity",
                ".ui.ProviderActivity", ".ui.ProviderEditActivity",
                ".ui.ContactEditActivity", ".ui.ConversationActivity",
                ".ui.SourcesActivity", ".ui.UsageActivity", ".ui.PromptAuditActivity"}) {
            assertTrue("activity missing from manifest: " + cls, xml.contains(cls));
        }
        assertTrue(xml.contains(".listener.RmNotificationListener"));
        assertTrue(xml.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"));
        assertTrue(xml.contains(".ReplyMateApp"));
    }
}
