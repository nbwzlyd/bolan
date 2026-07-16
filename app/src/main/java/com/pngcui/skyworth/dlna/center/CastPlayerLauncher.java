package com.pngcui.skyworth.dlna.center;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.pdy.tvpro.MainActivity;

/**
 * 收到 DLNA 投屏 URL 时拉起播放界面（开机仅起服务、不亮屏时使用）。
 */
public final class CastPlayerLauncher {

    private static final String TAG = "CastPlayerLauncher";

    public static final String EXTRA_FROM_CAST = "extra_from_cast";

    private CastPlayerLauncher() {
    }

    public static void launch(Context context, DlnaMediaModel mediaInfo) {
        if (context == null || mediaInfo == null) {
            return;
        }
        String url = mediaInfo.getUrl();
        if (url == null || url.length() < 2) {
            Log.w(TAG, "launch skip: invalid url");
            return;
        }
        try {
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(EXTRA_FROM_CAST, true);
            DlnaMediaModelFactory.pushMediaModelToIntent(intent, mediaInfo);
            context.startActivity(intent);
            Log.i(TAG, "launch MainActivity for cast, title=" + mediaInfo.getTitle());
        } catch (Exception e) {
            Log.e(TAG, "launch failed", e);
        }
    }
}