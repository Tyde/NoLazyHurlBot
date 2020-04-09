package de.darmstadtgaa

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
import java.math.BigDecimal
import java.sql.Connection
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

    private val CORRECT_VALUE_CALLBACK = "correctValue"
    private val WRONG_VALUE_CALLBACK = "wrongValue"

    private val CORRECT_VALUE_CALLBACK_HURLING = "HcorrectValue"
    private val WRONG_VALUE_CALLBACK_HURLING = "HwrongValue"

    private val MILES_TO_KM = 1.609344

    /**
     * Reacts to a usual update from the telegram servers. This is usually any message or
     * Interaction with the users
     */
    override fun onUpdateReceived(update: Update?) {
        update?.let {
            logger.debug(it.toString())
            if (it.hasMessage()) {
                handleSimpleMessage(update)
            } else if (it.hasCallbackQuery()) {
                //Handle all callbacks
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

    /**
     * Handles all messages that just are based on written Messages from Telegram Users.
     *
     * Here we check for the Hashtags or the /-Commands
     */
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
                handleHurlingSession(message)
            } else if (recievedText.startsWith("/")) {
                //Only react to list command in correct circumstances
                handleCommand(recievedText, update, message)

            }
        }
    }

    /**
     * Handle the hashtags in the messages and create activities and their callbacks
     */
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
            val textReturn = user.officialName + " ist ${ListUtils.numberFormatter.format(number)} km $actionVerb, korrekt?"

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
                command.chatId = "12672170"
                execute(command)
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

    /**
     * Helper function to send the List at a 100km mark
     */
    private fun send100kmList(composedList: String) {
        val command = SendMessage().apply {
            chatId = publicChatId
            text = "\uD83D\uDCAF\uD83C\uDFC3\u200D♂️\uD83E\uDDA0 Wir haben die nächsten 100 km" +
                    System.lineSeparator() + composedList
        }
        execute(command)
    }

    /**
     * Handle the #amBallBleiben hashtag and create activity
     */
    private fun handleHurlingSession(message: Message) {
        //Handle am Ball Bleiben entry
        val userId = message.from.id
        var user = User.fromIdOrCreate(
            userId,
            message.from.firstName,
            message.from.lastName ?: ""
        )
        val session = transaction {
            val ldt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(message.date.toLong()),
                TimeZone.getDefault().toZoneId()
            )
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
        } catch (e: TelegramApiException) {
            command.chatId = "12672170"
            logger.debug("got " + e.message)
            execute(command)
        }
    }

    /**
     * Handles all messages starting with an /
     */
    private fun handleCommand(recievedText: String, update: Update, message: Message) {
        val isGroupCommand = recievedText.equals("/list@NoLazyHurlBot", ignoreCase = true) &&
                (update.message.chat.isGroupChat || update.message.chat.isSuperGroupChat)
        val isPrivateCommand = recievedText.startsWith("/list", ignoreCase = true) &&
                (update.message.chat.isUserChat)

        if (isGroupCommand || isPrivateCommand) {
            val listString = ListUtils.composeCompleteList()

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
                Run.find { Runs.user eq user.id and (Runs.isConfirmed eq true) }.fold("", { acc, run ->
                    val activityType = if (run.isBike) "Fahrrad" else "Lauf"
                    acc + System.lineSeparator() +
                            activityType +
                            ": ${run.time.format(formatter)}: ${ListUtils.numberFormatter.format(run.length)} km"
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
                                ?: run.user.officialName}: ${ListUtils.numberFormatter.format(run.length)} km" +
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
        } else if (recievedText.startsWith("/trigger100km") && update.message.chat.isUserChat) {
            send100kmList(ListUtils.composeCompleteList())
        }
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
        val composedList = ListUtils.composeCompleteList()


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

    var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")


}



