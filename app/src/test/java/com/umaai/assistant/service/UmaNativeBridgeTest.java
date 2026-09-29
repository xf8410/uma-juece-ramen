package com.umaai.assistant.service;
import org.junit.Test;
import static org.junit.Assert.*;
public class UmaNativeBridgeTest {
    @Test public void missingNativeLibraryIsReportedWithoutCloudFallback() {
        assertFalse(UmaNativeBridge.isLoaded());
        assertEquals(8192,UmaNativeBridge.DEFAULT_SEARCH_N);
    }
    @Test public void bridgeDeclaresFileBasedSnapshotAndCancellationContract() throws Exception {
        assertNotNull(UmaNativeBridge.class.getMethod("nativeEvaluate",String.class,String.class,String.class,UmaNativeBridge.NativeEventListener.class));
        assertNotNull(UmaNativeBridge.class.getMethod("nativeCancel",String.class));
    }
}
