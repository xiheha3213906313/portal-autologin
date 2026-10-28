package local.portal.autologin;

import java.util.Locale;

/** Supported OEM family for the Xiaomi browser host and LSPosed integration. No extra permissions. */
final class DeviceCompatibility {
    static boolean supportsModule(String brand, String manufacturer) {
        return xiaomiFamily(brand) || xiaomiFamily(manufacturer);
    }

    private static boolean xiaomiFamily(String value) {
        if (value == null) return false;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "xiaomi": case "redmi": case "poco": return true;
            default: return false;
        }
    }
}
