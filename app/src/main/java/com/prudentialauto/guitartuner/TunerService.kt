package com.prudentialauto.guitartuner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.math.max

class TunerService : Service() {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRunning = false

    private val pitchDetector = PitchDetector(SAMPLE_RATE)

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_ID = "guitar_tuner_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.prudentialauto.guitartuner.STOP_SERVICE"
        const val PREFS_NAME = "tuner_prefs"
        const val PREF_IS_RUNNING = "is_running"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        startRecording()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_RUNNING, true).apply()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_RUNNING, false).apply()
        // Reset widget to idle state
        broadcastUpdate("--", 0f, 0f, false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording() {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = max(minBytes, 16384)   // at least ~186 ms of audio
        val bufferShorts = bufferBytes / 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            stopSelf()
            return
        }

        audioRecord?.startRecording()
        isRunning = true

        recordingThread = Thread({
            val buffer = ShortArray(bufferShorts)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, bufferShorts) ?: break
                if (read > 0) {
                    val freq = pitchDetector.detect(buffer, read)
                    val info = NoteUtils.fromFrequency(freq)
                    broadcastUpdate(info.name, info.frequency, info.cents, true)
                }
            }
        }, "TunerThread")
        recordingThread?.start()
    }

    private fun stopRecording() {
        isRunning = false
        recordingThread?.join(2000)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun broadcastUpdate(
        note: String,
        frequency: Float,
        cents: Float,
        isRecording: Boolean
    ) {
        sendBroadcast(Intent(GuitarTunerWidget.ACTION_TUNER_UPDATE).apply {
            putExtra(GuitarTunerWidget.EXTRA_NOTE, note)
            putExtra(GuitarTunerWidget.EXTRA_FREQUENCY, frequency)
            putExtra(GuitarTunerWidget.EXTRA_CENTS, cents)
            putExtra(GuitarTunerWidget.EXTRA_IS_RECORDING, isRecording)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Guitar Tuner",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while the guitar tuner is listening"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, TunerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guitar Tuner Active")
            .setContentText("Listening for guitar notes…")
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
