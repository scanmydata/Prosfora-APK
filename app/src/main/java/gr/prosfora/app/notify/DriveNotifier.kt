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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.absoluteValue

object DriveNotifier {
    const val EXTRA_OPEN_DEBT_ID = "open_debt_id"
    const val EXTRA_OPEN_PENDING_INSTALLMENTS = "open_pending_installments"

    private const val CHANNEL = "drive_changes"
    private const val ID = 4711
    private const val DEBTS_ID = 4712
    private const val DAILY_UNPAID_ID = 4713
    private const val PREFS = "debt_notifications"
    private const val LAST_UNPAID_NOTIFICATION_DATE = "last_unpaid_notification_date"

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
        val notificationId = DEBTS_ID + firstDebtId.hashCode().absoluteValue % 100_000
        val open = PendingIntent.getActivity(
            context,
            notificationId,
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            DebugLog.log(
                "notify",
                "Debt notification στάλθηκε ΑΜΕΣΑ · debts=${debts.size} · pendingInstallments=$openPendingInstallments · firstDebt=$firstDebtId · notificationId=$notificationId",
            )
        }.onFailure {
            DebugLog.log("notify", "Debt notification ΑΠΕΤΥΧΕ: ${it.stackTraceToString().take(900)}")
        }
    }

    /** Sends at most one reminder per Europe/Athens calendar day while unpaid debts exist. */
    fun notifyUnpaidDebtsDaily(context: Context, debts: List<DebtEntity>) {
        if (debts.isEmpty()) return
        if (!allowed(context)) {
            DebugLog.log("notify", "Daily unpaid notification ΔΕΝ στάλθηκε: Android notifications disabled/permission missing")
            return
        }

        val today = LocalDate.now(ZoneId.of("Europe/Athens")).toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(LAST_UNPAID_NOTIFICATION_DATE, "") == today) {
            DebugLog.log("notify", "Daily unpaid notification SKIP · already sent today=$today")
            return
        }

        ensureChannel(context)
        val ordered = debts.sortedWith(compareBy<DebtEntity>({ it.dueDay ?: Long.MAX_VALUE }, { it.periodYear }, { it.periodMonth }, { it.kind.ordinal }))
        val total = ordered.sumOf { it.amount }
        val lines = ordered.map { debt -> "${debt.title} — ${debt.amount.asMoney()}" }
        val firstDebtId = ordered.first().id
        val open = PendingIntent.getActivity(
            context,
            DAILY_UNPAID_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_DEBT_ID, firstDebtId)
                .putExtra(EXTRA_OPEN_PENDING_INSTALLMENTS, false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (ordered.size == 1) {
            "Υπάρχει 1 απλήρωτη οφειλή"
        } else {
            "Υπάρχουν ${ordered.size} απλήρωτες οφειλές"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("Σύνολο: ${total.asMoney()}")
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.take(8).forEach(style::addLine) })
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        runCatching {
            NotificationManagerCompat.from(context).notify(DAILY_UNPAID_ID, builder.build())
            prefs.edit().putString(LAST_UNPAID_NOTIFICATION_DATE, today).apply()
            DebugLog.log("notify", "Daily unpaid notification στάλθηκε · debts=${ordered.size} · total=${total.asMoney()} · date=$today")
        }.onFailure {
            DebugLog.log("notify", "Daily unpaid notification ΑΠΕΤΥΧΕ: ${it.stackTraceToString().take(900)}")
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
            NotificationChannel(CHANNEL, "Αλλαγές στο Drive", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Νέα ή διαγραμμένα αρχεία και οφειλές"
            },
        )
    }
}
