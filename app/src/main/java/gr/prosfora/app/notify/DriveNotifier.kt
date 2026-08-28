package gr.prosfora.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import gr.prosfora.app.MainActivity
import gr.prosfora.app.R
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.DriveWatch
import gr.prosfora.app.util.asMoney

object DriveNotifier {
    const val EXTRA_OPEN_DEBT_ID = "open_debt_id"
    const val EXTRA_OPEN_PENDING_INSTALLMENTS = "open_pending_installments"

    private const val CHANNEL = "drive_changes"
    private const val ID = 4711
    private const val DEBTS_ID = 4712

    fun notify(context: Context, changes: List<DriveWatch.Change>) {
        if (changes.isEmpty()) return
        if (!allowed(context)) {
            DebugLog.log("notify", "Drive notification ΔΕΝ στάλθηκε: Android notifications disabled/permission missing")
            return
        }
        ensureChannel(context)
        val added = changes.filterNot { it.removed }
        val removed = changes.filter { it.removed }
        val title = when {
            added.isNotEmpty() && removed.isNotEmpty() -> "Αλλαγές στον φάκελο του Drive"
            removed.isNotEmpty() -> "Διαγράφηκαν αρχεία από το Drive"
            added.size == 1 -> "Νέο αρχείο στο Drive"
            else -> "${added.size} νέα αρχεία στο Drive"
        }
        val lines = changes.map { change ->
            val who = change.author.ifBlank { "άγνωστος χρήστης" }
            if (change.removed) "Διαγράφηκε: ${change.file.name}" else "${change.file.name} — από $who"
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.take(6).forEach(style::addLine) })
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching {
            NotificationManagerCompat.from(context).notify(ID, builder.build())
            DebugLog.log("notify", "Drive notification στάλθηκε · changes=${changes.size}")
        }.onFailure {
            DebugLog.log("notify", "Drive notification ΑΠΕΤΥΧΕ: ${it.stackTraceToString().take(900)}")
        }
    }

    fun notifyDebts(
        context: Context,
        debts: List<DebtEntity>,
        openPendingInstallments: Boolean = false,
    ) {
        if (debts.isEmpty()) return
        if (!allowed(context)) {
            DebugLog.log("notify", "Debt notification ΔΕΝ στάλθηκε: Android notifications disabled/permission missing; debts=${debts.size}")
            return
        }
        ensureChannel(context)
        val total = debts.sumOf { it.amount }
        val lines = debts.map { debt -> "${debt.title} — ${debt.amount.asMoney()} · ${debt.createdBy}" }
        val firstDebtId = debts.first().id
        val open = PendingIntent.getActivity(
            context,
            firstDebtId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_DEBT_ID, firstDebtId)
                .putExtra(EXTRA_OPEN_PENDING_INSTALLMENTS, openPendingInstallments),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            openPendingInstallments && debts.size == 1 -> "Νέα οφειλή — επιλογή δόσεων"
            openPendingInstallments -> "${debts.size} νέες οφειλές — επιλογή δόσεων"
            debts.size == 1 -> "Νέα οφειλή στην κοινόχρηστη βάση"
            else -> "${debts.size} νέες οφειλές · ${total.asMoney()}"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.take(6).forEach(style::addLine) })
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching {
            NotificationManagerCompat.from(context).notify(DEBTS_ID, builder.build())
            DebugLog.log(
                "notify",
                "Debt notification στάλθηκε · debts=${debts.size} · pendingInstallments=$openPendingInstallments · firstDebt=$firstDebtId",
            )
        }.onFailure {
            DebugLog.log("notify", "Debt notification ΑΠΕΤΥΧΕ: ${it.stackTraceToString().take(900)}")
        }
    }

    private fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Αλλαγές στο Drive", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Νέα ή διαγραμμένα αρχεία και οφειλές"
            },
        )
    }
}
