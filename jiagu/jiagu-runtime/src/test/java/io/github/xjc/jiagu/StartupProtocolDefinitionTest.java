package io.github.xjc.jiagu;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public final class StartupProtocolDefinitionTest {
    @Test public void nativeStageIdsStatusValuesAndResultNamesMatchJava() throws Exception {
        String header = new String(Files.readAllBytes(Paths.get("src/main/cpp/startup-events.h")), java.nio.charset.StandardCharsets.UTF_8);
        for (JiaguStartupEvent.Stage stage : JiaguStartupEvent.Stage.values()) {
            assertTrue(stage.name(), Pattern.compile("\\b" + stage.name() + "\\s*=\\s*" + stage.getId() + "\\b").matcher(header).find());
            assertSame(stage, JiaguStartupEvent.Stage.fromId(stage.getId()));
        }
        for (JiaguStartupEvent.Status status : JiaguStartupEvent.Status.values()) {
            assertTrue(status.name(), Pattern.compile("\\b" + status.name() + "\\s*=\\s*" + status.ordinal() + "\\b").matcher(header).find());
        }
        java.util.regex.Matcher results = Pattern.compile("case StartupResultCode::(\\w+): return \"(\\w+)\";").matcher(header);
        int count = 0;
        while (results.find()) {
            assertEquals(StartupResultCode.valueOf(results.group(1)).getWireName(), results.group(2));
            count++;
        }
        assertTrue(count > 0);
    }
}
