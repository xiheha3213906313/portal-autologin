package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceCompatibilityTest {
    @Test public void recognizesXiaomiBrandAndManufacturer() {
        assertTrue(DeviceCompatibility.supportsModule("Xiaomi","unknown"));
        assertTrue(DeviceCompatibility.supportsModule("unknown"," Xiaomi "));
        assertTrue(DeviceCompatibility.supportsModule("xiaomi","XIAOMI"));
    }
    @Test public void includesRedmiAndPoco() {
        assertTrue(DeviceCompatibility.supportsModule("Redmi","Xiaomi"));
        assertTrue(DeviceCompatibility.supportsModule("POCO","unknown"));
        assertTrue(DeviceCompatibility.supportsModule(null,"redmi"));
        assertTrue(DeviceCompatibility.supportsModule("poco",null));
    }
    @Test public void excludesOtherAndroidManufacturers() {
        for(String brand:new String[]{"HUAWEI","HONOR","OPPO","vivo","realme","OnePlus",
                "samsung","google","meizu","nubia","ZTE","lenovo","motorola"})
            assertFalse(brand,DeviceCompatibility.supportsModule(brand,brand));
    }
    @Test public void rejectsMissingAndPartialNames() {
        for(String brand:new String[]{null,"","unknown","mi","notxiaomi","xiaomi-like","redmagic"})
            assertFalse(DeviceCompatibility.supportsModule(brand,null));
    }
}
