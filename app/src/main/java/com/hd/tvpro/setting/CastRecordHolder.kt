package com.hd.tvpro.setting

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import com.hd.tvpro.R
import com.hd.tvpro.constants.AppConfig
import com.hd.tvpro.model.CastRecord
import com.hd.tvpro.util.CastRecordMgr
import com.hd.tvpro.util.ToastMgr
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

class CastRecordHolder constructor(
    private val context: Context,
    private val clickListener: (CastRecord) -> Unit
) {
    private var popupWindow: PopupWindow? = null
    private var listView: ListView? = null
    private var adapter: RecordListAdapter? = null

    data class RecordItem(
        val title: String,
        val url: String,
        val time: String
    )

    fun show(anchor: View) {
        val records = CastRecordMgr.getRecords(context)
        if (records.isEmpty()) {
            ToastMgr.shortBottomCenter(context, "暂无投屏记录")
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.layout_live, null)
        val dm = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
        windowManager!!.defaultDisplay.getMetrics(dm)
        val width = max(dm.widthPixels, dm.heightPixels)
        val height = min(dm.widthPixels, dm.heightPixels)

        val fontSize = width / 42
        AppConfig.fontSize = fontSize

        val groupView = view.findViewById<ListView>(R.id.lv_live_group)
        groupView.visibility = View.GONE

        listView = view.findViewById(R.id.lv_setting_right)
        val itemList = records.map {
            val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            RecordItem(it.title, it.url, dateFormat.format(Date(it.time)))
        }
        adapter = RecordListAdapter(context, itemList)
        listView!!.adapter = adapter

        val tvTitle = view.findViewById<TextView>(R.id.tv_setting)
        tvTitle.text = "投屏记录"
        tvTitle.visibility = View.VISIBLE
        tvTitle.setTextColor(Color.WHITE)
        tvTitle.textSize = (fontSize + 3).toFloat()

        listView!!.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos < records.size) {
                clickListener(records[pos])
                hide()
            }
        }

        AppConfig.liveHeight = (height - 12) / 8 - listView!!.dividerHeight + 1

        popupWindow = PopupWindow(view, width / 2, ViewGroup.LayoutParams.WRAP_CONTENT)
        popupWindow?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.isFocusable = true
            it.isOutsideTouchable = true
            it.update()
            it.showAtLocation(anchor, Gravity.CENTER, 0, 0)
        }
        listView?.requestFocus()
    }

    fun isShowing(): Boolean {
        return popupWindow?.isShowing == true
    }

    fun hide() {
        popupWindow?.dismiss()
    }

    inner class RecordListAdapter(
        private val context: Context,
        private val items: List<RecordItem>
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_list_left, parent, false)
            val item = items[position]
            val textView = view.findViewById<TextView>(android.R.id.text1)
            textView.text = item.title
            textView.setTextColor(Color.WHITE)
            textView.textSize = (AppConfig.fontSize * 0.8f).toFloat()

            val subTextView = view.findViewById<TextView>(android.R.id.text2)
            subTextView?.let {
                it.text = item.time
                it.setTextColor(Color.GRAY)
                it.textSize = (AppConfig.fontSize * 0.6f).toFloat()
            }
            return view
        }
    }
}