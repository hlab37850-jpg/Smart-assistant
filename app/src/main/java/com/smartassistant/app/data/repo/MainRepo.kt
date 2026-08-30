package com.smartassistant.app.data.repo

import android.content.Context
import com.smartassistant.app.data.local.AppDatabase
import com.smartassistant.app.data.local.entity.ActivityLog
import com.smartassistant.app.data.local.entity.Category
import com.smartassistant.app.data.local.entity.Customer
import com.smartassistant.app.data.local.entity.CustomerNote
import com.smartassistant.app.data.local.entity.DueDate
import com.smartassistant.app.data.local.entity.Inventory
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.data.prefs.AppPrefs
import com.smartassistant.app.notifications.ReminderScheduler
import com.smartassistant.app.util.Fmt

class MainRepo(ctx: Context) {
    val db = AppDatabase.get(ctx)
    val prefs = AppPrefs.get(ctx)

    suspend fun log(action: String, details: String) =
        db.logDao().insert(ActivityLog(userName = "المدير", action = action, details = details))

    // ===== العملاء =====
    fun customersPaged(q: String?, filter: String?) = when {
        !q.isNullOrBlank() -> db.customerDao().searchPaged(q)
        !filter.isNullOrBlank() && filter != "ALL" -> db.customerDao().filterPaged(filter, Fmt.today())
        else -> db.customerDao().allPaged()
    }
    suspend fun saveCustomer(c: Customer) {
        if (c.id == 0L) db.customerDao().insert(c)
        else db.customerDao().update(c.copy(updatedAt = System.currentTimeMillis()))
        log(if (c.id == 0L) "إضافة عميل" else "تعديل عميل", c.name)
    }
    suspend fun customer(id: Long) = db.customerDao().byId(id)
    suspend fun archiveCustomer(id: Long) { db.customerDao().archive(id); log("أرشفة عميل", "#$id") }
    fun notes(cid: Long) = db.noteDao().forCustomer(cid)
    suspend fun addNote(cid: Long, text: String) {
        db.noteDao().insert(CustomerNote(customerId = cid, text = text)); log("إضافة ملاحظة", "عميل #$cid")
    }

    // ===== الأصناف =====
    fun productsPaged(q: String?) =
        if (q.isNullOrBlank()) db.productDao().allPaged() else db.productDao().searchPaged(q)
    suspend fun saveProduct(p: Product, qty: Double, minQty: Double, expiry: String?) {
        val id = if (p.id == 0L) db.productDao().insert(p) else { db.productDao().update(p); p.id }
        val inv = db.inventoryDao().byProduct(id)
        db.inventoryDao().upsert(Inventory(id = inv?.id ?: 0, productId = id,
            qty = qty, minQty = minQty, expiryDate = expiry))
        log(if (p.id == 0L) "إضافة صنف" else "تعديل صنف", p.nameRaw)
    }
    suspend fun product(id: Long) = db.productDao().byId(id)
    fun inventory(pid: Long) = db.inventoryDao().watch(pid)
    val categories = db.categoryDao().all()
    suspend fun saveCategory(c: Category) {
        if (c.id == 0L) db.categoryDao().insert(c) else db.categoryDao().update(c)
    }

    // ===== الاستحقاقات =====
    fun dues(tab: String) = when (tab) {
        "TODAY" -> db.dueDao().today(Fmt.today())
        "TOMORROW" -> db.dueDao().tomorrow(Fmt.plusDays(1))
        "OVERDUE" -> db.dueDao().overdue(Fmt.today())
        else -> db.dueDao().all()
    }
    val forgotten = db.dueDao().forgotten(Fmt.today())
    suspend fun saveDue(d: DueDate): Long {
        val id = db.dueDao().insert(d)
        ReminderScheduler.schedule(db.openHelper.writableDatabase.context ?: AppCtx, d.copy(id = id))
        log("تحديد موعد", "عميل #${d.customerId} بتاريخ ${d.date}")
        return id
    }
    suspend fun markFollowed(id: Long) { db.dueDao().markFollowed(id); log("تمت المتابعة", "موعد #$id (ليس سداداً)") }

    // ===== الإشعارات والنشاط =====
    val notifications = db.notificationDao().all()
    val unread = db.notificationDao().unread()
    suspend fun markRead(id: Long) = db.notificationDao().markRead(id)
    val recentActivity = db.logDao().recent()
}
