package com.example.eye_care_2026

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EyeCareServiceState(
    val isRunning: Boolean = false,
    val remainingSeconds: Int = EyeCareService.WORK_DURATION_SECONDS,
    val isResting: Boolean = false,
    val restRemainingSeconds: Int = EyeCareService.REST_DURATION_SECONDS,
    val isPausedByScreenOff: Boolean = false
)

class EyeCareService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var testOverlayJob: Job? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerScreenStateReceiver()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                startForegroundServiceWithNotification()
                startTimer()
            }
            ACTION_STOP -> {
                stopEyeCareService()
            }
            ACTION_TEST_OVERLAY -> {
                startForegroundServiceWithNotification()
                runTestOverlay()
            }
        }

        return START_STICKY
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenStateReceiver, filter)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification("Next eye rest break in: ${formatTime(WORK_DURATION_SECONDS)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTimer() {
        _serviceState.value = EyeCareServiceState(
            isRunning = true,
            remainingSeconds = WORK_DURATION_SECONDS,
            isResting = false,
            restRemainingSeconds = REST_DURATION_SECONDS,
            isPausedByScreenOff = false
        )

        startTimerJob()
    }

    private fun startTimerJob() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (true) {
                delay(1000L)

                val currentState = _serviceState.value
                if (!currentState.isRunning || currentState.isPausedByScreenOff) {
                    continue
                }

                if (currentState.isResting) {
                    val newRestRemaining = currentState.restRemainingSeconds - 1
                    if (newRestRemaining <= 0) {
                        // 20-second break finished!
                        OverlayManager.dismissOverlay()
                        _serviceState.value = currentState.copy(
                            isResting = false,
                            remainingSeconds = WORK_DURATION_SECONDS,
                            restRemainingSeconds = REST_DURATION_SECONDS
                        )
                        updateNotification("Next eye rest break in: ${formatTime(WORK_DURATION_SECONDS)}")
                    } else {
                        _serviceState.value = currentState.copy(restRemainingSeconds = newRestRemaining)
                        OverlayManager.updateTimer(newRestRemaining)
                        updateNotification("Rest your eyes! Look 20ft away (${formatTime(newRestRemaining)})")
                    }
                } else {
                    val newRemaining = currentState.remainingSeconds - 1
                    if (newRemaining <= 0) {
                        // 20-minute timer reached zero!
                        playAlertSoundAndVibration()
                        OverlayManager.showOverlay(
                            context = this@EyeCareService,
                            durationSeconds = REST_DURATION_SECONDS,
                            onDismiss = {
                                handleBreakDismissed()
                            }
                        )
                        _serviceState.value = currentState.copy(
                            remainingSeconds = 0,
                            isResting = true,
                            restRemainingSeconds = REST_DURATION_SECONDS
                        )
                        updateNotification("Rest your eyes! Look 20ft away (${formatTime(REST_DURATION_SECONDS)})")
                    } else {
                        _serviceState.value = currentState.copy(remainingSeconds = newRemaining)
                        updateNotification("Next eye rest break in: ${formatTime(newRemaining)}")
                    }
                }
            }
        }
    }

    private fun handleScreenOff() {
        if (OverlayManager.isShowing()) {
            OverlayManager.dismissOverlay()
        }
        _serviceState.value = _serviceState.value.copy(
            remainingSeconds = WORK_DURATION_SECONDS,
            isResting = false,
            restRemainingSeconds = REST_DURATION_SECONDS,
            isPausedByScreenOff = true
        )
        updateNotification("Screen off - Eye rest timer paused & reset")
    }

    private fun handleScreenOn() {
        val currentState = _serviceState.value
        if (currentState.isRunning) {
            _serviceState.value = currentState.copy(
                remainingSeconds = WORK_DURATION_SECONDS,
                isResting = false,
                restRemainingSeconds = REST_DURATION_SECONDS,
                isPausedByScreenOff = false
            )
            updateNotification("Next eye rest break in: ${formatTime(WORK_DURATION_SECONDS)}")
        }
    }

    private fun handleBreakDismissed() {
        val currentState = _serviceState.value
        if (currentState.isResting) {
            _serviceState.value = currentState.copy(
                isResting = false,
                remainingSeconds = WORK_DURATION_SECONDS,
                restRemainingSeconds = REST_DURATION_SECONDS
            )
            updateNotification("Next eye rest break in: ${formatTime(WORK_DURATION_SECONDS)}")
        }
    }

    private fun runTestOverlay() {
        playAlertSoundAndVibration()
        testOverlayJob?.cancel()

        var testRemaining = 10
        OverlayManager.showOverlay(
            context = this,
            durationSeconds = testRemaining,
            onDismiss = {
                testOverlayJob?.cancel()
                testOverlayJob = null
            }
        )

        testOverlayJob = serviceScope.launch {
            while (testRemaining > 0) {
                delay(1000L)
                testRemaining--
                if (testRemaining > 0) {
                    OverlayManager.updateTimer(testRemaining)
                } else {
                    OverlayManager.dismissOverlay()
                }
            }
        }
    }

    private fun playAlertSoundAndVibration() {
        try {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, soundUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 200, 100, 200), -1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopEyeCareService() {
        timerJob?.cancel()
        testOverlayJob?.cancel()
        if (OverlayManager.isShowing()) {
            OverlayManager.dismissOverlay()
        }
        _serviceState.value = EyeCareServiceState(isRunning = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Eye Care Rest Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active countdown timer for eye rest breaks."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eye Care 20-20-20 Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        OverlayManager.dismissOverlay()
        serviceScope.cancel()
        _serviceState.value = EyeCareServiceState(isRunning = false)
        super.onDestroy()
    }

    companion object {
        const val WORK_DURATION_SECONDS = 20 * 60 // 20 minutes = 1200 seconds
        const val REST_DURATION_SECONDS = 20      // 20 seconds
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "eyecare_notification_channel"

        const val ACTION_START = "com.example.eye_care_2026.action.START"
        const val ACTION_STOP = "com.example.eye_care_2026.action.STOP"
        const val ACTION_TEST_OVERLAY = "com.example.eye_care_2026.action.TEST_OVERLAY"

        private val _serviceState = MutableStateFlow(EyeCareServiceState())
        val serviceState: StateFlow<EyeCareServiceState> = _serviceState.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, EyeCareService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, EyeCareService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerTestOverlay(context: Context) {
            val intent = Intent(context, EyeCareService::class.java).apply {
                action = ACTION_TEST_OVERLAY
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun formatTime(seconds: Int): String {
            val m = seconds / 60
            val s = seconds % 60
            return String.format(java.util.Locale.getDefault(), "%02d:%02d", m, s)
        }
    }
}
