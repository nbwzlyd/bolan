package com.pdy.tvpro.security;

import android.content.Context;

/**
 * dex2c native 校验入口。类名对应 dcc.bolan.cfg 的 security.jni_class；
 * 方法名必须固定为 verify / encrypt / decryptResponseData（静态 native）。
 */
public final class SecuLoader {
    private SecuLoader() {
    }

    public static native boolean verify(Context context);

    public static native String encrypt();

    public static native String decryptResponseData(String base64Data);
}
