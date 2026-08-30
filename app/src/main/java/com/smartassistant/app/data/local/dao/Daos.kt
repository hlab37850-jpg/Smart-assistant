package com.smartassistant.app.data.local.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.smartassistant.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow

data class CustomerDueRow(
    @Embedded val customer: Customer,
    val dueId: Long? = null,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val dueFollowed: Int? = null,
)

data class ProductRow(
    @Embedded val product: Product,
    val qty: Double? = null,
    val minQty: Double? = null,
    val expiry: String? = null,
)

data class DueWithCustomer(
    @Embedded val due: DueDate,
    val customerName: String,
    val balance: Double,
)

@Dao
interface ShopDao {
    @Query("SELECT * FROM shop_settings WHERE id = 1") fun observe(): Flow<ShopSettings?>
    @Query("SELECT * FROM shop_settings WHERE id = 1") suspend fun get(): ShopSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(s: ShopSettings)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE active = 1") fun all(): Flow<List<User>>
    @Query("SELECT * FROM users WHERE role = 'ADMIN' LIMIT 1") suspend fun firstAdmin(): User?
    @Insert suspend fun insert(u: User): Long
    @Update suspend fun update(u: User)
}

@Dao
interface CustomerDao {
    @Query("""SELECT c.*, d.id AS dueId, d.date AS dueDate, d.time AS dueTime, d.followedUp AS dueFollowed
        FROM customers c LEFT JOIN dues d ON d.id = (
          SELECT id FROM dues WHERE customerId = c.id ORDER BY date || ' ' || time LIMIT 1)
        WHERE c.archived = 0 ORDER BY c.name""")
    fun allPaged(): PagingSource<Int, CustomerDueRow>

    @Query("""SELECT c.*, d.id AS dueId, d.date AS dueDate, d.time AS dueTime, d.followedUp AS dueFollowed
        FROM customers c LEFT JOIN dues d ON d.id = (
          SELECT id FROM dues WHERE customerId = c.id ORDER BY date || ' ' || time LIMIT 1)
        WHERE c.archived = 0 AND (c.name LIKE '%' || :q || '%' OR c.phone LIKE '%' || :q || '%'
          OR CAST(c.balance AS TEXT) LIKE '%' || :q || '%') ORDER BY c.name""")
    fun searchPaged(q: String): PagingSource<Int, CustomerDueRow>

    @Query("""SELECT c.*, d.id AS dueId, d.date AS dueDate, d.time AS dueTime, d.followedUp AS dueFollowed
        FROM customers c LEFT JOIN dues d ON d.id = (
          SELECT id FROM dues WHERE customerId = c.id ORDER BY date || ' ' || time LIMIT 1)
        WHERE c.archived = 0 AND
        CASE :filter
          WHEN 'DEBT' THEN c.balance > 0
          WHEN 'CREDIT' THEN c.balance < 0
          WHEN 'OVERDUE' THEN d.date < :today
          WHEN 'TODAY' THEN d.date = :today
          WHEN 'FORGOTTEN' THEN d.date < :today AND IFNULL(d.followedUp,0) = 0
          ELSE 1 END
        ORDER BY d.date, c.name""")
    fun filterPaged(filter: String, today: String): PagingSource<Int, CustomerDueRow>

    @Query("SELECT COUNT(*) FROM customers WHERE archived = 0") fun count(): Flow<Int>
    @Query("SELECT IFNULL(SUM(balance),0) FROM customers WHERE balance > 0") fun totalTheyOwe(): Flow<Double>
    @Query("SELECT IFNULL(SUM(-balance),0) FROM customers WHERE balance < 0") fun totalWeOwe(): Flow<Double>
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun byId(id: Long): Customer?
    @Query("SELECT * FROM customers WHERE archived = 0") suspend fun allSync(): List<Customer>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(c: Customer): Long
    @Update suspend fun update(c: Customer)
    @Query("UPDATE customers SET archived = 1 WHERE id = :id") suspend fun archive(id: Long)
}

@Dao
interface ProductDao {
    @Query("""SELECT p.*, i.qty AS qty, i.minQty AS minQty, i.expiryDate AS expiry
        FROM products p LEFT JOIN inventory i ON i.productId = p.id
        WHERE p.archived = 0 ORDER BY p.nameRaw""")
    fun allPaged(): PagingSource<Int, ProductRow>

    @Query("""SELECT p.*, i.qty AS qty, i.minQty AS minQty, i.expiryDate AS expiry
        FROM products p LEFT JOIN inventory i ON i.productId = p.id
        WHERE p.archived = 0 AND (p.nameRaw LIKE '%' || :q || '%' OR p.code LIKE '%' || :q || '%')
        ORDER BY p.nameRaw""")
    fun searchPaged(q: String): PagingSource<Int, ProductRow>

    @Query("""SELECT p.*, i.qty AS qty, i.minQty AS minQty, i.expiryDate AS expiry
        FROM products p LEFT JOIN inventory i ON i.productId = p.id
        WHERE p.archived = 0 AND
        CASE :tab WHEN 'LOW' THEN i.qty <= i.minQty AND i.qty > 0
                  WHEN 'OUT' THEN i.qty <= 0
                  WHEN 'EXP' THEN i.expiryDate IS NOT NULL AND i.expiryDate <= :today
                  ELSE 1 END
        ORDER BY p.nameRaw""")
    fun tabPaged(tab: String, today: String): PagingSource<Int, ProductRow>

    @Query("""SELECT p.*, i.qty AS qty, i.minQty AS minQty, i.expiryDate AS expiry
        FROM products p JOIN inventory i ON i.productId = p.id
        WHERE p.archived = 0 AND i.qty <= i.minQty ORDER BY i.qty LIMIT 10""")
    fun lowStockList(): Flow<List<ProductRow>>

