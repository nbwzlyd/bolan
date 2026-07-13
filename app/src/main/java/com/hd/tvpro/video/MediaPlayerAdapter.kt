package com.hd.tvpro.video

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.view.SurfaceHolder
import java.util.Locale
import androidx.leanback.media.PlaybackBaseControlGlue
import androidx.leanback.media.PlaybackGlueHost
import androidx.leanback.media.PlayerAdapter
import androidx.leanback.media.SurfaceHolderGlueHost
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectionArray
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hd.tvpro.util.PreferenceMgr
import com.hd.tvpro.util.StringUtil
import java.util.HashSet

/**
 * 纯 Leanback + Media3 的播放适配器。
 * 继承自 Leanback 的 PlayerAdapter，transport（播放/暂停/快进/进度）由 Leanback glue 驱动，
 * 渲染由 media3 的 PlayerView 负责（surface + 字幕 + resizeMode 一体）。
 * 不再依赖 chuangyuan 的 ManualPlayer / ExoUserPlayer / VideoPlayerManager。
 */
class MediaPlayerAdapter constructor(
    private var mContext: Context,
    private var playerView: PlayerView,
    private val videoDataHelper: VideoDataHelper
) : PlayerAdapter() {

    /** 直接持有的 Media3 ExoPlayer 实例 */
    var player: ExoPlayer? = null
        private set

    var mSurfaceHolderGlueHost: SurfaceHolderGlueHost? = null
    val mRunnable: Runnable = object : Runnable {
        override fun run() {
            // 进度更新：外部监听器在 onCurrentPositionChanged 中处理，这里只触发回调
            callback.onCurrentPositionChanged(this@MediaPlayerAdapter)
            mHandler.postDelayed(this, getProgressUpdatingInterval().toLong())
        }
    }
    val mHandler = Handler()
    var mInitialized = false // true when the player is prepared/initialized

    var mMediaSourceUri: String? = null
    var headers: Map<String, String>? = null
    var subtitle: String? = null
    var mHasDisplay = false
    var mBufferedProgress: Long = 0
    var playStartTask: Runnable? = null

    private var listeners: MutableList<Callback> = ArrayList()
    var onTracksChangedListener: TracksChangedListener? = null

    interface TracksChangedListener {
        fun onTracksChanged(tracks: androidx.media3.common.Tracks)
    }

    private val videoPlayerSurfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
            // 用 PlayerView 自带的 surface 时不需要手动 setDisplay
        }

        override fun surfaceChanged(surfaceHolder: SurfaceHolder, i: Int, i1: Int, i2: Int) {
        }

        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
        }
    }

    fun addListener(listener: Callback) {
        listeners.add(listener)
    }

    private fun initPlayer() {
        checkListener()
        if (player == null) {
            val renderersFactory = DefaultRenderersFactory(mContext)
                // 默认优先使用 MediaCodec 扩展渲染器；音频解码失败时由 onPlayerError 退化处理
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

            val trackSelector = DefaultTrackSelector(mContext).apply {
                setParameters(
                    buildUponParameters()
                        .setPreferredAudioLanguages(*getDeviceLanguages())
                        .setPreferredTextLanguages(*getDeviceLanguages())
                )
            }

            player = ExoPlayer.Builder(mContext)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .build()

            // 渲染交给 PlayerView
            playerView.player = player

            player!!.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            mInitialized = true
                            listeners.forEach {
                                it.onBufferingStateChanged(this@MediaPlayerAdapter, false)
                                it.onPreparedStateChanged(this@MediaPlayerAdapter)
                            }
                            playStartTask?.run()
                        }
                        Player.STATE_BUFFERING -> {
                            listeners.forEach {
                                it.onBufferingStateChanged(this@MediaPlayerAdapter, true)
                            }
                        }
                        Player.STATE_ENDED -> {
                            listeners.forEach {
                                it.onPlayCompleted(this@MediaPlayerAdapter)
                            }
                            videoDataHelper.next(true)
                        }
                        Player.STATE_IDLE -> {
                            // 空闲
                        }
                    }
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    listeners.forEach {
                        it.onBufferingStateChanged(this@MediaPlayerAdapter, isLoading)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listeners.forEach {
                        it.onPlayStateChanged(this@MediaPlayerAdapter)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (error.message?.contains("MediaCodecAudioRenderer error") == true) {
                        // 系统音频解码失败：尝试优先使用扩展（ffmpeg）渲染器后重建；
                        // 注意：当前已移除 ffmpeg 源码模块，扩展渲染器仅为 MediaCodec 扩展，
                        // 这里退化为直接使用 MediaCodec（EXTENSION_RENDERER_MODE_ON 已是默认）。
                        reStartPlayer()
                        return
                    }
                    onError(error)
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    super.onTracksChanged(tracks)
                    onTracksChangedListener?.onTracksChanged(tracks)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    super.onVideoSizeChanged(videoSize)
                    listeners.forEach {
                        it.onVideoSizeChanged(
                            this@MediaPlayerAdapter,
                            videoSize.width,
                            videoSize.height
                        )
                    }
                }
            })

            loadResizeMode()
            loadSpeed()
        }
    }

    fun getDeviceLanguages(): Array<String> {
        val locales: MutableSet<String> = HashSet()
        locales.add("zh")
        if (Build.VERSION.SDK_INT >= 24) {
            val localeList: LocaleList = Resources.getSystem().configuration.locales
            for (i in 0 until localeList.size()) {
                locales.add(localeList.get(i).isO3Language)
            }
        } else {
            val locale: Locale = Resources.getSystem().configuration.locale
            locales.add(locale.isO3Language)
        }
        return locales.toTypedArray()
    }

    private fun onError(e: Exception?) {
        e?.let {
            e.printStackTrace()
            listeners.forEach {
                it.onError(this@MediaPlayerAdapter, 0, e.message)
            }
        }
    }

    private fun checkListener() {
        if (listeners.isEmpty() || (callback != null && !listeners.contains(callback))) {
            listeners.add(callback)
        }
    }

    fun loadResizeMode() {
        when (PreferenceMgr.getInt(mContext, "screen", 0)) {
            0 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            3 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    fun loadSpeed() {
        val speed = PreferenceMgr.getFloat(mContext, "speed", 1f)
        player?.setPlaybackSpeed(speed)
    }

    override fun onAttachedToHost(host: PlaybackGlueHost?) {
        // 渲染由 PlayerView 接管，不再使用 SurfaceHolderGlueHost 手动设置 surface。
        // 保留回调占位以兼容 Leanback 的 glue host 生命周期。
        if (host is SurfaceHolderGlueHost) {
            mSurfaceHolderGlueHost = host
            mSurfaceHolderGlueHost!!.setSurfaceHolderCallback(videoPlayerSurfaceHolderCallback)
        }
    }

    private fun reset() {
        player?.let {
            changeToUnitialized()
            try {
                player!!.stop()
            } catch (e: Exception) {
            }
        }
    }

    private fun changeToUnitialized() {
        if (player != null) {
            if (mHasDisplay) {
                listeners.forEach {
                    it.onPreparedStateChanged(this@MediaPlayerAdapter)
                }
            }
        }
    }

    private fun release() {
        player?.let {
            changeToUnitialized()
            mHasDisplay = false
            player!!.release()
            player = null
        }
    }

    override fun onDetachedFromHost() {
        memoryPosition()
        if (mSurfaceHolderGlueHost != null) {
            mSurfaceHolderGlueHost!!.setSurfaceHolderCallback(null)
            mSurfaceHolderGlueHost = null
        }
        reset()
        release()
    }

    fun memoryPosition() {
        player?.let {
            if (it.isCurrentWindowLive != true) {
                PreferenceMgr.put(mContext, memUrlKey, mMediaSourceUri)
                var pos = 0L
                if (it.duration - it.currentPosition > 3 * 60 * 1000 && it.duration > 10 * 60 * 1000 && it.currentPosition > 3 * 60 * 1000) {
                    pos = it.currentPosition
                }
                PreferenceMgr.put(mContext, memPosKey, pos)
            }
        }
    }

    protected fun onError(what: Int, extra: Int): Boolean {
        return false
    }

    protected fun onSeekComplete() {}

    protected fun onInfo(what: Int, extra: Int): Boolean {
        return false
    }

    fun setDisplay(surfaceHolder: SurfaceHolder?) {
        val hadDisplay = mHasDisplay
        mHasDisplay = surfaceHolder != null
        if (hadDisplay == mHasDisplay) {
            return
        }
        // 使用 PlayerView 时不需要手动设置 surface；保留状态通知
        listeners.forEach {
            if (mHasDisplay) {
                if (player != null) {
                    it.onPreparedStateChanged(this@MediaPlayerAdapter)
                }
            } else {
                if (player != null) {
                    it.onPreparedStateChanged(this@MediaPlayerAdapter)
                }
            }
        }
    }

    override fun setProgressUpdatingEnabled(enabled: Boolean) {
        mHandler.removeCallbacks(mRunnable)
        if (!enabled) {
            return
        }
        mHandler.postDelayed(mRunnable, getProgressUpdatingInterval().toLong())
    }

    fun getProgressUpdatingInterval(): Int {
        // 原值 16ms ≈ 62 次/秒过于频繁，改为 1000ms（与 DLNA 进度上报节奏一致），显著降低耗电
        return 1000
    }

    override fun isPlaying(): Boolean {
        return player?.isPlaying == true
    }

    override fun getDuration(): Long {
        return player?.duration ?: -1
    }

    override fun getCurrentPosition(): Long {
        return player?.currentPosition ?: -1
    }

    override fun play() {
        player?.let {
            if (it.isPlaying) {
                return
            }
            it.playWhenReady = true
            listeners.forEach {
                it.onPlayStateChanged(this@MediaPlayerAdapter)
                it.onCurrentPositionChanged(this@MediaPlayerAdapter)
            }
        }
    }

    override fun pause() {
        if (isPlaying && player != null) {
            player!!.playWhenReady = false
            listeners.forEach {
                it.onPlayStateChanged(this@MediaPlayerAdapter)
            }
        }
    }

    override fun next() {
        videoDataHelper.next(false)
    }

    override fun fastForward() {
        videoDataHelper.fastForward()
    }

    override fun rewind() {
        videoDataHelper.rewind()
    }

    override fun getSupportedActions(): Long {
        return (PlaybackBaseControlGlue.ACTION_PLAY_PAUSE or
                PlaybackBaseControlGlue.ACTION_FAST_FORWARD or
                PlaybackBaseControlGlue.ACTION_REWIND or
                PlaybackBaseControlGlue.ACTION_SKIP_TO_NEXT).toLong()
    }

    override fun seekTo(newPosition: Long) {
        if (player == null) {
            return
        }
        player!!.seekTo(newPosition)
    }

    override fun getBufferedPosition(): Long {
        return mBufferedProgress
    }

    override fun isPrepared(): Boolean {
        return player != null
    }

    /**
     * 设置媒体源并开始播放。
     */
    fun setDataSource(
        uri: String?,
        headers: Map<String, String>?,
        subtitle: String? = null
    ): Boolean {
        if (uri == null) {
            return false
        }
        mMediaSourceUri = uri
        this.headers = headers
        this.subtitle = subtitle
        prepareMediaForPlaying()
        return true
    }

    private fun buildMediaItem(): MediaItem {
        val builder = MediaItem.Builder().setUri(mMediaSourceUri)
        val sub = subtitle
        if (!sub.isNullOrEmpty()) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("zh")
                        .build()
                )
            )
        }
        return builder.build()
    }

    private fun prepareMediaForPlaying() {
        reset()
        initPlayer()
        try {
            val memUrl = PreferenceMgr.getString(mContext, memUrlKey, "")
            var startPos = 0L
            if (StringUtil.isNotEmpty(memUrl) && memUrl == mMediaSourceUri) {
                val memPos = PreferenceMgr.getLong(mContext, memPosKey, 0L)
                if (memPos > 0) {
                    startPos = memPos
                }
            }
            val currentHeaders = headers
            player?.apply {
                val factory = if (mMediaSourceUri?.startsWith("http", true) == true) {
                    val dsFactory = DefaultHttpDataSource.Factory()
                    if (!currentHeaders.isNullOrEmpty()) {
                        dsFactory.setDefaultRequestProperties(currentHeaders)
                    }
                    dsFactory
                } else {
                    DefaultDataSource.Factory(mContext)
                }
                setMediaSource(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(mContext)
                        .setDataSourceFactory(factory)
                        .createMediaSource(buildMediaItem())
                )
                seekTo(startPos)
                playWhenReady = true
                prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun reStartPlayer() {
        val pos = player?.currentPosition ?: 0
        reset()
        initPlayer()
        try {
            val currentHeaders = headers
            player?.apply {
                val factory = if (mMediaSourceUri?.startsWith("http", true) == true) {
                    val dsFactory = DefaultHttpDataSource.Factory()
                    if (!currentHeaders.isNullOrEmpty()) {
                        dsFactory.setDefaultRequestProperties(currentHeaders)
                    }
                    dsFactory
                } else {
                    DefaultDataSource.Factory(mContext)
                }
                setMediaSource(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(mContext)
                        .setDataSourceFactory(factory)
                        .createMediaSource(buildMediaItem())
                )
                seekTo(pos)
                playWhenReady = true
                prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val memUrlKey = "url"
        const val memPosKey = "memPos"
    }
}
