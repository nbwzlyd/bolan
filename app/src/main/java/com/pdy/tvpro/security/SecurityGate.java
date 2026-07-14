package com.pdy.tvpro.security;

/**
 * dex2c native_verifier 通过 GetStaticFieldID/SetStaticBooleanField 写 blocked。
 * 必须是 Java public static boolean，Kotlin object + @JvmField 是实例字段，JNI 会失败。
 */
public final class SecurityGate {
    public static volatile boolean blocked = false;

    private SecurityGate() {
    }
}
