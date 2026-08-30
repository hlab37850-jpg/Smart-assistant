package com.smartassistant.app.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.smartassistant.app.MainActivity
import com.smartassistant.app.R
import com.smartassistant.app.data.local.AppDatabase
import com.smartassistant.app.data.local.entity.AppNotification
import com.smartassistant.app.data.local.entity.DueDate
import com.smartassistant.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class NotificationHelper(private val ctx: Context) {
    companion object { const val CH_DUES = "dues"; const val CH_STOCK = "stock"; const val CH_SYS = "system" }
    fun post(channel: String, title: String, body: String, route: String, id: Int) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("route", route)
        }
        val pi = PendingIntent.getActivity(ctx, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle(title)
            .setContentText(body).setAutoCancel(true).setContentIntent(pi).build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

object ReminderBuilder {
    const val DEFAULT_TEMPLATE =
        "تحية طيبة، معكم {shop} ({address} - هاتف: {phone}).\n" +
        "الأستاذ/ة {customer} المحترم، نفيدكم بأن رصيدكم الحالي: {balance}.\n" +
        "موعد الاستحقاق: {due}. نرجو التكرم بالمتابعة، وشكراً لثقتكم."
    fun build(shop: String, customer: String, balance: Double, date: String, time: String,
              phone: String, address: String, template: String = DEFAULT_TEMPLATE): String =
        template.replace("{shop}", shop).replace("{customer}", customer)
            .replace("{balance}", Fmt.money(balance))
            .replace("{due}", "$date $time").replace("{phone}", phone).replace("{address}", address)
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dueId = inputData.getLong("dueId", -1)
        if (dueId < 0) return@withContext Result.failure()
        val label = inputData.getString("label") ?: "تذكير"
        val db = AppDatabase.get(applicationContext)
        val due = db.dueDao().byId(dueId) ?: return@withContext Result.failure()
        val cust = db.customerDao().byId(due.customerId) ?: return@withContext Result.failure()
        val shop = db.shopDao().get()
        db.notificationDao().insert(AppNotification(
            type = "DUE_REMINDER", title = label,
            body = "${cust.name} — الرصيد: ${Fmt.money(cust.balance)} — الاستحقاق: ${due.date} ${due.time}",
            route = "customer/${cust.id}"))
        NotificationHelper(applicationContext).post(
            NotificationHelper.CH_DUES, label,
            ReminderBuilder.build(shop?.name ?: "المحل", cust.name, cust.balance,
                due.date, due.time, shop?.phone ?: "", shop?.address ?: ""),
            "customer/${cust.id}", dueId.toInt())
        Result.success()
    }
}

class DailyScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(applicationContext)
        val helper = NotificationHelper(applicationContext)
        val today = Fmt.today(); val tomorrow = Fmt.plusDays(1)
        val t = db.dueDao().today(today).firstOrNull() ?: emptyList()
        if (t.isNotEmpty()) {
            db.notificationDao().insert(AppNotification("DUE_TODAY", "استحقاقات اليوم", "${t.size} استحقاق اليوم", "dues"))
            helper.post(NotificationHelper.CH_DUES, "استحقاقات اليوم", "${t.size} عميل يستحق اليوم", "dues", 101)
        }
        val m = db.dueDao().tomorrow(tomorrow).firstOrNull() ?: emptyList()
        if (m.isNotEmpty())
            db.notificationDao().insert(AppNotification("DUE_TOMORROW", "استحقاق غداً", "${m.size} استحقاق غداً", "dues"))
        val od = db.dueDao().overdue(today).firstOrNull() ?: emptyList()
        if (od.isNotEmpty()) {
            db.notificationDao().insert(AppNotification("DUE_OVERDUE", "استحقاقات متأخرة", "${od.size} استحقاق متأخر", "dues"))
            helper.post(NotificationHelper.CH_DUES, "استحقاقات متأخرة", "${od.size} عميل متأخر", "dues", 102)
        }
        Result.success()
    }
}

object ReminderScheduler {
    enum class Slot(val label: String) {
        BEFORE_DAY("تذكير قبل الموعد بيوم"), BEFORE_HOURS("تذكير قبل الموعد بساعات"),
        AT("تذكير في وقت الاستحقاق"), AFTER_1("متابعة بعد يوم"),
        AFTER_3("متابعة بعد 3 أيام"), AFTER_7("متابعة بعد 7 أيام")
    }
    fun schedule(ctx: Context, due: DueDate) {
        val wm = WorkManager.getInstance(ctx)
        val base = LocalDateTime.of(LocalDate.parse(due.date), LocalTime.parse(due.time))
        val slots = mutableListOf<Pair<Slot, LocalDateTime>>()
        if (due.remBeforeDay == 1) slots += Slot.BEFORE_DAY to base.minusDays(1)
        if (due.remBeforeHours > 0) slots += Slot.BEFORE_HOURS to base.minusHours(due.remBeforeHours.toLong())
        if (due.remAt == 1) slots += Slot.AT to base
        if (due.remAfter1 == 1) slots += Slot.AFTER_1 to base.plusDays(1)
        if (due.remAfter3 == 1) slots += Slot.AFTER_3 to base.plusDays(3)
        if (due.remAfter7 == 1) slots += Slot.AFTER_7 to base.plusDays(7)
        for ((slot, at) in slots) {
            val delay = Duration.between(LocalDateTime.now(), at).toMillis()
            if (delay < 0) continue
            wm.enqueueUniqueWork("rem_${due.id}_${slot.name}", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("dueId" to due.id, "label" to slot.label)).build())
        }
    }
    fun scheduleDailyScan(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily_scan",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyScanWorker>(6, TimeUnit.HOURS).build())
    }
}
