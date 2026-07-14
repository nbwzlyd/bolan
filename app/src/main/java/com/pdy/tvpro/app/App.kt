package com.pdy.tvpro.app

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.multidex.MultiDex
import com.pdy.tvpro.constants.TimeConstants
import com.pdy.tvpro.security.SecuLoader
import com.pdy.tvpro.security.SecurityGate
import com.pdy.tvpro.util.VerifyUtil
import com.lzy.okgo.OkGo
import com.lzy.okgo.https.HttpsUtils
import com.lzy.okgo.model.HttpHeaders
import com.pngcui.skyworth.dlna.device.DeviceInfo
import com.pngcui.skyworth.dlna.device.DeviceUpdateBrocastFactory
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

open class App : Application() {

    companion object {
        lateinit var INSTANCE: App
    }

    private var mDeviceInfo: DeviceInfo = DeviceInfo()

    override fun onCreate() {
        super.onCreate()

        if (!runSecurityCheck()) {
            Process.killProcess(Process.myPid())
            return
        }
        // 防止 R8 剔除带 @Dex2C 的桩方法，确保 dex2c 至少能编译到一个方法
        securityPing()

        INSTANCE = this
        val builder = OkHttpClient.Builder()
        builder.readTimeout(TimeConstants.HTTP_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        builder.writeTimeout(TimeConstants.HTTP_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        builder.connectTimeout(TimeConstants.HTTP_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        val sslParams1 = HttpsUtils.getSslSocketFactory()
        builder.sslSocketFactory(sslParams1.sSLSocketFactory, HttpsUtils.UnSafeTrustManager)
            .hostnameVerifier(HttpsUtils.UnSafeHostnameVerifier)
        val headers = HttpHeaders()
        headers.put("charset", "UTF-8")
        OkGo.getInstance().init(this).setOkHttpClient(builder.build())
            .setRetryCount(1)
            .addCommonHeaders(headers)
    }

    /**
     * 优先走 dex2c 注入的 libnc.so（JNI_OnLoad + SecuLoader.verify）。
     * 纯 Gradle 调试包尚无 nc 时，回退到旧的 libverify。
     */
    private fun runSecurityCheck(): Boolean {
        return try {
            System.loadLibrary("nc")
            val ok = SecuLoader.verify(this)
            if (!ok || SecurityGate.blocked) {
                Timber.e("dex2c security check failed")
                false
            } else {
                true
            }
        } catch (e: UnsatisfiedLinkError) {
            Timber.w(e, "libnc missing, fallback to VerifyUtil")
            VerifyUtil.verifyAll(this)
        } catch (t: Throwable) {
            Timber.e(t, "security check error")
            false
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(base)
    }

    open fun updateDevInfo(name: String?, uuid: String?) {
        mDeviceInfo.dev_name = name
        mDeviceInfo.uuid = uuid
    }

    open fun setDevStatus(flag: Boolean) {
        mDeviceInfo.status = flag
        DeviceUpdateBrocastFactory.sendDevUpdateBrocast(this)
    }

    fun hasDlanConnect(): Boolean {
        return mDeviceInfo.status
    }

    @com.pdy.tvpro.security.Dex2C
    fun securityPing(): Int {
        return if (hasDlanConnect()) 1 else 0
    }

    open fun getDevInfo(): DeviceInfo? {
        return mDeviceInfo
    }
}
