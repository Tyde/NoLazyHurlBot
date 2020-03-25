package de.darmstadtgaa
import freemarker.cache.ClassTemplateLoader
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.DefaultHeaders
import io.ktor.freemarker.FreeMarker
import io.ktor.freemarker.FreeMarkerContent
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

class Website {
    init {
        embeddedServer(Netty, 8014) {
            install(DefaultHeaders)
            install(FreeMarker) {
                templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
            }

            routing {
                get("/{secret?}") {
                    val secretCorrect = call.parameters["secret"] == "RiechtKomischSchmecktKomisch"
                    val userRuns = extractUserRuns()
                    val userBikeRuns = extractUserRuns(isRunning = false)
                    val chartDataTotal = extractChartData()
                    val chartDataRuns = extractChartData(Op.build { Runs.isBike eq false })
                    val chartDataBike = extractChartData(Op.build { Runs.isBike eq true })
                    call.respond(FreeMarkerContent("index.ftl", mapOf("userRuns" to userRuns,
                        "userBikeRuns" to userBikeRuns,
                        "redacted" to !secretCorrect,
                        "chartDataTotal" to chartDataTotal,
                        "chartDataRuns" to chartDataRuns,
                        "chartDataBike" to chartDataBike
                    ),""))
                }
            }
        }.start(wait = true)
    }

    private fun extractChartData(condition:Op<Boolean>?=null): String {
        val baseCondition = Op.build { (Runs.isConfirmed eq true) }
        val completeCondition = condition?.let { Op.build { condition and baseCondition } }?:baseCondition
        return transaction {
            Runs.slice(
                Runs.length.sumOver(order = Runs.time),
                Runs.time
            ).select(completeCondition).orderBy(Runs.time)
                .map { row -> "['" + row[Runs.time]+ "'," + row[Runs.length.sumOver(order = Runs.time)]?.toDouble() + "]" }
        }.joinToString(separator = ",", prefix = "[", postfix = "]")
    }

    fun extractUserRuns(isRunning:Boolean = true):List<UserRuns> {
        return transaction{
            var lastUser:User? = null
            val userRunsList = mutableListOf<UserRuns>()
            (Runs innerJoin Users).slice (
                *Runs.columns.toTypedArray(),
                *Users.columns.toTypedArray(),
                Runs.length.sumOver(partition = Users.id),
                Runs.length.countOver(Users.id)
            ). select { Runs.isConfirmed eq true and (Runs.isBike eq !isRunning) }.orderBy(Runs.length.sumOver(Users.id) to SortOrder.DESC)
                .forEach {
                    val user = User.wrapRow(it)
                    val run = Run.wrapRow(it)
                    val userSum = it[Runs.length.sumOver(Users.id)]
                    val userCount = it[Runs.length.countOver(Users.id)]
                    if (lastUser == null || lastUser != user) {
                        userRunsList.add(UserRuns(user,userSum?.toDouble()?:0.0,userCount, mutableListOf(run)))
                    } else {
                        val userRuns = userRunsList.find { it.user == user }
                        if (userRuns!=null) {
                            userRuns.addRun(run)
                        } else {
                            logger.error("I have a row with a user I did not found a userRunsList for.")
                        }
                    }
                    lastUser = user
                }
            userRunsList
        }
    }
}

data class UserRuns(val user:User, val totalKM:Double, val totalRuns:Long, val listRuns:MutableList<Run>) {
    fun addRun(run:Run) {
        listRuns.add(run)
    }

    fun getAverage():Double {
        return listRuns.map { it.length.toDouble() }.average()
    }


}








