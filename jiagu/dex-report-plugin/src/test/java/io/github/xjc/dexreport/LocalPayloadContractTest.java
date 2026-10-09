package io.github.xjc.dexreport;

import io.github.xjc.jiagu.local.LocalPayload;
import org.junit.Test;
import java.nio.*;
import java.util.*;
import static org.junit.Assert.*;

public class LocalPayloadContractTest {
    private byte[] container(int count) {
        ByteBuffer b = ByteBuffer.allocate(8 + count * 12 + count * 112);
        b.putInt(0x4a473400).putInt(count);
        for (int i = 0; i < count; i++) b.putInt(i * 112).putInt(112).putInt(112);
        for (int i = 0; i < count; i++) {
            byte[] dex = new byte[112]; ByteBuffer d = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
            d.put(new byte[]{'d','e','x','\n','0','3','9',0}); d.putInt(32, 112); d.putInt(36, 112); d.putInt(40, 0x12345678);
            b.put(dex);
        }
        return b.array();
    }
    @Test public void buildEncoderRoundTripsWithRuntimeDecoderAndRetainsMultipleDex() throws Exception {
        byte[] asset = CreateLocalPayloadTask.encode(container(12), "com.example.app", 24, "default");
        LocalPayload.Decoded d = LocalPayload.decode(asset, "com.example.app");
        assertEquals(12, d.dex.length); assertEquals(24, d.versionCode);
        for (ByteBuffer b : d.dex) { assertTrue(b.isDirect()); assertEquals(112, b.remaining()); }
    }
    @Test public void freshBuildsUseFreshEncryptionMaterials() throws Exception {
        byte[] a = CreateLocalPayloadTask.encode(container(1), "com.example.app", 1, "default");
        byte[] b = CreateLocalPayloadTask.encode(container(1), "com.example.app", 1, "default");
        assertFalse(Arrays.equals(a, b));
    }
    @Test public void tamperedCiphertextAndWrongPackageFailClosed() throws Exception {
        byte[] asset = CreateLocalPayloadTask.encode(container(1), "com.example.app", 1, "default");
        assertThrows(SecurityException.class, () -> LocalPayload.decode(asset, "com.other.app"));
        asset[asset.length - 1] ^= 1;
        SecurityException failure = assertThrows(SecurityException.class, () -> LocalPayload.decode(asset, "com.example.app"));
        assertEquals("LOCAL_PAYLOAD_AUTH_FAILED", failure.getMessage());
        assertTrue(failure.getCause() instanceof javax.crypto.AEADBadTagException);
    }
    @Test public void lengthOverflowAndOverlappingDexAreRejected() throws Exception {
        byte[] asset = CreateLocalPayloadTask.encode(container(1), "com.example.app", 1, "default");
        ByteBuffer.wrap(asset).putInt(8, Integer.MAX_VALUE);
        assertThrows(SecurityException.class, () -> LocalPayload.decode(asset, "com.example.app"));
        byte[] plain = container(2); ByteBuffer.wrap(plain).putInt(20, 0);
        byte[] bad = CreateLocalPayloadTask.encode(plain, "com.example.app", 1, "default");
        assertThrows(SecurityException.class, () -> LocalPayload.decode(bad, "com.example.app"));
    }
    @Test public void authenticatedConfigCannotBeSubstituted() throws Exception {
        byte[] asset = CreateLocalPayloadTask.encode(container(1), "com.example.app", 1, "default");
        String config = new String(asset, 16, ByteBuffer.wrap(asset).getInt(8), java.nio.charset.StandardCharsets.UTF_8);
        int p = config.indexOf("\"versionCode\":1") + "\"versionCode\":".length();
        asset[16 + p] = '2';
        SecurityException failure = assertThrows(SecurityException.class, () -> LocalPayload.decode(asset, "com.example.app"));
        assertTrue(failure.getCause() instanceof javax.crypto.AEADBadTagException);
    }
    private LocalPayload.Observer observer(List<String> events) {
        return (stage, status, code, duration) -> {
            assertTrue(duration >= 0);
            events.add(stage + ":" + status + ":" + code);
        };
    }
    @Test public void invalidHeaderReportsReadFailureWithoutPrematureSuccess() throws Exception {
        byte[] asset = CreateLocalPayloadTask.encode(container(1), "com.example.app", 1, "default");
        asset[0] = 0; List<String> events = new ArrayList<>();
        assertThrows(SecurityException.class, () -> LocalPayload.decode(asset, "com.example.app", observer(events)));
        assertEquals(Arrays.asList("LOCAL_PAYLOAD_READ:FAILED:LOCAL_PAYLOAD_FORMAT_UNSUPPORTED"), events);
    }
    @Test public void badTagReportsDecryptFailureAndDoesNotParseDex() throws Exception {
        byte[] asset = CreateLocalPayloadTask.encode(container(1), "com.example.app", 1, "default");
        asset[asset.length - 1] ^= 1; List<String> events = new ArrayList<>();
        assertThrows(SecurityException.class, () -> LocalPayload.decode(asset, "com.example.app", observer(events)));
        assertEquals(Arrays.asList("LOCAL_PAYLOAD_READ:SUCCEEDED:PAYLOAD_READ",
                "PAYLOAD_DECRYPT:FAILED:LOCAL_PAYLOAD_AUTH_FAILED"), events);
    }
    @Test public void authenticatedInvalidDexReportsParseFailureAfterVerifiedDecrypt() throws Exception {
        byte[] plain = container(1); plain[0] = 0;
        byte[] asset = CreateLocalPayloadTask.encode(plain, "com.example.app", 1, "default");
        List<String> events = new ArrayList<>();
        assertThrows(SecurityException.class, () -> LocalPayload.decode(asset, "com.example.app", observer(events)));
        assertEquals(Arrays.asList("LOCAL_PAYLOAD_READ:SUCCEEDED:PAYLOAD_READ",
                "PAYLOAD_DECRYPT:SUCCEEDED:PAYLOAD_VERIFIED", "BUSINESS_DEX_PARSE:FAILED:LOCAL_PAYLOAD_DEX_TABLE_INVALID"), events);
    }
    @Test public void observerFailureCannotReplaceDecodeSuccessOrOriginalFailure() throws Exception {
        byte[] asset = CreateLocalPayloadTask.encode(container(1), "com.example.app", 1, "default");
        LocalPayload.Observer broken = (stage, status, code, duration) -> { throw new LinkageError("probe"); };
        assertEquals(1, LocalPayload.decode(asset, "com.example.app", broken).dex.length);
        asset[asset.length - 1] ^= 1;
        SecurityException failure = assertThrows(SecurityException.class, () -> LocalPayload.decode(asset, "com.example.app", broken));
        assertEquals("LOCAL_PAYLOAD_AUTH_FAILED", failure.getMessage());
        assertTrue(failure.getCause() instanceof javax.crypto.AEADBadTagException);
    }
}
