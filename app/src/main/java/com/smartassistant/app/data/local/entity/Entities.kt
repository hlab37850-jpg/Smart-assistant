package com.smartassistant.app.data.local.entity

import androidx.room.*

@Entity(tableName = "customers", indices = [
    Index(value = ["name"]),
    Index(value = ["phone"]),
    Index(value = ["code"])
])
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalId: String? = null,
    val name: String,
    val phone: String? = null,
    val code: String? = null,
    val address: String? = null,
    val balance: Double = 0.0,
    val rawBalance: String? = null,
    val currency: String? = null,
    val archived: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "customer_notes")
data class CustomerNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "dues", indices = [
    Index(value = ["date"]),
    Index(value = ["customerId"])
])
data class DueDate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val date: String,
    val time: String,
    val remBeforeDay: Int = 1,
    val remBeforeHours: Int = 0,
    val remAt: Int = 1,
    val remAfter1: Int = 0,
    val remAfter3: Int = 0,
    val remAfter7: Int = 0,
    val followedUp: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "notifications", indices = [Index(value = ["read"])])
data class AppNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val body: String,
    val route: String,
    val read: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val archived: Int = 0,
)

@Entity(tableName = "products", indices = [
    Index(value = ["nameRaw"]),
    Index(value = ["code"])
])
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalId: String? = null,
    val nameRaw: String,
    val code: String? = null,
    val categoryId: Long? = null,
    val unit: String? = null,
    val archived: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "inventory", indices = [
    Index(value = ["productId"]),
    Index(value = ["qty"])
])
data class Inventory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val qty: Double = 0.0,
    val minQty: Double = 0.0,
    val expiryDate: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "import_sessions")
data class ImportSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val type: String,
    val startedAt: Long = System.currentTimeMillis(),
    var finishedAt: Long? = null,
    var addedCustomers: Int = 0,
    var updatedCustomers: Int = 0,
    var addedProducts: Int = 0,
    var updatedProducts: Int = 0,
    var ignored: Int = 0,
    var reviewed: Int = 0,
    var backupPath: String? = null,
    var status: String = "RUNNING",
)

@Entity(tableName = "import_records")
data class ImportRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val kind: String,
    val action: String,
    val sourceRaw: String,
    val matchedId: Long? = null,
    val confidence: Double = 0.0,
)

@Entity(tableName = "import_errors")
data class ImportError(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val message: String,
    val rawLine: String,
)

@Entity(tableName = "backups")
data class Backup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val size: Long = 0,
    val auto: Int = 0,
)

@Entity(tableName = "activity_log")
data class ActivityLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userName: String,
    val action: String,
    val details: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "ai_conversations")
data class AIConversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "ai_messages")
data class AIMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)
