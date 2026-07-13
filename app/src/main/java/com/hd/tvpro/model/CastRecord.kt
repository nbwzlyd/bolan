package com.hd.tvpro.model

import java.io.Serializable

data class CastRecord(
    var title: String,
    var url: String,
    var time: Long = System.currentTimeMillis()
) : Serializable