package com.pdy.tvpro.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pngcui.skyworth.dlna.event.EngineCommand
import com.pngcui.skyworth.dlna.service.MediaRenderService
import org.greenrobot.eventbus.EventBus

/**
 * 开机自启相关：拉起投屏服务 + 网络可用时补救。
 *
 * 场景：开机广播时 Wi‑Fi 可能尚未就绪，DMR 注册失败；
 * 网络连通后再拉起/重启引擎，提高自启成功率。
 * 仅在用户开启 selfStart 时生效。
 */
object SelfStartHelper {

    private const val TAG = "SelfStartHelper"
    private const val RESTART_DELAY_MS = 800L

    @Volatile
    private var networkCallbackRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingRestart: Runnable? = null

    /**
     * 在 [com.pdy.tvpro.app.App] 中调用，进程存活期间监听网络。
     */
    fun installNetworkRestore(appContext: Context) {
        val app = appContext.applicationContext
        if (networkCallbackRegistered) {
            return
        }
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager null")
            return
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "network onAvailable")
                    onNetworkAvailable(app)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val hasNet = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                    val validated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        )
                    } else {
                        true
                    }
                    if (hasNet && validated) {
                        Log.i(TAG, "network capabilities ok (validated=$validated)")
                        onNetworkAvailable(app)
                    }
                }
            })
            networkCallbackRegistered = true
            Log.i(TAG, "network restore callback registered")
            // 若安装时网络已通，补一次
            if (PreferenceMgr.getBoolean(app, "selfStart", false) && isNetworkUsable(cm)) {
                onNetworkAvailable(app)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed", e)
        }
    }

    /**
     * 开机广播 / 网络补救 共用：在开启自启时启动投屏服务。
     */
    fun startCastServiceIfEnabled(context: Context, reason: String) {
        val app = context.applicationContext
        if (!PreferenceMgr.getBoolean(app, "selfStart", false)) {
            Log.d(TAG, "skip start ($reason): selfStart off")
            return
        }
        try {
            val serviceIntent = Intent(app, MediaRenderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(serviceIntent)
            } else {
                app.startService(serviceIntent)
            }
            Log.i(TAG, "MediaRenderService start requested ($reason)")
        } catch (e: Exception) {
            Log.e(TAG, "start MediaRenderService failed ($reason)", e)
        }
    }

    private fun onNetworkAvailable(app: Context) {
        if (!PreferenceMgr.getBoolean(app, "selfStart", false)) {
            return
        }
        // 防抖：短时间多次 onAvailable / capabilities 只处理一次
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            startCastServiceIfEnabled(app, "network")
            // 服务可能已在跑但引擎因无网启动失败，延迟后请求重启引擎
            mainHandler.postDelayed({
                try {
                    EventBus.getDefault().post(
                        EngineCommand(MediaRenderService.RESTART_RENDER_ENGINE)
                    )
                    Log.i(TAG, "posted RESTART_RENDER_ENGINE after network")
                } catch (e: Exception) {
                    Log.e(TAG, "post restart engine failed", e)
                }
            }, RESTART_DELAY_MS)
        }
        pendingRestart = task
        mainHandler.postDelayed(task, 500)
    }

    private fun isNetworkUsable(cm: ConnectivityManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                info != null && info.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }
}