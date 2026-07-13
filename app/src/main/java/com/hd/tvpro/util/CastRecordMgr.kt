package com.hd.tvpro.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hd.tvpro.model.CastRecord

object CastRecordMgr {
    private const val KEY_RECORDS = "cast_records"
    private const val MAX_RECORDS = 50

    fun addRecord(context: Context, title: String, url: String) {
        val records = getRecords(context).toMutableList()
        records.removeAll { it.url == url }
        records.add(0, CastRecord(title, url))
        if (records.size > MAX_RECORDS) {
            records.removeAt(records.size - 1)
        }
        saveRecords(context, records)
    }

    fun getRecords(context: Context): List<CastRecord> {
        val json = PreferenceMgr.getString(context, KEY_RECORDS, "")
        if (json.isNullOrEmpty()) {
            return emptyList()
        }
        return try {
            val type = object : TypeToken<List<CastRecord>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearRecords(context: Context) {
        PreferenceMgr.put(context, KEY_RECORDS, "")
    }

    private fun saveRecords(context: Context, records: List<CastRecord>) {
        val json = Gson().toJson(records)
        PreferenceMgr.put(context, KEY_RECORDS, json)
    }
}