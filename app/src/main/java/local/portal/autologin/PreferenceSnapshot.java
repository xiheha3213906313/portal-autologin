package local.portal.autologin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** An atomic, app-private snapshot outside LSPosed's redirected preferences directory. */
final class PreferenceSnapshot {
    static Map<String,Object> read(File file) throws IOException {
        if (file.length() > 8 * 1024 * 1024) throw new IOException("preferences_size");
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) { p.load(in); }
        if (!"1".equals(p.getProperty("format"))) throw new IOException("preferences_format");
        Map<String,Object> result = new HashMap<>();
        try {
            for (String key : p.stringPropertyNames()) {
                if (!key.endsWith(".type")) continue;
                String prefix = key.substring(0, key.length()-5);
                String name = new String(Base64.getUrlDecoder().decode(prefix), StandardCharsets.UTF_8);
                String value = p.getProperty(prefix+".value");
                Object decoded;
                switch (p.getProperty(key)) {
                    case "s": decoded = Objects.requireNonNull(value); break;
                    case "b":
                        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException();
                        decoded = Boolean.valueOf(value); break;
                    case "i": decoded = Integer.valueOf(value); break;
                    case "l": decoded = Long.valueOf(value); break;
                    case "f": decoded = Float.valueOf(value); break;
                    case "set":
                        int count = Integer.parseInt(value);
                        if (count < 0 || count > 10000) throw new IllegalArgumentException();
                        Set<String> set = new HashSet<>();
                        for (int i=0;i<count;i++) set.add(Objects.requireNonNull(p.getProperty(prefix+".item."+i)));
                        decoded = set; break;
                    default: throw new IllegalArgumentException();
                }
                result.put(name, decoded);
            }
        } catch (RuntimeException e) { throw new IOException("preferences_format"); }
        return result;
    }

    static void write(File file, Map<String,?> values) throws IOException {
        Properties p = new Properties(); p.setProperty("format", "1");
        for (Map.Entry<String,?> entry : values.entrySet()) {
            String prefix = Base64.getUrlEncoder().withoutPadding().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
            Object value = entry.getValue(); String type;
            if (value instanceof String) type="s";
            else if (value instanceof Boolean) type="b";
            else if (value instanceof Integer) type="i";
            else if (value instanceof Long) type="l";
            else if (value instanceof Float) type="f";
            else if (value instanceof Set) {
                type="set"; int i=0;
                for (Object item : (Set<?>)value) {
                    if (!(item instanceof String)) throw new IOException("preferences_type");
                    p.setProperty(prefix+".item."+(i++),(String)item);
                }
                value=i;
            } else throw new IOException("preferences_type");
            p.setProperty(prefix+".type",type); p.setProperty(prefix+".value",value.toString());
        }
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("preferences_directory");
        File part = File.createTempFile("snapshot-", ".tmp", parent);
        try {
            try (FileOutputStream out = new FileOutputStream(part)) { p.store(out,null); out.getFD().sync(); }
            Files.move(part.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { part.delete(); }
    }
}
