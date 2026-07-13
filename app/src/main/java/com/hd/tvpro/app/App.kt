package com.hd.tvpro.app

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.hd.tvpro.constants.TimeConstants
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

    open fun getDevInfo(): DeviceInfo? {
        return mDeviceInfo
    }
}