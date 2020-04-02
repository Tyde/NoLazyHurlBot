package de.darmstadtgaa

import com.natpryce.konfig.*
import com.natpryce.konfig.Key
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*


fun main() {
    val conf = Config()
    val token = conf.token
    val publicChatId = conf.publicChatId


    Database.connect("jdbc:sqlite:data.db", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel =
        Connection.TRANSACTION_SERIALIZABLE // Or Connection.TRANSACTION_READ_UNCOMMITTED
    transaction {
        //addLogger(StdOutSqlLogger)
        SchemaUtils.create(Users)
        SchemaUtils.create(Runs)
        SchemaUtils.create(HurlingSessions)
        //println("Tournaments: ${Tournaments.selectAll()}")
    }
    ApiContextInitializer.init()
    val botsApi = TelegramBotsApi()
    val noLazyHurlBot = NoLazyHurlBot(token, publicChatId)
    botsApi.registerBot(noLazyHurlBot)


    Website()


}


val logger = LoggerFactory.getLogger("de.darmstadtgaa.nolazyhurlbot")

class Config {
    private val config = ConfigurationProperties.systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationProperties.fromFile(File("bot.properties"))
    private val tokenKey = Key("bot.token", stringType)
    private val publicChatKey = Key("bot.public_group", stringType)

    val token: String
        get() = config[tokenKey]

    val publicChatId: String
        get() = config[publicChatKey]

}


class NoLazyHurlBot(private val token: String, private val publicChatId: String) : TelegramLongPollingBot() {
    var lastTotalKilometers = transaction {
        Runs.slice(Runs.length.sum()).select { Runs.isBike eq false and (Runs.isConfirmed eq true) }.firstOrNull()
            ?.getOrNull(
                Runs.length.sum()
            )
    }

    init {
        logger.debug(lastTotalKilometers.toString())
    }

    override fun getBotUsername(): String {
        return "NoLazyHurlBot"
    }

    override fun getBotToken(): String {
        return token
    }

    val hashtagRegex = Regex("#nolazyhurl", RegexOption.IGNORE_CASE)
    val bikeHastagRegex = Regex("#nolazybike", RegexOption.IGNORE_CASE)
    val amBallBleibenRegex = Regex("#amballbleiben",RegexOption.IGNORE_CASE)
    val lengthRegex = Regex("""(\d+)[\.,]*(\d*)\s*(km|mi[\s$])*""", RegexOption.IGNORE_CASE)
    var numberFormatter: NumberFormat = DecimalFormat("#0.00")


    private val CORRECT_VALUE_CALLBACK = "correctValue"
    private val WRONG_VALUE_CALLBACK = "wrongValue"

    private val CORRECT_VALUE_CALLBACK_HURLING = "HcorrectValue"
    private val WRONG_VALUE_CALLBACK_HURLING = "HwrongValue"

    private val MILES_TO_KM = 1.609344

    override fun onUpdateReceived(update: Update?) {
        update?.let {
            logger.debug(it.toString())
            if (it.hasMessage()) {
                handleSimpleMessage(update)
            } else if (it.hasCallbackQuery()) {

                val command = it.callbackQuery.data
                val activityId = command.split("/").getOrNull(1)?.toInt()
                if (command.startsWith(CORRECT_VALUE_CALLBACK)) {
                    confirmActivity(activityId, it)
                } else if (command.startsWith(WRONG_VALUE_CALLBACK)) {
                    cancelActivity(activityId, it)
                } else if(command.startsWith(CORRECT_VALUE_CALLBACK_HURLING)) {
                    confirmActivity(activityId, it, isHurlingSession = true)
                } else if(command.startsWith(WRONG_VALUE_CALLBACK_HURLING)) {
                    cancelActivity(activityId, it, isHurlingSession = true)
                }
                logger.debug("Callback pressed")

            }
        }
    }

    private fun cancelActivity(activityId: Int?, it: Update, isHurlingSession: Boolean = false) {
        transaction {
            val activity = if (isHurlingSession)
                activityId?.let { it -> HurlingSession.find { HurlingSessions.id eq it }.firstOrNull() }
            else
                activityId?.let { it -> Run.find { Runs.id eq it }.firstOrNull() }
            activity?.delete()
        }

        //Change Message
        val messageIdToEdit = it.callbackQuery.message.messageId
        val chatIdToEdit = it.callbackQuery.message.chatId
        execute(DeleteMessage().apply {
            chatId = chatIdToEdit.toString()
            messageId = messageIdToEdit
        })
    }


    private fun confirmActivity(activityId: Int?, it: Update, isHurlingSession:Boolean = false) {
        //Confirm run
        transaction {
            if (isHurlingSession) {
                val session = activityId?.let {
                        it -> HurlingSession.find { HurlingSessions.id eq it }.firstOrNull() }
                session?.isConfirmed = true
            } else {
                val run = activityId?.let { it -> Run.find { Runs.id eq it }.firstOrNull() }
                run?.isConfirmed = true
            }
        }
        val composedList = composeCompleteList()


        //Change Message
        val messageIdToEdit = it.callbackQuery.message.messageId
        val chatIdToEdit = it.callbackQuery.message.chatId
        /*execute(DeleteMessage().apply {
            chatId = chatIdToEdit.toString()
            messageId = messageIdToEdit
        })*/
        execute(EditMessageText().apply {
            chatId = chatIdToEdit.toString()
            messageId = messageIdToEdit
            text = composedList
        })
        val total = transaction {
            Runs.slice(Runs.length.sum()).select { Runs.isBike eq false and (Runs.isConfirmed eq true) }.firstOrNull()
                ?.getOrNull(
                    Runs.length.sum()
                )
        }
        val defValue = BigDecimal(0.0)
        if (Math.floor((total ?: defValue).toDouble() / 100) > Math.floor(
                (lastTotalKilometers ?: defValue).toDouble() / 100
            )
        ) {
            this.lastTotalKilometers = total
            send100kmList(composedList)
        }
    }

    private fun send100kmList(composedList: String) {

        val command = SendMessage().apply {
            chatId = publicChatId
            text = "\uD83D\uDCAF\uD83C\uDFC3\u200D♂️\uD83E\uDDA0 Wir haben die nächsten 100 km" +
                    System.lineSeparator() + composedList
        }
        execute(command)
    }

    private fun composeList(isBike: Boolean = false): String {
        //Compose Message
        var composedList = transaction {
            val sums = (Runs innerJoin Users)
                .slice(
                    Runs.length.sum(),
                    Runs.length.count(),
                    Users.customAlias,
                    Users.officalName
                )
                .select { Runs.isConfirmed eq true and (Runs.isBike eq isBike) }
                .groupBy(Runs.user)
                .orderBy(Runs.length.sum() to SortOrder.DESC)
            var count = 0

            val listName = if (isBike) "#NoLazyBikeListe" else "#NoLazyHurlListe"
            val singularName = if (isBike) "Fahrt" else "Lauf"
            val pluralName = if (isBike) "Fahrten" else "Läufen"
            sums.fold(listName, { acc, res ->
                val name = res.getOrNull(Users.customAlias) ?: res[Users.officalName]
                val numberOfRuns = res[Runs.length.count()]
                val textRunRuns = if (numberOfRuns == 1L) singularName else pluralName
                count++
                acc + System.lineSeparator() + count + ". " +
                        "$name: ${numberFormatter.format(res[Runs.length.sum()])} km in $numberOfRuns $textRunRuns"
            })
        }

        val total = transaction {
            Runs.slice(Runs.length.sum()).select { Runs.isBike eq isBike and (Runs.isConfirmed eq true) }.firstOrNull()
                ?.getOrNull(
                    Runs.length.sum()
                )
        }

        composedList += System.lineSeparator() + System.lineSeparator() +
                "Gesamt: ${numberFormatter.format(total)} km"
        return composedList
    }

    private fun composeHurlingSessionList(): String {
        return transaction {
            var count = 0
            (HurlingSessions innerJoin Users)
                .slice(HurlingSessions.user.count(),
                    Users.customAlias,
                    Users.officalName)
                .select { HurlingSessions.isConfirmed eq true }
                .groupBy(HurlingSessions.user)
                .orderBy(HurlingSessions.user.count() to SortOrder.DESC)
                .fold("#AmBallBleibenListe", { acc, res ->
                    val name = res.getOrNull(Users.customAlias) ?: res[Users.officalName]
                    val numberOfSessions = res[HurlingSessions.user.count()]
                    count ++
                    acc + System.lineSeparator() + count + ". "+
                            "$name: ${numberOfSessions}"
                })
        }
    }
    private fun composeCompleteList(): String {
        return composeList() + System.lineSeparator() +
                System.lineSeparator() + composeList(true) + System.lineSeparator() +
                System.lineSeparator() + composeHurlingSessionList()
    }

    var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun handleSimpleMessage(update: Update) {
        val message = update.message!!
        val recievedText = if (message.hasText()) {
            message.text!!
        } else if (message.caption != null) {
            message.caption
        } else {
            null
        }
        if (recievedText != null) {
            if (hashtagRegex.containsMatchIn(recievedText)) {
                handleHashtag(recievedText, message)
            } else if (bikeHastagRegex.containsMatchIn(recievedText)) {
                //Handle BikeHastag
                handleHashtag(recievedText, message, true)
            } else if (amBallBleibenRegex.containsMatchIn(recievedText)) {
                //Handle am Ball Bleiben entry
                val userId = message.from.id
                var user = User.fromIdOrCreate(
                    userId,
                    message.from.firstName,
                    message.from.lastName ?: ""
                )
                val session = transaction {
                    val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(message.date.toLong()),
                        TimeZone.getDefault().toZoneId())
                    HurlingSession.new {
                        this.user = user
                        this.time = ldt
                        this.isConfirmed = false
                    }
                }
                val textReturn = user.officialName + " hat eine Hurling Session eingelegt. Richtig?"
                val command = SendMessage().apply {
                    chatId = message.from.id.toString()
                    text = textReturn
                    //disableNotification()
                    replyMarkup = InlineKeyboardMarkup().apply {
                        keyboard = mutableListOf(
                            mutableListOf(
                                InlineKeyboardButton().apply {
                                    text = "Ja"
                                    callbackData = "$CORRECT_VALUE_CALLBACK_HURLING/${session.id.value}"
                                },
                                InlineKeyboardButton().apply {
                                    text = "Nein"
                                    callbackData = "$WRONG_VALUE_CALLBACK_HURLING/${session.id.value}"
                                })
                        )
                    }
                }
                try {
                    execute(command)
                } catch (e:TelegramApiException) {
                    command.chatId = "12672170"
                    logger.debug("got "+e.message)
                    execute(command)
                }
            } else if (recievedText.startsWith("/")) {
                //Only react to list command in correct circumstances
                val isGroupCommand = recievedText.equals("/list@NoLazyHurlBot", ignoreCase = true) &&
                        (update.message.chat.isGroupChat || update.message.chat.isSuperGroupChat)
                val isPrivateCommand = recievedText.startsWith("/list", ignoreCase = true) &&
                        (update.message.chat.isUserChat)

                if (isGroupCommand || isPrivateCommand) {
                    val listString = composeCompleteList()

                    execute(SendMessage().apply {
                        chatId = message.chatId.toString()
                        text = listString
                    })
                } else if (recievedText.startsWith("/myruns") && update.message.chat.isUserChat) {
                    val from = update.message.from
                    val user = User.fromIdOrCreate(
                        from.id,
                        from.firstName,
                        from.lastName ?: ""
                    )
                    val resultText = transaction {
                        Run.find { Runs.user eq user.id and (Runs.isConfirmed eq true)}.fold("", { acc, run ->
                            val activityType = if (run.isBike) "Fahrrad" else "Lauf"
                            acc + System.lineSeparator() +
                                    activityType +
                                    ": ${run.time.format(formatter)}: ${numberFormatter.format(run.length)} km"
                        })
                    }
                    execute(SendMessage().apply {
                        chatId = message.chatId.toString()
                        text = resultText
                    })
                } else if (recievedText.startsWith("/allruns") && update.message.chat.isUserChat) {
                    val resultText = transaction {
                        val showIds = recievedText.contains("id")
                        Run.find { Runs.isConfirmed eq true }.fold("", { acc, run ->
                            val activityType = if (run.isBike) "Fahrrad" else "Lauf"
                            acc + System.lineSeparator() +
                                    "${run.time.format(formatter)} - ${run.user.customAlias
                                        ?: run.user.officialName}: ${numberFormatter.format(run.length)} km" +
                                    " ($activityType)" +
                                    if (showIds) " id: ${run.id}" else ""
                        })
                    }
                    execute(SendMessage().apply {
                        chatId = message.chatId.toString()
                        text = resultText
                    })
                } else if (recievedText.startsWith("/delete") && update.message.chat.isUserChat) {
                    logger.debug("Delete called but not implemented")
                } else if(recievedText.startsWith("/trigger100km") && update.message.chat.isUserChat) {
                    send100kmList(composeCompleteList())
                }

            }
        }
    }

    private fun handleHashtag(recievedText: String, message: Message, isBike: Boolean = false) {
        if (lengthRegex.containsMatchIn(recievedText)) {
            //found nr.
            val matches = lengthRegex.find(recievedText)?.groups
            val numberFirst = matches?.get(1)?.value
            val numberLast = matches?.get(2)?.value
            val unit = matches?.get(3)?.value
            var number = "$numberFirst.$numberLast".toDouble()
            if (unit.equals("mi", ignoreCase = true)) {
                number *= MILES_TO_KM
            }

            //Save now, because we still have all the data
            val userId = message.from.id
            var user = User.fromIdOrCreate(
                userId,
                message.from.firstName,
                message.from.lastName ?: ""
            )
            val forwardFrom = message.forwardFrom
            if (forwardFrom != null) {
                user = User.fromIdOrCreate(
                    forwardFrom.id,
                    forwardFrom.firstName ?: "",
                    forwardFrom.lastName ?: ""
                )
            }
            val run = transaction {
                 Run.new {
                    this.user = user
                    this.length = number.toBigDecimal()
                    this.time = LocalDateTime.now()
                    this.isConfirmed = false
                    this.isBike = isBike
                }
            }

            val actionVerb = if (isBike) "Fahrrad gefahren" else "gelaufen"
            val textReturn = user.officialName + " ist ${numberFormatter.format(number)} km $actionVerb, korrekt?"

            val command = SendMessage().apply {
                chatId = message.from.id.toString()
                text = textReturn
                //disableNotification()
                replyMarkup = InlineKeyboardMarkup().apply {
                    keyboard = mutableListOf(
                        mutableListOf(
                            InlineKeyboardButton().apply {
                                text = "Ja"
                                callbackData = "$CORRECT_VALUE_CALLBACK/${run.id.value}"
                            },
                            InlineKeyboardButton().apply {
                                text = "Nein"
                                callbackData = "$WRONG_VALUE_CALLBACK/${run.id.value}"
                            })
                    )
                }
            }
            try {
                execute(command)
            } catch ( e: TelegramApiException) {
                logger.info("Don't have any privilege, sending to Daniel")
                val sendMessage = SendMessage().apply {
                    chatId = "12672170"
                    text = textReturn
                    //disableNotification()
                    replyMarkup = InlineKeyboardMarkup().apply {
                        keyboard = mutableListOf(
                            mutableListOf(
                                InlineKeyboardButton().apply {
                                    text = "Ja"
                                    callbackData = "$CORRECT_VALUE_CALLBACK/${run.id.value}"
                                },
                                InlineKeyboardButton().apply {
                                    text = "Nein"
                                    callbackData = "$WRONG_VALUE_CALLBACK/${run.id.value}"
                                })
                        )
                    }
                }
                execute(sendMessage)
            }



        } else {
            val command = SendMessage().apply {
                chatId = message.chatId.toString()
                text = "Ich kann die Streckenlänge nicht erkennen. Bitte im Format #NoLazyHurl ##,## km schreiben"
            }
            execute(command)
        }
        logger.debug("Found correct Hashtag")
    }


}



