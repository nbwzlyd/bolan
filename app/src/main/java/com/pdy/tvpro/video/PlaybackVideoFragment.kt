package com.pdy.tvpro.video

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.DisplayMetrics
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.leanback.media.PlayerAdapter
import androidx.leanback.widget.BaseGridView.OnTouchInterceptListener
import androidx.leanback.widget.PlaybackControlsRow
import androidx.leanback.widget.PlaybackSeekDataProvider
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.SelectionOverride
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectionArray
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.google.gson.Gson
import com.pdy.tvpro.MainActivity
import com.pdy.tvpro.R
import com.pdy.tvpro.app.App
import com.pdy.tvpro.event.PlayUrlChange
import com.pdy.tvpro.event.SwitchUrlChange
import com.pdy.tvpro.setting.SettingHolder
import com.pdy.tvpro.setting.CastRecordHolder
import com.pdy.tvpro.util.*
import com.pdy.tvpro.util.http.HttpListener
import com.pdy.tvpro.util.http.HttpUtils
import com.pdy.tvpro.video.MediaPlayerAdapter
import com.pdy.tvpro.video.MyPlaybackTransportControlGlue
import com.pdy.tvpro.video.VideoDataHelper
import com.pdy.tvpro.video.model.DlanUrlDTO
import com.pdy.tvpro.video.model.TrackHolder
import com.pdy.tvpro.util.FileUtil
import com.pdy.tvpro.util.CastRecordMgr
import com.pngcui.skyworth.dlna.center.DLNAGenaEventBrocastFactory
import com.pngcui.skyworth.dlna.center.DlnaMediaModel
import com.pngcui.skyworth.dlna.center.MediaControlBrocastFactory
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.*
import kotlin.collections.ArrayList
import androidx.lifecycle.lifecycleScope
import kotlin.math.floor

