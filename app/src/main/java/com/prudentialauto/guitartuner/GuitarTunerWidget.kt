package com.prudentialauto.guitartuner

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class GuitarTunerWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_RECORDING = "com.prudentialauto.guitartuner.TOGGLE_RECORDING"
        const val ACTION_TUNER_UPDATE = "com.prudentialauto.guitartuner.TUNER_UPDATE"
        const val EXTRA_NOTE = "note"
        const val EXTRA_FREQUENCY = "frequency"
        const val EXTRA_CENTS = "cents"
        const val EXTRA_IS_RECORDING = "is_recording"

        private const val PREFS_NAME = "widget_state"
        private const val PREF_NOTE = "note"
        private const val PREF_FREQ = "frequency"
        private const val PREF_CENTS = "cents"
        private const val PREF_RECORDING = "is_recording"

        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, GuitarTunerWidget::class.java))
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            for (id in ids) {
                val minWidth = mgr.getAppWidgetOptions(id)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
                applyRemoteViews(
                    context, mgr, id,
                    note = prefs.getString(PREF_NOTE, "--") ?: "--",
                    frequency = prefs.getFloat(PREF_FREQ, 0f),
                    cents = prefs.getFloat(PREF_CENTS, 0f),
                    isRecording = prefs.getBoolean(PREF_RECORDING, false),
                    minWidthDp = minWidth
                )
            }
        }

        private fun applyRemoteViews(
            context: Context,
            mgr: AppWidgetManager,
            widgetId: Int,
            note: String,
            frequency: Float,
            cents: Float,
            isRecording: Boolean,
            minWidthDp: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.guitar_tuner_widget)

            // REC button click → toggle broadcast
            val togglePi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, GuitarTunerWidget::class.java).apply {
                    action = ACTION_TOGGLE_RECORDING
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_record, togglePi)

            // Note and Hz text
            views.setTextViewText(R.id.note_letter, note)
            views.setTextViewText(
                R.id.note_hz,
                if (frequency > 0f) "%.1f Hz".format(frequency) else ""
            )

            // Responsive text size based on widget width
            val noteTextSizeSp = when {
                minWidthDp < 120 -> 28f
                minWidthDp < 180 -> 38f
                else -> 52f
            }
            views.setTextViewTextSize(
                R.id.note_letter, TypedValue.COMPLEX_UNIT_SP, noteTextSizeSp
            )

            // Chevrons: sharp = green above, flat = red below
            val sharpVisible = isRecording && cents > 5f
            val flatVisible = isRecording && cents < -5f
            views.setViewVisibility(
                R.id.chevron_up,
                if (sharpVisible) View.VISIBLE else View.INVISIBLE
            )
            views.setViewVisibility(
                R.id.chevron_down,
                if (flatVisible) View.VISIBLE else View.INVISIBLE
            )

            // Record button state
            views.setImageViewResource(
                R.id.btn_record,
                if (isRecording) R.drawable.ic_rec_button_active else R.drawable.ic_rec_button
            )

            mgr.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (id in appWidgetIds) {
            val minWidth = appWidgetManager.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            applyRemoteViews(
                context, appWidgetManager, id,
                note = prefs.getString(PREF_NOTE, "--") ?: "--",
                frequency = prefs.getFloat(PREF_FREQ, 0f),
                cents = prefs.getFloat(PREF_CENTS, 0f),
                isRecording = prefs.getBoolean(PREF_RECORDING, false),
                minWidthDp = minWidth
            )
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val minWidth = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applyRemoteViews(
            context, appWidgetManager, appWidgetId,
            note = prefs.getString(PREF_NOTE, "--") ?: "--",
            frequency = prefs.getFloat(PREF_FREQ, 0f),
            cents = prefs.getFloat(PREF_CENTS, 0f),
            isRecording = prefs.getBoolean(PREF_RECORDING, false),
            minWidthDp = minWidth
        )
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Stop service when last widget instance is removed
        context.stopService(Intent(context, TunerService::class.java))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_RECORDING -> handleToggle(context)
            ACTION_TUNER_UPDATE -> handleTunerUpdate(context, intent)
        }
    }

    private fun handleToggle(context: Context) {
        val isRunning = context
            .getSharedPreferences(TunerService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(TunerService.PREF_IS_RUNNING, false)

        if (isRunning) {
            context.stopService(Intent(context, TunerService::class.java))
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TunerService::class.java)
                )
            } else {
                context.startActivity(
                    Intent(context, PermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun handleTunerUpdate(context: Context, intent: Intent) {
        val note = intent.getStringExtra(EXTRA_NOTE) ?: "--"
        val frequency = intent.getFloatExtra(EXTRA_FREQUENCY, 0f)
        val cents = intent.getFloatExtra(EXTRA_CENTS, 0f)
        val isRecording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)

        // Persist state so onUpdate / resize can restore it
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_NOTE, note)
            .putFloat(PREF_FREQ, frequency)
            .putFloat(PREF_CENTS, cents)
            .putBoolean(PREF_RECORDING, isRecording)
            .apply()

        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, GuitarTunerWidget::class.java))
        for (id in ids) {
            val minWidth = mgr.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            applyRemoteViews(
                context, mgr, id,
                note, frequency, cents, isRecording, minWidth
            )
        }
    }
}
