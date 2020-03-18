package de.darmstadtgaa

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.`java-time`.datetime
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction


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
}

object Runs : IntIdTable() {
    val user = reference("user", Users)
    val time = datetime("time")
    val length = decimal("length", precision = 8, scale = 3)
    val isConfirmed = bool("is_confirmed")
}

class Run(id:EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Run>(Runs)
    var user by User referencedOn Runs.user
    var time by Runs.time
    var length by Runs.length
    var isConfirmed by Runs.isConfirmed
}
