package com.newoether.agora.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.util.CrashReporter
import com.newoether.agora.util.DebugLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process alive while a generation is in flight.
 *
 * **Critical race (historical crash):**
 * `GenerationManager` called `start()` at the beginning of every generate and
 * `stop()` in `finally`. With parallel multi-conversation generation (or a
 * failed generate that ends within a few hundred ms of start), `stopService`
 * could run **after** `startForegroundService` but **before** `onCreate` →
 * `startForeground`. Android then kills the app with
 * `ForegroundServiceDidNotStartInTimeException`.
 *
 * Fix: refcount active generations. Only tear down the service when the last
 * generation ends, and never `stopService` until `startForeground` has
 * succeeded (defer stop if needed).
 */
class AgoraForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "agora_generation_status"
        const val NOTIFICATION_ID = 1
        private const val COMPLETION_CHANNEL_ID = "agora_completed"
        private const val COMPLETION_NOTIFICATION_ID = 2
        private const val TAG = "AgoraForegroundService"

        @Volatile private var instance: AgoraForegroundService? = null

        /** Number of in-flight generations that want the FGS kept up. */
        private val activeGenerations = AtomicInteger(0)

        /** True once this process has successfully promoted the service. */
        private val foregroundReady = AtomicBoolean(false)

        /**
         * stop() arrived before startForeground finished — honour it after promote.
         * Cleared when a new start() bumps the refcount again.
         */
        private val stopAfterPromote = AtomicBoolean(false)

        /**
         * True between issuing `stopService()` and the service actually being destroyed.
         *
         * `stopService()` is asynchronous: during that window `instance` is still set and
         * [foregroundReady] is still true, so [start]'s fast path would hand back a service that
         * is about to disappear. The generation count would then claim a live foreground service
         * while the process quietly loses its foreground protection and becomes eligible for
         * background eviction — which looks exactly like a crash to the user, except no exception
         * is ever thrown and no crash report is written.
         */
        private val teardownInFlight = AtomicBoolean(false)

        fun start(context: Context): Boolean {
            val appContext = context.applicationContext
            val count = activeGenerations.incrementAndGet()
            stopAfterPromote.set(false)
            val state = processState()
            CrashReporter.note("FGS.start count=$count api=${Build.VERSION.SDK_INT} $state ready=${foregroundReady.get()}")
            // Already promoted and running — just refresh the notification text.
            // Skipped while a teardown is in flight: the service still looks alive but is on its
            // way out, and returning true here would leave us with no foreground service at all.
            if (foregroundReady.get() && instance != null && !teardownInFlight.get()) {
                instance?.updateNotificationText(instance?.currentText ?: "Generating response...")
                return true
            }
            val intent = Intent(appContext, AgoraForegroundService::class.java)
            return try {
                createChannel(appContext)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                CrashReporter.note("FGS.startService ok count=$count")
                true
            } catch (e: RuntimeException) {
                // Could not start — drop the ref so we don't leave a sticky count.
                activeGenerations.updateAndGet { (it - 1).coerceAtLeast(0) }
                CrashReporter.note("FGS.startService threw ${e.javaClass.simpleName}: ${e.message}")
                DebugLog.w(TAG, "Failed to start foreground service", e)
                false
            }
        }

        fun updateText(text: String) {
            instance?.updateNotificationText(text)
        }

        fun stop(context: Context) {
            val remaining = activeGenerations.updateAndGet { (it - 1).coerceAtLeast(0) }
            CrashReporter.note(
                "FGS.stop remaining=$remaining ready=${foregroundReady.get()} hasInstance=${instance != null}",
            )
            if (remaining > 0) return

            val svc = instance
            if (svc != null && foregroundReady.get()) {
                // Safe: already promoted. Stop normally.
                teardownInFlight.set(true)
                try {
                    context.applicationContext.stopService(
                        Intent(context.applicationContext, AgoraForegroundService::class.java),
                    )
                } catch (e: RuntimeException) {
                    teardownInFlight.set(false)
                    DebugLog.w(TAG, "stopService failed", e)
                }
            } else {
                // startForegroundService was issued but onCreate/startForeground has not
                // finished (or failed). Do NOT call stopService yet — that would leave the
                // system waiting for startForeground and crash with DidNotStartInTime.
                // Promote path will stopSelf once ready if this flag is still set.
                stopAfterPromote.set(true)
                CrashReporter.note("FGS.stop deferred until after promote")
            }
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Generation",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing notification while Agora is generating"
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        fun showCompletionNotification(context: Context, responseText: String) {
            createCompletionChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.agora_responded))
                .setContentText(if (responseText.length > 200) responseText.take(200) + "…" else responseText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(createPendingIntent(context, 1))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        if (responseText.length > 200) responseText.take(200) + "…" else responseText,
                    ),
                )
                .build()

            try {
                manager.notify(COMPLETION_NOTIFICATION_ID, notification)
            } catch (e: RuntimeException) {
                DebugLog.w(TAG, "Failed to show completion notification", e)
            }
        }

        private fun createCompletionChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "Response Ready",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Shown when a response finishes generating"
                setShowBadge(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        private fun createPendingIntent(context: Context, requestCode: Int): PendingIntent {
            return PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun processState(): String = try {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            "importance=${info.importance} trim=${info.lastTrimLevel}"
        } catch (_: Exception) {
            "importance=?"
        }
    }

    private var currentText: String = "Generating response…"
    private var foregroundStarted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.note("FGS.onCreate count=${activeGenerations.get()}")
        // A fresh instance is coming up; any previous teardown is over.
        teardownInFlight.set(false)
        createChannel(this)
        promoteToForeground("onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Belt-and-suspenders: always re-assert foreground here. If onCreate already
        // promoted, this is a cheap no-op path; if something delayed onCreate's promote
        // or the process was restarted mid-start, this still beats the 5s deadline.
        if (!foregroundStarted) {
            promoteToForeground("onStartCommand")
        }
        // If stop() raced ahead of promote, honour it now that we are safe.
        maybeStopIfRequested("onStartCommand")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        CrashReporter.note("FGS.onDestroy")
        teardownInFlight.set(false)
        if (instance === this) instance = null
        foregroundStarted = false
        foregroundReady.set(false)
        // If we were destroyed while gens still claim us, clear the sticky stop flag.
        // Next start() will re-promote.
        if (activeGenerations.get() <= 0) {
            stopAfterPromote.set(false)
        }
    }

    private fun promoteToForeground(from: String) {
        if (foregroundStarted) return
        val notification = buildGenerationNotification(currentText)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType(),
            )
            foregroundStarted = true
            foregroundReady.set(true)
            CrashReporter.note("FGS.startForeground ok from=$from count=${activeGenerations.get()}")
            teardownInFlight.set(false)
        } catch (e: Exception) {
            // Promote failed (permission / background start denied / etc.). Stop cleanly
            // so the system does not wait out the 5s timeout and crash the process with a
            // useless DidNotStartInTime exception.
            CrashReporter.note("FGS.startForeground FAIL from=$from ${e.javaClass.simpleName}: ${e.message}")
            DebugLog.w(TAG, "startForeground failed from=$from", e)
            foregroundStarted = false
            foregroundReady.set(false)
            activeGenerations.set(0)
            stopAfterPromote.set(false)
            stopSelf()
            return
        }
        maybeStopIfRequested("afterPromote:$from")
    }

    private fun maybeStopIfRequested(from: String) {
            if (activeGenerations.get() > 0) {
                // A new generation claimed us — cancel any deferred stop.
                stopAfterPromote.set(false)
                return
            }
            // Only stop if stop() already asked us to, and we have safely promoted.
            if (stopAfterPromote.getAndSet(false) && foregroundStarted) {
                CrashReporter.note("FGS.stopSelf from=$from")
                stopSelf()
            }
        }

    private fun updateNotificationText(text: String) {
        currentText = text
        if (!foregroundStarted) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildGenerationNotification(text))
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "Failed to update notification", e)
        }
    }

    private fun buildGenerationNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(createPendingIntent(this, 0))
            .build()
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }

    override fun onTimeout(startId: Int) {
        // API 35+: dataSync FGS has a time budget. Drop cleanly.
        CrashReporter.note("FGS.onTimeout startId=$startId count=${activeGenerations.get()}")
        DebugLog.w(TAG, "Foreground service timed out startId=$startId")
        activeGenerations.set(0)
        stopAfterPromote.set(false)
        stopSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun onTimeout(startId: Int, fgsType: Int) {
        CrashReporter.note("FGS.onTimeout startId=$startId type=$fgsType count=${activeGenerations.get()}")
        DebugLog.w(TAG, "Foreground service timed out: startId=$startId type=$fgsType")
        activeGenerations.set(0)
        stopAfterPromote.set(false)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
