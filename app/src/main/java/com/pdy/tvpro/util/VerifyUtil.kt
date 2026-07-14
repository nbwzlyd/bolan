package com.pdy.tvpro.util

import android.content.Context

object VerifyUtil {

    init {
        System.loadLibrary("verify")
    }

    @JvmStatic
    external fun nativeVerifySignature(context: Context): Boolean

    @JvmStatic
    external fun nativeVerifyPackageName(context: Context): Boolean

    @JvmStatic
    external fun nativeVerifyAll(context: Context): Boolean

    fun verifyAll(context: Context): Boolean {
        return nativeVerifyAll(context)
    }

    fun verifySignature(context: Context): Boolean {
        return nativeVerifySignature(context)
    }

    fun verifyPackageName(context: Context): Boolean {
        return nativeVerifyPackageName(context)
    }
}