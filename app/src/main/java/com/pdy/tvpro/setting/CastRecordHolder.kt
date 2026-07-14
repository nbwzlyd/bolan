package com.pdy.tvpro.setting

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import com.pdy.tvpro.R
import com.pdy.tvpro.constants.AppConfig
import com.pdy.tvpro.model.CastRecord
import com.pdy.tvpro.util.CastRecordMgr
import com.pdy.tvpro.util.ToastMgr
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

        val view = LayoutInflater.from(context).inflate(R.layout.layout_cast_record, null)
        val dm = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
        windowManager!!.defaultDisplay.getMetrics(dm)
        val width = max(dm.widthPixels, dm.heightPixels)
        val height = min(dm.widthPixels, dm.heightPixels)

        val fontSize = width / 42
        AppConfig.fontSize = fontSize

        listView = view.findViewById(R.id.lv_cast_record)
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
        tvTitle.textSize = (fontSize * 0.5f).toFloat()

        listView!!.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos < records.size) {
                clickListener(records[pos])
                hide()
            }
        }

        AppConfig.liveHeight = (height - 12) / 8 - listView!!.dividerHeight + 1

        val popupHeight = height * 2 / 3
        popupWindow = PopupWindow(view, width / 2, popupHeight)
        popupWindow?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.isFocusable = true
            it.isOutsideTouchable = true
            it.update()
            it.showAtLocation(anchor, Gravity.BOTTOM, 0, 50)
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
                .inflate(R.layout.item_cast_record, parent, false)
            val item = items[position]
            val textView = view.findViewById<TextView>(R.id.tv_title)
            textView.text = item.title
            textView.setTextColor(Color.WHITE)
            textView.textSize = (AppConfig.fontSize * 0.4f).toFloat()

            val subTextView = view.findViewById<TextView>(R.id.tv_time)
            subTextView.text = item.time
            subTextView.setTextColor(Color.GRAY)
            subTextView.textSize = (AppConfig.fontSize * 0.3f).toFloat()
            return view
        }
    }
}