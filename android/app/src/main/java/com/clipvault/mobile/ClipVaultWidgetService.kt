package com.clipvault.mobile

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject

class ClipVaultWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ClipVaultWidgetFactory(this.applicationContext)
    }
}

class ClipVaultWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var clips: List<String> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        clips = NativeClipStore.recent(context, 10)
    }

    override fun onDestroy() {}

    override fun getCount(): Int = clips.size

    override fun getViewAt(position: Int): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.widget_list_item)
        if (position < clips.size) {
            val clipText = clips[position]
            view.setTextViewText(R.id.widget_item_text, clipText)

            val fillInIntent = Intent()
            view.setOnClickFillInIntent(R.id.widget_item_text, fillInIntent)
        }
        return view
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
