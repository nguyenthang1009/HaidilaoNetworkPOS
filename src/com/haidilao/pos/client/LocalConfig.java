package com.haidilao.pos.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Cấu hình riêng của từng máy (vd. Tablet này là bàn nào), lưu trong file
 * %USERPROFILE%/.lan-table-ordering/config.properties để lần sau mở lên không phải chọn lại.
 */
public final class LocalConfig {
    public static final String TABLET_TABLE = "tablet.table";

    private static final File FILE = new File(System.getProperty("user.home"), ".lan-table-ordering" + File.separator + "config.properties");

    private LocalConfig() {}

    public static synchronized String get(String key, String def) {
        return load().getProperty(key, def);
    }

    public static synchronized void set(String key, String value) {
        Properties p = load();
        p.setProperty(key, value);
        FILE.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
            p.store(w, "LAN Table Ordering - cấu hình máy này");
        } catch (IOException e) {
            System.err.println("[LocalConfig] Không lưu được cấu hình: " + e.getMessage());
        }
    }

    private static Properties load() {
        Properties p = new Properties();
        if (FILE.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) {}
        }
        return p;
    }
}
