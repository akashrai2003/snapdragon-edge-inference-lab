package ai.snapdragon.edge.widget

import ai.snapdragon.edge.AssistantClient
import ai.snapdragon.edge.FloatingAssistantActivity
import ai.snapdragon.edge.MainActivity
import ai.snapdragon.edge.R
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import java.util.concurrent.Executors

class EdgeAIWidgetProvider : AppWidgetProvider() {
    companion object {
        private val worker = Executors.newSingleThreadExecutor()

        fun updateAllWidgets(context: Context) {
            val app = context.applicationContext
            worker.execute { render(app, AssistantClient.isReady()) }
        }

        private fun render(context: Context, ready: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            manager
                .getAppWidgetIds(ComponentName(context, EdgeAIWidgetProvider::class.java))
                .forEach { id ->
                    val small =
                        manager
                            .getAppWidgetOptions(id)
                            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140) < 170
                    val views =
                        RemoteViews(
                            context.packageName,
                            if (small) R.layout.widget_edge_ai_compact else R.layout.widget_edge_ai,
                        )
                    views.setTextViewText(
                        R.id.btn_widget_power,
                        if (ready) "• Ready" else "• Resting",
                    )
                    views.setContentDescription(
                        R.id.btn_widget_power,
                        "Assistant status and settings",
                    )
                    views.setTextViewText(
                        R.id.tv_widget_title,
                        if (small) "A little help, right here." else "A little help,\nright here.",
                    )
                    fun open(
                        request: Int,
                        activity: Class<*>,
                        listen: Boolean = false,
                        destination: String = "home",
                    ): PendingIntent {
                        val intent =
                            Intent(context, activity).apply {
                                action = "ai.snapdragon.edge.MIRA_$request"
                                putExtra("auto_listen", listen)
                                putExtra("destination", destination)
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                        return PendingIntent.getActivity(
                            context,
                            request,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    }
                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        open(100, MainActivity::class.java),
                    )
                    views.setOnClickPendingIntent(
                        R.id.btn_speak,
                        open(101, FloatingAssistantActivity::class.java),
                    )
                    views.setOnClickPendingIntent(
                        R.id.btn_ideas,
                        open(102, FloatingAssistantActivity::class.java, true),
                    )
                    views.setOnClickPendingIntent(
                        R.id.btn_widget_power,
                        open(103, MainActivity::class.java, destination = "settings"),
                    )
                    manager.updateAppWidget(id, views)
                }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        worker.execute {
            try {
                render(context.applicationContext, AssistantClient.isReady())
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) {
        onUpdate(context, manager, intArrayOf(id))
    }
}
