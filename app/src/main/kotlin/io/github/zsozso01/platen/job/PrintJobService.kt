package io.github.zsozso01.platen.job

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.zsozso01.platen.MainActivity
import io.github.zsozso01.platen.R
import io.github.zsozso01.platen.core.model.JobEvent
import android.app.PendingIntent

/**
 * Keeps the process alive while a job is running, so Android does not stop the app in the middle of a
 * page. The job itself runs in [JobManager]; this service only holds the foreground notification.
 */
class PrintJobService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = build(this, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "print_jobs"
        private const val NOTIFICATION_ID = 1

        fun intent(context: Context) = Intent(context, PrintJobService::class.java)

        fun stop(context: Context) {
            context.stopService(intent(context))
        }

        fun update(context: Context, state: JobUiState?) {
            if (state == null || !state.isRunning) return
            val manager = context.getSystemService(NotificationManager::class.java)
            runCatching { manager.notify(NOTIFICATION_ID, build(context, state)) }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_jobs), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        private fun build(context: Context, state: JobUiState?): Notification {
            val text = when (val e = state?.latest) {
                is JobEvent.Preparing -> context.getString(R.string.job_preparing, e.page, e.totalPages)
                is JobEvent.Sending -> context.getString(R.string.job_sending)
                is JobEvent.Printing -> context.getString(R.string.job_printing)
                JobEvent.NeedsReload -> context.getString(R.string.job_reload_title)
                else -> context.getString(R.string.job_starting)
            }
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(state?.documentName ?: context.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}
