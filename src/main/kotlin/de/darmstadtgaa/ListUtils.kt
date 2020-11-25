package de.darmstadtgaa

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.`java-time`.CurrentDateTime
import org.jetbrains.exposed.sql.`java-time`.dateLiteral
import org.jetbrains.exposed.sql.`java-time`.dateTimeLiteral
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import kotlin.and

object ListUtils {
    var numberFormatter: NumberFormat = DecimalFormat("#0.00")
    fun composeList(isBike: Boolean = false): String {
        //Compose Message
        var composedList = transaction {
            val sums = (Runs innerJoin Users)
                .slice(
                    Runs.length.sum(),
                    Runs.length.count(),
                    Users.customAlias,
                    Users.officalName
                )
                .select { Runs.isConfirmed eq true and (Runs.isBike eq isBike) and
                        (Runs.time greater Settings.cutOffDate)}
                .groupBy(Runs.user)
                .orderBy(Runs.length.sum() to SortOrder.DESC)
            var count = 0

            val listName = if (isBike) "#NoLazyBikeListe" else "#NoLazyHurlListe"
            val singularName = if (isBike) "Fahrt" else "Lauf"
            val pluralName = if (isBike) "Fahrten" else "Läufen"
            sums.fold(listName, { acc, res ->
                val name = res.getOrNull(Users.customAlias)
                    ?: res[Users.officalName]
                val numberOfRuns = res[Runs.length.count()]
                val textRunRuns = if (numberOfRuns == 1L) singularName else pluralName
                count++
                acc + System.lineSeparator() + count + ". " +
                        "$name: ${numberFormatter.format(res[Runs.length.sum()])} km in $numberOfRuns $textRunRuns"
            })
        }


        val total = transaction {
            Runs.slice(Runs.length.sum())
                .select { Runs.isBike eq isBike and (Runs.isConfirmed eq true) and (Runs.time greater Settings.cutOffDate)}
                .firstOrNull()
                ?.getOrNull(
                    Runs.length.sum()
                )
        }?: BigDecimal(0)


        composedList += System.lineSeparator() + System.lineSeparator() +
                "Gesamt: ${numberFormatter.format(total)} km"


        return composedList
    }

    fun composeHurlingSessionList(): String {
        return transaction {
            var count = 0
            (HurlingSessions innerJoin Users)
                .slice(
                    HurlingSessions.user.count(),
                    Users.customAlias,
                    Users.officalName
                )
                .select { HurlingSessions.isConfirmed eq true and (HurlingSessions.time greater Settings.cutOffDate)}
                .groupBy(HurlingSessions.user)
                .orderBy(HurlingSessions.user.count() to SortOrder.DESC)
                .fold("#AmBallBleibenListe", { acc, res ->
                    val name = res.getOrNull(Users.customAlias)
                        ?: res[Users.officalName]
                    val numberOfSessions = res[HurlingSessions.user.count()]
                    count++
                    acc + System.lineSeparator() + count + ". " +
                            "$name: ${numberOfSessions}"
                })
        }
    }
    fun composeCompleteList(): String {
        return composeList() + System.lineSeparator() +
                System.lineSeparator() + composeList(true) + System.lineSeparator() +
                System.lineSeparator() + composeHurlingSessionList()
    }
}