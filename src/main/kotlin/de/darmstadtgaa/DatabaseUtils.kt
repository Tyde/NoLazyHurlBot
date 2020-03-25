package de.darmstadtgaa

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Function
import org.jetbrains.exposed.sql.`java-time`.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.format.DateTimeFormatter





object Users : IdTable<Int>() {
    //No autoincrement as Telegram id is used
    override val id = integer("user_id").uniqueIndex().entityId()
    val customAlias = text("custom_alias").nullable()
    val officalName = text("official_name")
    override val primaryKey = PrimaryKey(id, name = "useridKey")
}

class User(telegramUserId: EntityID<Int>) : IntEntity(telegramUserId) {
    companion object : IntEntityClass<User>(Users) {
        fun fromIdOrCreate(id: Int?, firstName: String, lastName: String): User {
            return transaction {
                val userOrNull = find { Users.id eq id }.firstOrNull()
                if (userOrNull == null && id != null) {
                    Users.insert {
                        it[Users.id] = EntityID(id,
                            Users
                        )
                        it[officalName] = "$firstName $lastName"
                    }
                    find { Users.id eq id }.first()
                } else {
                    userOrNull!!
                }
            }
        }
    }


    var customAlias by Users.customAlias
    var officialName by Users.officalName

    val bestName:String
        get() = customAlias ?: officialName
}

object Runs : IntIdTable() {
    val user = reference("user", Users)
    val time = datetime("time")
    val length = decimal("length", precision = 8, scale = 3)
    val isConfirmed = bool("is_confirmed")
    val isBike = bool("is_bike").default(false)
}



var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
class Run(id:EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Run>(Runs)
    var user by User referencedOn Runs.user
    var time by Runs.time
    var length by Runs.length
    var isConfirmed by Runs.isConfirmed
    var isBike by Runs.isBike

    fun formatTime():String {
        return time.format(formatter)
    }
}


/**
 * Helper functions
 */

class SumOver<T>(
    /** Returns the expression from which the sum partition is calculated. */
    val expr: Expression<T>,
    val partition: Expression<*>?,
    val order: Expression<*>?,
    _columnType: IColumnType
) : Function<T?>(_columnType) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder): Unit = queryBuilder {
        +"(SUM("
        +expr
        +") OVER "
        if (partition!=null){
            +"( PARTITION BY "
            +partition
            +")"
        }
        if (order!=null) {
            +"( ORDER BY "
            +order
            +")"
        }
        +")"
        }
}
fun <T : Any?> ExpressionWithColumnType<T>.sumOver(partition: Expression<*>? = null,order: Expression<*>? = null): SumOver<T> =
    SumOver(this, partition, order, this.columnType)



class CountOver(
    /** Returns the expression from which the rows are counted. */
    val expr: Expression<*>,
    /** the expression over which the counting is grouped */
    val over: Expression<*>,
    /** Returns whether only distinct element should be count. */
    val distinct: Boolean = false
) : Function<Long>(LongColumnType()) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder): Unit = queryBuilder {
        +"(COUNT("
        if (distinct) +"DISTINCT "
        +expr
        +") OVER (PARTITION BY "
        +over
        +"))"
    }
}
fun ExpressionWithColumnType<*>.countOver(over: Expression<*>): CountOver = CountOver(this,over)


