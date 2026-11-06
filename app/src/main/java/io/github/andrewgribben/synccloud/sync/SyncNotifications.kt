package io.github.andrewgribben.synccloud.sync

import android.accounts.Account
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.andrewgribben.synccloud.MainActivity
import io.github.andrewgribben.synccloud.R

/** The only notification Synccloud sends (wireframe B4): iCloud rejected the password. */
object SyncNotifications {
    private const val CHANNEL_ID = "password_needed"

    /** One notification per account, so a second failing account doesn't replace the first's. */
    private fun notificationId(account: Account) = account.name.hashCode()

    fun notifyPasswordNeeded(context: Context, account: Account) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Password needed", NotificationManager.IMPORTANCE_HIGH)
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_REAUTH_ACCOUNT, account)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId(account), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle("Synccloud · ${account.name}")
            .setContentText("iCloud stopped accepting the password. Tap to enter a new app-specific password.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(notificationId(account), notification)
    }

    fun cancelPasswordNeeded(context: Context, account: Account) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(account))
    }
}
