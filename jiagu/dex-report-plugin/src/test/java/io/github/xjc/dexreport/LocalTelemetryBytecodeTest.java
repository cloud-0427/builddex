package io.github.xjc.dexreport;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class LocalTelemetryBytecodeTest {
    @Test public void generatedConfigurationWorksBeforePayloadAndHasNoDependencies() throws Exception {
        byte[] bytes = LocalTelemetryBytecode.generate(true, "example.app", 42, "1.2.3", "build-hash");
        Class<?> config = new ClassLoader(null) {
            Class<?> load() { return defineClass(null, bytes, 0, bytes.length); }
        }.load();
        assertEquals(true, invoke(config, "enabled"));
        assertEquals("example.app", invoke(config, "packageName"));
        assertEquals(42L, invoke(config, "versionCode"));
        assertEquals("1.2.3", invoke(config, "versionName"));
        assertEquals("build-hash", invoke(config, "buildId"));
    }
    private Object invoke(Class<?> type, String name) throws Exception {
        Method method = type.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(null);
    }
}
