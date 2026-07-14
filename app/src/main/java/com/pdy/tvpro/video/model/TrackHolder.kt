package com.pdy.tvpro.video.model

import androidx.media3.common.Tracks
import androidx.media3.exoplayer.trackselection.MappingTrackSelector

/**
 * 作者：By 15968
 * 日期：On 2022/10/1
 * 时间：At 15:07
 */
data class TrackHolder(
    val tracks: Tracks?,
    val trackProvider: () -> MappingTrackSelector.MappedTrackInfo?,
    val subtitle: () -> String?
)
