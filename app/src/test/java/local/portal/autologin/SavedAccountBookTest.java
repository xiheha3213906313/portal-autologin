package local.portal.autologin;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SavedAccountBookTest {
    private SavedAccountBook.Account preset(String user,String pass,String label) {
        return new SavedAccountBook.Account(user,pass,label);
    }
    @Test public void activeAccountMigratesOnlyOnceAndDeletionSurvivesRestart() {
        SavedAccountBook b=new SavedAccountBook();
        assertTrue(b.migrateActive("existing","old-password"));
        b.remove("existing");b=SavedAccountBook.decode(b.encode());
        assertFalse(b.migrateActive("existing","old-password"));assertTrue(b.getAccounts().isEmpty());
    }
    @Test public void presetDisplaysAliasAndSelectionRetainsRealCredentials() {
        SavedAccountBook b=new SavedAccountBook();
        b.seed(Arrays.asList(preset("first","secret-1","别名甲"),preset("second","secret-2","别名乙")));
        assertEquals("别名甲",b.getAccounts().get(0).displayName());
        assertEquals("second",b.getAccounts().get(1).username);
        assertEquals("secret-2",b.getAccounts().get(1).password);
    }
    @Test public void updatingSameAccountPreservesAliasAndMovesItToFront() {
        SavedAccountBook b=new SavedAccountBook();
        b.seed(Arrays.asList(preset("first","old","别名甲"),preset("second","other","别名乙")));
        b.remember("second","updated");
        assertEquals(2,b.getAccounts().size());assertEquals("second",b.getAccounts().get(0).username);
        assertEquals("updated",b.getAccounts().get(0).password);assertEquals("别名乙",b.getAccounts().get(0).displayName());
        assertTrue(b.getAccounts().get(0).bundled);
    }
    @Test public void addingPresetNeverOverwritesSavedPassword() {
        SavedAccountBook b=new SavedAccountBook();b.remember("first","user-changed");
        b.seed(Collections.singletonList(preset("first","default","别名甲")));
        assertEquals("user-changed",b.getAccounts().get(0).password);
        assertEquals("别名甲",b.getAccounts().get(0).displayName());
    }
    @Test public void presetCannotBeDeletedAcrossRestartAndNewPresetCanBeImported() {
        SavedAccountBook.Account a=preset("first","one","别名甲"),c=preset("third","three","别名丙");
        SavedAccountBook b=new SavedAccountBook();b.seed(Collections.singletonList(a));assertFalse(b.remove("first"));
        b=SavedAccountBook.decode(b.encode());b.seed(Arrays.asList(a,c));
        assertEquals(2,b.getAccounts().size());assertEquals("first",b.getAccounts().get(0).username);
        assertFalse(b.remove("first"));
    }
    @Test public void bulkClearRemovesCustomAccountsAndPreservesPresets() {
        List<SavedAccountBook.Account> defaults=Collections.singletonList(preset("first","one","别名甲"));
        SavedAccountBook b=new SavedAccountBook();b.seed(defaults);b.remember("custom","custom-password");b.clear();
        b=SavedAccountBook.decode(b.encode());assertFalse(b.seed(defaults));assertEquals(1,b.getAccounts().size());
        assertTrue(b.getAccounts().get(0).bundled);assertEquals("first",b.getAccounts().get(0).username);
    }
    @Test public void storageRoundTripPreservesUnicodeWhitespaceAndSpecialCharacters() {
        SavedAccountBook b=new SavedAccountBook();b.remember("  user:=甲  "," a=:+&密\\\n ");
        b=SavedAccountBook.decode(b.encode());
        assertEquals("user:=甲",b.getAccounts().get(0).username);
        assertEquals(" a=:+&密\\\n ",b.getAccounts().get(0).password);
        assertEquals("user:=甲",b.getAccounts().get(0).displayName());
        assertEquals("SavedAccount(<redacted>)",b.getAccounts().get(0).toString());
    }
    @Test public void corruptHistoryDoesNotCrash() {
        assertTrue(SavedAccountBook.decode("version=1\ncount=oops").getAccounts().isEmpty());
        assertTrue(SavedAccountBook.decode("version=1\ncount=-1").getAccounts().isEmpty());
        assertTrue(SavedAccountBook.decode("version=99").getAccounts().isEmpty());
    }
    @Test public void legacyPresetFlagsAreMigratedWithoutChangingCredentials() {
        String legacy="version=1\nmigrated=true\ncount=1\naccount.0.username=first\naccount.0.password=saved-password\npreset.count=1\npreset.0=first\n";
        SavedAccountBook b=SavedAccountBook.decode(legacy);
        assertTrue(b.getAccounts().get(0).bundled);assertFalse(b.remove("first"));
        assertEquals("saved-password",b.getAccounts().get(0).password);
    }
    @Test public void previouslyDeletedPresetsAreRestoredByNewLockingPolicy() {
        SavedAccountBook b=SavedAccountBook.decode("version=1\nmigrated=true\ncount=0\npreset.count=1\npreset.0=first\n");
        assertTrue(b.seed(Collections.singletonList(preset("first","default","别名甲"))));
        assertEquals(1,b.getAccounts().size());assertTrue(b.getAccounts().get(0).bundled);
    }
}