/** Handles video playback with media controls (纯 Leanback + Media3). */
class PlaybackVideoFragment : androidx.leanback.app.VideoSupportFragment(),
    MediaControlBrocastFactory.IMediaControlListener {

    private lateinit var mTransportControlGlue: MyPlaybackTransportControlGlue<MediaPlayerAdapter>
    private var playData: DlanUrlDTO? = null
    private lateinit var playerAdapter: MediaPlayerAdapter
    private lateinit var videoView: PlayerView
    private var settingHolder: SettingHolder? = null

    private lateinit var mMediaControlBorcastFactory: MediaControlBrocastFactory
    private val scope: CoroutineScope = lifecycleScope
    private var useDlan = false
    private var lastShowToastTime1: Long = 0
    private var lastShowToastTime2: Long = 0
    private var isLongPressLeft = false
    private var isLongPressRight = false
    private var longPressJob: Job? = null
    private var castRecordHolder: CastRecordHolder? = null
    private var lastVideoWidth: Int = 0
    private var lastVideoHeight: Int = 0
    private var lastVideoRotation: Int = 0
    private var lastDlnaUrl: String? = null
    private var lastDlnaPlayAt: Long = 0L
    private var portraitModeApplied: Boolean = false

    private var webDlanData: DlanUrlDTO? = null

    private var trackHolder: TrackHolder? = null

    fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return false
    }

    fun onKeyDown(keyCode: Int, ev: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSetting()
            return true
        }
        return false
    }

    fun onBackPressed(): Boolean {
        if (castRecordHolder != null && castRecordHolder!!.isShowing()) {
            if (isControlsOverlayVisible) {
                hideControlsOverlay(false)
            }
            castRecordHolder!!.hide()
            return true
        }
        if (settingHolder != null && settingHolder!!.isShowing()) {
            if (isControlsOverlayVisible) {
                hideControlsOverlay(false)
            }
            settingHolder!!.hide()
            return true
        }
        if (isControlsOverlayVisible && view != null) {
            hideControlsOverlay(false)
            return true
        }
        return false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        videoView = PlayerView(requireContext())
        videoView.useController = false

        val glueHost = androidx.leanback.app.VideoSupportFragmentGlueHost(this@PlaybackVideoFragment)
        playerAdapter = MediaPlayerAdapter(requireActivity(), videoView, videoDataHelper)

        playerAdapter.onTracksChangedListener = object : MediaPlayerAdapter.TracksChangedListener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                trackHolder = TrackHolder(tracks, {
                    (playerAdapter.player?.trackSelector as MappingTrackSelector?)?.currentMappedTrackInfo
                }, {
                    playData?.subtitle
                })
            }
        }
        playerAdapter.onVideoSizeListener = { width, height, rotation ->
            lastVideoWidth = width
            lastVideoHeight = height
            lastVideoRotation = rotation
            applyAutoPortraitMode()
        }
        playerAdapter.setRepeatAction(PlaybackControlsRow.RepeatAction.INDEX_NONE)

        mTransportControlGlue = MyPlaybackTransportControlGlue(activity, playerAdapter)
        mTransportControlGlue.host = glueHost
        mTransportControlGlue.playWhenPrepared()
        mTransportControlGlue.isControlsOverlayAutoHideEnabled = true
        mTransportControlGlue.isSeekEnabled = true

        mTransportControlGlue.seekProvider = object : PlaybackSeekDataProvider() {
            override fun getSeekPositions(): LongArray {
                val seekGap = 10 * 1000
                val gap: Int = floor((playerAdapter.duration / seekGap).toDouble()).toInt()
                val positions = LongArray(gap)
                for (index in 0 until gap) {
                    positions[index] = (index * seekGap).toLong()
                }
                return positions
            }
        }

        val initUrl = PreferenceMgr.getString(context, "playUrl", "")
        val initTitle = PreferenceMgr.getString(context, "playTitle", null)
        playData = DlanUrlDTO()
        playData?.url = initUrl
        playData?.title = initTitle
        if (!initTitle.isNullOrEmpty()) {
            mTransportControlGlue.title = "\n" + initTitle
            mTransportControlGlue.subtitle = initUrl
        }

        playerAdapter.setDataSource(initUrl, null)
        val parent = view.parent as ViewGroup
        parent.addView(videoView, 0)


        val subtitleView: SubtitleView? = videoView.subtitleView
        subtitleView?.setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null
            )
        )
        initDlan()
    }

    private fun initDlan() {
        mMediaControlBorcastFactory = MediaControlBrocastFactory(App.INSTANCE)
        mMediaControlBorcastFactory.register(this)
        playerAdapter.addListener(object : PlayerAdapter.Callback() {
            override fun onPreparedStateChanged(adapter: PlayerAdapter?) {
                if (playerAdapter.duration > 0) {
                    DLNAGenaEventBrocastFactory.sendDurationEvent(
                        App.INSTANCE,
                        playerAdapter.duration.toInt()
                    )
                }
            }

            override fun onPlayStateChanged(adapter: PlayerAdapter?) {
                if (playerAdapter.isPlaying) {
                    DLNAGenaEventBrocastFactory.sendPlayStateEvent(App.INSTANCE)
                } else {
                    DLNAGenaEventBrocastFactory.sendPauseStateEvent(App.INSTANCE)
                }
            }

            override fun onVideoSizeChanged(adapter: PlayerAdapter?, width: Int, height: Int) {
                lastVideoWidth = width
                lastVideoHeight = height
                applyAutoPortraitMode()
            }
        })
        scope.launch {
            var count = 1
            while (true) {
                try {
                    if (App.INSTANCE.getDevInfo()?.status == true) {
                        if (activity == null || activity?.isFinishing == true) {
                            break
                        }
                        withContext(Dispatchers.Main) {
                            DLNAGenaEventBrocastFactory.sendDurationEvent(
                                App.INSTANCE,
                                playerAdapter.duration.toInt()
                            )
                            DLNAGenaEventBrocastFactory.sendSeekEvent(
                                App.INSTANCE,
                                playerAdapter.currentPosition.toInt()
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (e is CancellationException) {
                        break
                    }
                }
                try {
                    if (count == 1) {
                        count++
                    } else {
                        count--
                        if (playerAdapter?.player != null && playerAdapter?.player?.isPlaying == true) {
                            playerAdapter?.memoryPosition()
                            Log.d(TAG, "initDlan: memoryPosition")
                        }
                    }
                } catch (e: Exception) {
                }
                delay(1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDlan(media: DlnaMediaModel) {
        playDlnaMedia(media)
    }

    /**
     * 播放 DLNA 投屏内容。供 EventBus 与 MainActivity 冷启动拉起共用。
     */
    fun playDlnaMedia(media: DlnaMediaModel) {
        Log.d(TAG, "onDlan: " + Gson().toJson(media))
        if (activity?.isFinishing == true) {
            return
        }
        val incomingUrl = media.url ?: ""
        val now = System.currentTimeMillis()
        // EventBus + 拉起界面可能连续投递两次，短时间同 URL 去重
        if (incomingUrl.isNotEmpty() && incomingUrl == lastDlnaUrl && now - lastDlnaPlayAt < 1500) {
            Log.d(TAG, "playDlnaMedia: skip duplicate url within 1.5s")
            return
        }
        lastDlnaUrl = incomingUrl
        lastDlnaPlayAt = now
        useDlan = true
        playData = DlanUrlDTO()
        playData?.apply {
            url = media.url?.split("##\\|")?.get(0) ?: media.url
            headers = HttpParser.getEncodedHeaders(media.url)
            title = media.title
        }
        play()
        if (isControlsOverlayVisible) {
            hideControlsOverlay(false)
        }
        DLNAGenaEventBrocastFactory.sendPlayStateEvent(App.INSTANCE)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onSwitch(media: SwitchUrlChange) {
        if (activity?.isFinishing == true) {
            return
        }
        playData = DlanUrlDTO()
        playData?.apply {
            url = media.url
        }
        PreferenceMgr.put(context, "playUrl", media.url)
        play(false)
        if (isControlsOverlayVisible) {
            hideControlsOverlay(false)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPlay(media: PlayUrlChange) {
        if (activity?.isFinishing == true) {
            return
        }
        if (playData == null) {
            playData = DlanUrlDTO()
        }
        val urlEmpty = media.url.isNullOrEmpty()
        playData?.apply {
            if (!urlEmpty) {
                url = media.url
                title = if (media.name.isNullOrEmpty()) media.url else media.name
            }
            subtitle = media.subtitle
        }
        play(true)
        if (isControlsOverlayVisible) {
            hideControlsOverlay(false)
        }
    }

    fun onInterceptInputEvent(event: InputEvent?): Boolean {
        var keyCode = KeyEvent.KEYCODE_UNKNOWN
        var keyAction = 0
        if (event is KeyEvent) {
            keyCode = event.keyCode
            keyAction = event.action
        }
        if (!isControlsOverlayVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    val act = activity as MainActivity
                    if (act.dismissDialog()) {
                        return true
                    }
                    if (onBackPressed()) {
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isSeekable()) {
                        if (keyAction == KeyEvent.ACTION_DOWN) {
                            ToastMgr.shortBottomCenter(context, "该视频不支持快进")
                        }
                        return true
                    }
                    if (keyAction == KeyEvent.ACTION_DOWN) {
                        isLongPressLeft = false
                        fastPositionJump(-15)
                        val now = System.currentTimeMillis()
                        if (now - lastShowToastTime1 > 5 * 1000) {
                            ToastMgr.shortBottomCenter(context, "已快退15秒")
                        }
                        lastShowToastTime1 = now
                        scope.launch {
                            delay(500)
                            if (!isLongPressLeft && isResumed) {
                                isLongPressLeft = true
                                startLongPressSeek(-15)
                            }
                        }
                    } else if (keyAction == KeyEvent.ACTION_UP) {
                        isLongPressLeft = false
                        stopLongPressSeek()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isSeekable()) {
                        if (keyAction == KeyEvent.ACTION_DOWN) {
                            ToastMgr.shortBottomCenter(context, "该视频不支持快进")
                        }
                        return true
                    }
                    if (keyAction == KeyEvent.ACTION_DOWN) {
                        isLongPressRight = false
                        fastPositionJump(15)
                        val now = System.currentTimeMillis()
                        if (now - lastShowToastTime2 > 5 * 1000) {
                            ToastMgr.shortBottomCenter(context, "已快进15秒")
                        }
                        lastShowToastTime2 = now
                        scope.launch {
                            delay(500)
                            if (!isLongPressRight && isResumed) {
                                isLongPressRight = true
                                startLongPressSeek(15)
                            }
                        }
                    } else if (keyAction == KeyEvent.ACTION_UP) {
                        isLongPressRight = false
                        stopLongPressSeek()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    showSetting()
                    return true
                }
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        super.onDestroy()
    }

    private fun fastPositionJump(forward: Long) {
        var newPos: Long = playerAdapter.currentPosition + forward * 1000
        if (playerAdapter.duration < newPos) {
            newPos = playerAdapter.duration - 1000
        } else if (newPos < 0) {
            if (forward > 0) {
                newPos = forward * 1000
            } else {
                newPos = 0
            }
        }
        playerAdapter.seekTo(newPos)
    }

    private fun isSeekable(): Boolean {
        return try {
            playerAdapter.duration > 0 && playerAdapter.player?.isCurrentWindowSeekable == true
        } catch (e: Exception) {
            false
        }
    }

    private fun startLongPressSeek(stepSeconds: Long) {
        longPressJob?.cancel()
        longPressJob = scope.launch {
            while (isLongPressLeft || isLongPressRight) {
                delay(200)
                if (isResumed && isSeekable()) {
                    withContext(Dispatchers.Main) {
                        fastPositionJump(stepSeconds)
                    }
                }
            }
        }
    }

    private fun stopLongPressSeek() {
        longPressJob?.cancel()
        longPressJob = null
    }

    private val videoDataHelper = object : VideoDataHelper {
        override fun next(autoEnd: Boolean) {
            if (useDlan) {
                if (autoEnd) {
                    ToastMgr.shortBottomCenter(context, "正在使用DLAN投屏，不支持自动下一集")
                } else {
                    ToastMgr.shortBottomCenter(context, "正在使用DLAN投屏，手机上操作吧~")
                }
                return
            }
            val lastMem = PreferenceMgr.getString(activity, "remote", null)
            lastMem?.let {
                ToastMgr.shortBottomCenter(context, "播放下一集")
                scope.launch(Dispatchers.IO) {
                    Log.d(TAG, "playNext: ${lastMem}/playNext")
                    HttpUtils.get("${lastMem}/playNext", object : HttpListener {
                        override fun success(body: String?) {
                            Log.d(TAG, "playNext success: $body")
                        }

                        override fun failed(msg: String?) {
                        }
                    })
                }
            }
        }

        override fun previous() {
        }

        override fun fastForward() {
            fastPositionJump(10)
        }

        override fun rewind() {
            fastPositionJump(-10)
        }

    }

    private fun startCheckPlayUrl(url: String) {
        if (useDlan) {
            return
        }
        scope.launch(Dispatchers.IO) {
            HttpUtils.get("$url/playUrl?enhance=true", object : HttpListener {
                override fun success(body: String?) {
                    if (useDlan) {
                        return
                    }
                    restartCheck(url)
                    scope.launch(Dispatchers.Main) {
                        val newData = Gson().fromJson(body, DlanUrlDTO::class.java)
                        if (webDlanData == null) {
                            playData = newData
                            webDlanData = newData
                            play()
                        } else if (newData.url != webDlanData?.url) {
                            playData = newData
                            webDlanData = newData
                            play()
                        }
                    }
                }

                override fun failed(msg: String?) {
                    if (useDlan) {
                        return
                    }
                    restartCheck(url)
                }
            })
        }
    }

    private fun play(clearSwitch: Boolean = true) {
        if (isControlsOverlayVisible) {
            hideControlsOverlay(false)
        }
        playData?.let {
            val title = if (it.title.isNullOrEmpty() || it.title == it.url) FileUtil.getFileName(it.url) else it.title
            CastRecordMgr.addRecord(requireContext(), title, it.url)
        }
        playData?.let {
            val t =
                if (it.title.isNullOrEmpty() || it.title == it.url) FileUtil.getFileName(it.url) else it.title
            mTransportControlGlue.title = "\n" + t
            mTransportControlGlue.subtitle = it.subtitle
            playerAdapter.setDataSource(it.url, it.headers, it.subtitle)
        }
        hideControlsOverlay(true)
    }

    private fun restartCheck(url: String) {
        if (activity?.isFinishing == false) {
            scope.launch(Dispatchers.IO) {
                delay(1000)
                if (activity?.isFinishing == false) {
                    startCheckPlayUrl(url)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mTransportControlGlue.pause()
    }

    override fun getSurfaceView(): SurfaceView {
        return videoView.videoSurfaceView as SurfaceView
    }

    fun showSetting() {
        if (settingHolder == null) {
            settingHolder =
                SettingHolder(requireContext(), object : SettingHolder.SettingUpdateListener {
                    override fun update(option: SettingHolder.Option) {
                        when (option) {
                            SettingHolder.Option.SCREEN -> {
                                playerAdapter.loadResizeMode()
                            }
                            SettingHolder.Option.SPEED -> {
                                playerAdapter.loadSpeed()
                            }
                            SettingHolder.Option.AUTO_PORTRAIT -> {
                                playerAdapter.refreshVideoSizeIfPossible()
                                applyAutoPortraitMode()
                            }
                            SettingHolder.Option.RESET -> {
                                if (useDlan) {
                                    useDlan = false
                                }
                            }
                            SettingHolder.Option.FINISH -> {
                                activity?.finish()
                            }

                            SettingHolder.Option.CAST_RECORD -> {
                                showCastRecords()
                            }
                        }
                    }

                    override fun showCastRecords() {
                        if (castRecordHolder == null) {
                            castRecordHolder = CastRecordHolder(requireContext()) { record ->
                                playData = DlanUrlDTO()
                                playData?.apply {
                                    url = record.url
                                    title = record.title
                                }
                                play(true)
                            }
                        }
                        settingHolder?.hide()
                        castRecordHolder?.show(videoView)
                    }
                })
        }
        if (settingHolder!!.isShowing()) {
            return
        }
        settingHolder!!.show(videoView, playData?.url, trackHolder)
    }

    fun onHostConfigurationChanged() {
        if (settingHolder?.isShowing() == true) {
            settingHolder?.hide()
        }
        if (castRecordHolder?.isShowing() == true) {
            castRecordHolder?.hide()
        }
        if (isControlsOverlayVisible) {
            hideControlsOverlay(false)
        }
        // 系统方向变化后，按当前策略重算一次（含 TV 旋转兜底）
        applyAutoPortraitMode()
    }

    private fun isPortraitVideo(width: Int, height: Int, rotation: Int): Boolean {
        if (width <= 0 || height <= 0) {
            return false
        }
        // 手机竖屏视频常以横屏编码 + 90/270 旋转元数据
        val swapped = (rotation % 180) != 0
        val displayWidth = if (swapped) height else width
        val displayHeight = if (swapped) width else height
        return displayHeight > displayWidth
    }

    private fun applyAutoPortraitMode() {
        val act = activity ?: return
        if (act.isFinishing) {
            return
        }
        // 设置刚打开时可能还没有尺寸，尝试从播放器再读一次
        if (lastVideoWidth <= 0 || lastVideoHeight <= 0) {
            val size = playerAdapter.getCurrentVideoSize()
            if (size != null) {
                lastVideoWidth = size.first
                lastVideoHeight = size.second
                lastVideoRotation = size.third
            }
        }
        val autoPortrait = PreferenceMgr.getBoolean(requireContext(), "autoPortrait", false)
        val shouldPortrait = autoPortrait && isPortraitVideo(lastVideoWidth, lastVideoHeight, lastVideoRotation)
        Log.i(
            TAG,
            "applyAutoPortraitMode auto=$autoPortrait size=${lastVideoWidth}x${lastVideoHeight}" +
                " rot=$lastVideoRotation shouldPortrait=$shouldPortrait"
        )
        val targetOrientation = if (shouldPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (act.requestedOrientation != targetOrientation) {
            act.requestedOrientation = targetOrientation
        }
        // TV 盒子经常忽略 setRequestedOrientation，用根布局旋转兜底
        applyPortraitLayoutFallback(shouldPortrait)
        if (shouldPortrait != portraitModeApplied) {
            portraitModeApplied = shouldPortrait
            if (autoPortrait) {
                ToastMgr.shortBottomCenter(
                    requireContext(),
                    if (shouldPortrait) "已切换竖屏模式" else "已恢复横屏模式"
                )
            }
        }
        // 方向变化后强制刷新播放与控制栏布局
        view?.post {
            videoView.requestLayout()
            view?.requestLayout()
        }
    }

    /**
     * 当系统仍保持横屏时，旋转内容根视图实现视觉竖屏（兼容 TV/盒子）。
     */
    private fun applyPortraitLayoutFallback(enable: Boolean) {
        val act = activity ?: return
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        root.post {
            val dm = act.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val orientation = act.resources.configuration.orientation
            val systemIsPortrait = orientation == Configuration.ORIENTATION_PORTRAIT
            if (enable && !systemIsPortrait) {
                // 系统没转过来：把内容旋转 90 度铺满物理屏幕
                val lp = root.layoutParams
                lp.width = screenH
                lp.height = screenW
                root.layoutParams = lp
                root.pivotX = 0f
                root.pivotY = 0f
                root.rotation = 90f
                root.translationX = screenW.toFloat()
                root.translationY = 0f
                Log.i(TAG, "portrait fallback rotate applied ${screenW}x${screenH}")
            } else {
                val lp = root.layoutParams
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                root.layoutParams = lp
                root.rotation = 0f
                root.translationX = 0f
                root.translationY = 0f
                root.pivotX = root.width / 2f
                root.pivotY = root.height / 2f
            }
            root.requestLayout()
            content.requestLayout()
        }
    }

    companion object {
        private const val TAG = "PlaybackVideoFragment"
    }

    override fun onPlayCommand() {
        playerAdapter.play()
    }

    override fun onPauseCommand() {
        playerAdapter.pause()
    }

    override fun onStopCommand() {
        playerAdapter.pause()
    }

    override fun onSeekCommand(time: Int) {
        val pos: Long = when {
            time < 0 -> 0
            time > playerAdapter.duration -> playerAdapter.duration
            else -> {
                time.toLong()
            }
        }
        playerAdapter.seekTo(pos)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun changeTrack(format: Format) {
        val trackSelector: TrackSelector? = playerAdapter.player?.trackSelector
        if (trackSelector is DefaultTrackSelector) {
            val mappedTrackInfo = trackSelector.currentMappedTrackInfo
            if (mappedTrackInfo != null) {
                for (index in 0 until mappedTrackInfo.rendererCount) {
                    val trackGroupArray = mappedTrackInfo.getTrackGroups(index)
                    for (i in 0 until trackGroupArray.length) {
                        val trackGroup = trackGroupArray[i]
                        for (j in 0 until trackGroup.length) {
                            if (trackGroup.getFormat(j).id == format.id) {
                                trackSelector.setParameters(
                                    trackSelector.parameters.buildUpon()
                                        .setSelectionOverride(
                                            index, trackGroupArray,
                                            SelectionOverride(i, j)
                                        )
                                )
                                return
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        try {
            mTransportControlGlue.host = null
            playerAdapter.onDetachedFromHost()
        } catch (e: Exception) {
        }
        super.onDestroyView()
    }
}
