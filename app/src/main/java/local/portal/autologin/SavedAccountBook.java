package local.portal.autologin;

import java.io.StringReader;
import java.io.StringWriter;
import java.io.IOException;
import java.util.*;

/** Account history data only; the Android store keeps it in private preferences. */
final class SavedAccountBook {
    static final class Account {
        final String username, password, label;
        final boolean bundled;
        Account(String username, String password, String label) {
            this(username,password,label,false);
        }
        Account(String username, String password, String label,boolean bundled) {
            this.username=username.trim();this.password=password;this.label=label==null?"":label;
            this.bundled=bundled;
        }
        String displayName() { return label.isEmpty()?username:label; }
        @Override public String toString() { return "SavedAccount(<redacted>)"; }
    }
    private final ArrayList<Account> accounts=new ArrayList<>();
    private final LinkedHashSet<String> importedPresets=new LinkedHashSet<>();
    private boolean migrated;

    List<Account> getAccounts() { return Collections.unmodifiableList(new ArrayList<>(accounts)); }
    SavedAccountBook copy() {
        SavedAccountBook b=new SavedAccountBook();b.accounts.addAll(accounts);
        b.importedPresets.addAll(importedPresets);b.migrated=migrated;return b;
    }
    boolean migrateActive(String username,String password) {
        if(migrated)return false;
        migrated=true;
        if(valid(username,password))remember(username,password);
        return true;
    }
    void remember(String username,String password) {
        if(!valid(username,password))throw new IllegalArgumentException("invalid_account");
        username=username.trim();String label="";boolean bundled=false;
        for(Account a:accounts)if(a.username.equals(username)){label=a.label;bundled=a.bundled;break;}
        final String key=username;accounts.removeIf(a->a.username.equals(key));
        accounts.add(0,new Account(username,password,label,bundled));
    }
    boolean seed(List<Account> presets) {
        boolean changed=false;
        for(Account p:presets) {
            if(!valid(p.username,p.password))continue;
            if(importedPresets.add(p.username))changed=true;
            int index=-1;
            for(int i=0;i<accounts.size();i++)if(accounts.get(i).username.equals(p.username)){index=i;break;}
            if(index<0){accounts.add(new Account(p.username,p.password,p.label,true));changed=true;}
            else {
                Account existing=accounts.get(index);
                // Preserve a saved password while adding the preset's friendly display name.
                String label=existing.label.isEmpty()?p.label:existing.label;
                if(!existing.bundled || !existing.label.equals(label)) {
                    accounts.set(index,new Account(existing.username,existing.password,label,true));changed=true;
                }
            }
        }
        return changed;
    }
    boolean remove(String username) { return accounts.removeIf(a->!a.bundled && a.username.equals(username.trim())); }
    void clear() { accounts.removeIf(a->!a.bundled);migrated=true; }
    private static boolean valid(String user,String pass) {
        return user!=null && !user.trim().isEmpty() && pass!=null && !pass.isEmpty();
    }
    String encode() {
        Properties p=new Properties();p.setProperty("version","1");
        p.setProperty("migrated",Boolean.toString(migrated));p.setProperty("count",Integer.toString(accounts.size()));
        for(int i=0;i<accounts.size();i++) {
            Account a=accounts.get(i);String prefix="account."+i+".";
            p.setProperty(prefix+"username",a.username);p.setProperty(prefix+"password",a.password);p.setProperty(prefix+"label",a.label);
            p.setProperty(prefix+"bundled",Boolean.toString(a.bundled));
        }
        p.setProperty("preset.count",Integer.toString(importedPresets.size()));int index=0;
        for(String id:importedPresets)p.setProperty("preset."+(index++),id);
        try {StringWriter out=new StringWriter();p.store(out,null);return out.toString();}
        catch(IOException impossible){throw new IllegalStateException("account_encoding_failed");}
    }
    static SavedAccountBook decode(String data) {
        SavedAccountBook b=new SavedAccountBook();if(data==null || data.isEmpty())return b;
        try {
            Properties p=new Properties();p.load(new StringReader(data));
            if(!"1".equals(p.getProperty("version")))return b;
            int count=Integer.parseInt(p.getProperty("count","0"));
            int presets=Integer.parseInt(p.getProperty("preset.count","0"));
            if(count<0 || count>10000 || presets<0 || presets>10000)return b;
            b.migrated=Boolean.parseBoolean(p.getProperty("migrated","false"));
            for(int i=0;i<presets;i++){String id=p.getProperty("preset."+i);if(id!=null)b.importedPresets.add(id);}
            HashSet<String> seen=new HashSet<>();
            for(int i=0;i<count;i++) {
                String prefix="account."+i+".",user=p.getProperty(prefix+"username"),pass=p.getProperty(prefix+"password");
                if(valid(user,pass) && seen.add(user.trim()))b.accounts.add(new Account(user,pass,p.getProperty(prefix+"label",""),
                    Boolean.parseBoolean(p.getProperty(prefix+"bundled",Boolean.toString(b.importedPresets.contains(user.trim()))))));
            }
        } catch(IOException|IllegalArgumentException ignored) { return new SavedAccountBook(); }
        return b;
    }
}
