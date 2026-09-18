package com.umaai.assistant.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * UmaNativeBridge 的 JVM 级契约（无需 Android 环境与真实 .so）。
 *
 * 历史注：曾有 {@code stateForNative()} 在 Java 侧剥离 acquisition_gauges；
 * v0.3.2 起改为整包透传给 Rust、由 reconcile 层处理格式差异，该方法已删。
 * 旧测试仍引用它导致单测编译失败，被 CI 的 {@code gradle test || true} 掩盖
 * 过一段时间（2026-09-18 审计发现）。本文件现钉住「native 不可用时的安全
 * 降级」契约：双通道都不可用时 search() 必须返回 null（浮窗降级而非崩溃）。
 */
public class UmaNativeBridgeTest {
    @Test public void searchReturnsNullWhenNativeUnavailableAndNoCloud() {
        // JVM 单测里没有 libuma_jni.so：loaded=false；未配置云端 → 双通道都不可用
        UmaNativeBridge.setCloudUrl("");
        assertFalse("JVM 环境不应加载到 native 库", UmaNativeBridge.isLoaded());
        assertNull(UmaNativeBridge.search(
                new JSONObject("{\"turn\":1}"),
                102601,
                new int[]{302424, 302894, 303044, 302924, 303024, 303054},
                8));
    }

    @Test public void cloudUrlRoundTripAndNullClears() {
        UmaNativeBridge.setCloudUrl("http://127.0.0.1:19000");
        assertTrue(UmaNativeBridge.getCloudUrl().contains("127.0.0.1:19000"));
        UmaNativeBridge.setCloudUrl(null);
        assertTrue("setCloudUrl(null) 应清空云端地址", UmaNativeBridge.getCloudUrl().isEmpty());
    }
}
