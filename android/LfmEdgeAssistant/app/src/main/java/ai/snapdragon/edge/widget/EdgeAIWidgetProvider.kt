package ai.snapdragon.edge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.RemoteViews
import ai.snapdragon.edge.AssistantClient
import ai.snapdragon.edge.FloatingAssistantActivity
import ai.snapdragon.edge.MainActivity
import ai.snapdragon.edge.R
import java.util.concurrent.Executors

class EdgeAIWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_TOGGLE_ENGINE = "ai.snapdragon.edge.widget.TOGGLE_ENGINE"
        private val executor = Executors.newSingleThreadExecutor()

        fun updateAllWidgets(context: Context) {
            val app = context.applicationContext
            executor.execute {
                val ready = AssistantClient.isReady()
                render(app, ready, false)
            }
        }

        fun render(context: Context, ready: Boolean, transitioning: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, EdgeAIWidgetProvider::class.java))
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_edge_ai)

                when {
                    transitioning -> {
                        views.setTextViewText(R.id.btn_widget_power, "⏳ Starting…")
                        views.setTextColor(R.id.btn_widget_power, Color.parseColor("#8D5B4C"))
                        views.setInt(R.id.btn_widget_power, "setBackgroundResource", R.drawable.widget_badge_resting)
                        views.setTextViewText(R.id.tv_widget_title, "Waking on-device\nengine…")
                    }
                    ready -> {
                        views.setTextViewText(R.id.btn_widget_power, "● Active  ⏸")
                        views.setTextColor(R.id.btn_widget_power, Color.parseColor("#2E6F40"))
                        views.setInt(R.id.btn_widget_power, "setBackgroundResource", R.drawable.widget_badge_active)
                        views.setTextViewText(R.id.tv_widget_title, "On-device AI,\nready to talk.")
                    }
                    else -> {
                        views.setTextViewText(R.id.btn_widget_power, "⚡ Start Engine")
                        views.setTextColor(R.id.btn_widget_power, Color.parseColor("#5C3A82"))
                        views.setInt(R.id.btn_widget_power, "setBackgroundResource", R.drawable.widget_badge_resting)
                        views.setTextViewText(R.id.tv_widget_title, "On-device AI,\nright here.")
                    }
                }

                fun openApp(request: Int, destination: String): PendingIntent {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        action = "ai.snapdragon.edge.OPEN_$destination"
                        putExtra("destination", destination)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }

                fun openFloating(request: Int, autoListen: Boolean): PendingIntent {
                    val intent = Intent(context, FloatingAssistantActivity::class.java).apply {
                        action = "ai.snapdragon.edge.OPEN_FLOATING"
                        putExtra("auto_listen", autoListen)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }

                val toggleIntent = Intent(context, EdgeAIWidgetProvider::class.java).apply {
                    action = ACTION_TOGGLE_ENGINE
                }
                val togglePi = PendingIntent.getBroadcast(
                    context,
                    103,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                views.setOnClickPendingIntent(R.id.widget_root, openApp(100, "home"))
                views.setOnClickPendingIntent(R.id.btn_speak, openFloating(101, true))
                views.setOnClickPendingIntent(R.id.btn_ideas, openFloating(102, false))
                views.setOnClickPendingIntent(R.id.btn_widget_power, togglePi)

                manager.updateAppWidget(id, views)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_ENGINE) {
            val pending = goAsync()
            val appContext = context.applicationContext
            executor.execute {
                try {
                    val currentlyReady = AssistantClient.isReady()
                    if (currentlyReady) {
                        // User requested stop
                        AssistantClient.stopEngine(appContext)
                        Thread.sleep(800)
                        render(appContext, ready = false, transitioning = false)
                    } else {
                        // User requested start
                        render(appContext, ready = false, transitioning = true)
                        AssistantClient.startEngine(appContext)
                        // Poll until ready or max 12 seconds
                        var ready = false
                        for (i in 0 until 8) {
                            Thread.sleep(1500)
                            if (AssistantClient.isReady()) {
                                ready = true
                                break
                            }
                        }
                        render(appContext, ready = ready, transitioning = false)
                    }
                } finally {
                    pending.finish()
                }
            }
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        executor.execute {
            try {
                render(context.applicationContext, AssistantClient.isReady(), false)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }
}
