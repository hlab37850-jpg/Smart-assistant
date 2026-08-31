package com.smartassistant.app.data.local.entity

import androidx.room.*

@Entity(tableName = "shop_settings")
data class ShopSettings(
    @PrimaryKey val id: Int = 1,
    val name: String = "",
    val phone: String = "",
    val whatsapp: String = "",
    val address: String = "",
    val logoPath: String? = null,
    val currency: String = "",
)

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val username: String,
    val pinHash: String,
    val role: String,
    val permissions: String,
    val active: Int = 1,
)

@Entity(tableName = "customers", indices = [Index("name"), Index("phone"), Index("code"), Index("nameNormalized")])
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalCode: String? = null,
    val originalId: String? = null,
    val name: String,
    val nameNormalized: String = "",
    val phone: String? = null,
    val code: String? = null,
    val address: String? = null,
    val balance: Double = 0.0,
    val rawBalance: String? = null,
    val currency: String? = null,
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    val sourcePage: Int? = null,
    val importSessionId: Long? = null,
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

@Entity(tableName = "dues", indices = [Index("date"), Index("customerId")])
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

@Entity(tableName = "notifications", indices = [Index("read")])
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

@Entity(tableName = "products", indices = [Index("nameRaw"), Index("code")])
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

@Entity(tableName = "inventory", indices = [Index("productId"), Index("qty")])
data class Inventory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val qty: Double = 0.0,
    val minQty: Double = 0.0,
    val expiryDate: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

// ============ Import System Tables ============

@Entity(tableName = "import_sessions")
data class ImportSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val fileHash: String,
    val fileType: String,
    val pdfType: String? = null,
    val profileId: String? = null,
    val kind: String,
    val totalFound: Int = 0,
    val validCount: Int = 0,
    val reviewCount: Int = 0,
    val duplicateCount: Int = 0,
    val incompleteCount: Int = 0,
    val ignoredCount: Int = 0,
    val totalCredit: Double = 0.0,
    val totalDebit: Double = 0.0,
    val net: Double = 0.0,
    val status: String = "UPLOADED",
    val startedAt: Long = System.currentTimeMillis(),
    var finishedAt: Long? = null,
    var backupPath: String? = null,
)

@Entity(tableName = "import_rows", indices = [Index("sessionId")])
data class ImportRawRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val pageNumber: Int,
    val rowNumber: Int,
    val nameRaw: String,
    val nameDisplay: String,
    val nameNormalized: String,
    val credit: Double = 0.0,
    val debit: Double = 0.0,
    val currency: String? = null,
    val net: Double = 0.0,
    val status: String = "PENDING",
    val confidenceScore: Int = 0,
    val sourceCoordinates: String? = null,
    val issues: String? = null,
    val approved: Int = 0,
    val userEdited: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "import_issues")
data class ImportError(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val pageNumber: Int? = null,
    val rowNumber: Int? = null,
    val issueType: String,
    val message: String,
    val rawData: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "import_profiles")
data class ImportProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileKey: String,
    val name: String,
    val kind: String,
    val identificationKeywords: String,
    val columnHints: String,
    val headerRules: String,
    val footerRules: String,
    val totalRules: String,
    val createdAt: Long = System.currentTimeMillis(),
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