    @Query("SELECT COUNT(*) FROM products WHERE archived = 0") fun count(): Flow<Int>
    @Query("""SELECT COUNT(*) FROM products p JOIN inventory i ON i.productId=p.id
        WHERE p.archived=0 AND i.qty <= i.minQty""") fun lowOrOutCount(): Flow<Int>
    @Query("SELECT * FROM products WHERE id=:id") suspend fun byId(id: Long): Product?
    @Query("SELECT * FROM products WHERE archived = 0") suspend fun allSync(): List<Product>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(p: Product): Long
    @Update suspend fun update(p: Product)
    @Query("UPDATE products SET archived=1 WHERE id=:id") suspend fun archive(id: Long)
}

@Dao
interface DueDao {
    @Query("""SELECT d.*, c.name AS customerName, c.balance AS balance FROM dues d
        JOIN customers c ON c.id = d.customerId WHERE d.date = :today ORDER BY d.time""")
    fun today(today: String): Flow<List<DueWithCustomer>>
    @Query("""SELECT d.*, c.name AS customerName, c.balance AS balance FROM dues d
        JOIN customers c ON c.id = d.customerId WHERE d.date = :tomorrow ORDER BY d.time""")
    fun tomorrow(tomorrow: String): Flow<List<DueWithCustomer>>
    @Query("""SELECT d.*, c.name AS customerName, c.balance AS balance FROM dues d
        JOIN customers c ON c.id = d.customerId WHERE d.date BETWEEN :from AND :to ORDER BY d.date""")
    fun week(from: String, to: String): Flow<List<DueWithCustomer>>
    @Query("""SELECT d.*, c.name AS customerName, c.balance AS balance FROM dues d
        JOIN customers c ON c.id = d.customerId WHERE d.date < :today ORDER BY d.date DESC""")
    fun overdue(today: String): Flow<List<DueWithCustomer>>
    @Query("""SELECT d.*, c.name AS customerName, c.balance AS balance FROM dues d
        JOIN customers c ON c.id = d.customerId ORDER BY d.date, d.time""")
    fun all(): Flow<List<DueWithCustomer>>
    @Query("""SELECT d.*, c.name AS customerName, c.balance AS balance FROM dues d
        JOIN customers c ON c.id = d.customerId
        WHERE d.date < :today AND d.followedUp = 0 ORDER BY d.date""")
    fun forgotten(today: String): Flow<List<DueWithCustomer>>
    @Query("""SELECT d.*, c.name AS customerName, c.balance AS balance FROM dues d
        JOIN customers c ON c.id = d.customerId WHERE d.date >= :today
        ORDER BY d.date, d.time LIMIT 5""")
    fun upcomingFlow(today: String): Flow<List<DueWithCustomer>>
    @Query("SELECT COUNT(*) FROM dues WHERE date = :today") fun todayCount(today: String): Flow<Int>
    @Query("SELECT * FROM dues WHERE id=:id") suspend fun byId(id: Long): DueDate?
    @Insert suspend fun insert(d: DueDate): Long
    @Update suspend fun update(d: DueDate)
    @Query("UPDATE dues SET followedUp = 1 WHERE id = :id") suspend fun markFollowed(id: Long)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY createdAt DESC") fun all(): Flow<List<AppNotification>>
    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0") fun unread(): Flow<Int>
    @Insert suspend fun insert(n: AppNotification): Long
    @Query("UPDATE notifications SET read = 1 WHERE id = :id") suspend fun markRead(id: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY name") fun all(): Flow<List<Category>>
    @Insert suspend fun insert(c: Category): Long
    @Update suspend fun update(c: Category)
    @Query("UPDATE categories SET archived = 1 WHERE id = :id") suspend fun archive(id: Long)
}

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory WHERE productId = :pid") suspend fun byProduct(pid: Long): Inventory?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(i: Inventory): Long
    @Query("SELECT * FROM inventory WHERE productId = :pid") fun watch(pid: Long): Flow<Inventory?>
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM customer_notes WHERE customerId = :cid ORDER BY createdAt DESC")
    fun forCustomer(cid: Long): Flow<List<CustomerNote>>
    @Insert suspend fun insert(n: CustomerNote): Long
}

@Dao
interface ImportDao {
    @Insert suspend fun session(s: ImportSession): Long
    @Update suspend fun updateSession(s: ImportSession)
    @Insert suspend fun record(r: ImportRecord)
    @Insert suspend fun error(e: ImportError)
    @Query("SELECT * FROM import_sessions ORDER BY startedAt DESC") fun sessions(): Flow<List<ImportSession>>
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backups ORDER BY createdAt DESC") fun all(): Flow<List<Backup>>
    @Insert suspend fun insert(b: Backup): Long
}

@Dao
interface LogDao {
    @Query("SELECT * FROM activity_log ORDER BY createdAt DESC LIMIT 500") fun all(): Flow<List<ActivityLog>>
    @Query("SELECT * FROM activity_log ORDER BY createdAt DESC LIMIT 20") fun recent(): Flow<List<ActivityLog>>
    @Insert suspend fun insert(l: ActivityLog)
}

@Dao
interface AIDao {
    @Insert suspend fun conv(c: AIConversation): Long
    @Insert suspend fun msg(m: AIMessage): Long
    @Query("SELECT * FROM ai_conversations ORDER BY createdAt DESC LIMIT 1") suspend fun lastConv(): AIConversation?
    @Query("SELECT * FROM ai_messages WHERE conversationId = :cid ORDER BY createdAt")
    fun messages(cid: Long): Flow<List<AIMessage>>
}
