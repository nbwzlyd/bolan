package com.pdy.tvpro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pdy.tvpro.util.SelfStartHelper

/**
 * 开机自启：仅启动投屏服务（DMR），不打开界面。
 * 真正有投屏时由 [com.pngcui.skyworth.dlna.center.CastPlayerLauncher] 再拉起 MainActivity。
 * 若开机时网络未就绪，由 [SelfStartHelper] 在网络可用时补救。
 */
class SelfStartingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED
            && action != "android.intent.action.QUICKBOOT_POWERON"
            && action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        Log.d(TAG, "onReceive action=$action")
        SelfStartHelper.startCastServiceIfEnabled(context, "boot:$action")
    }

    companion object {
        private const val TAG = "SelfStartingReceiver"
    }
}