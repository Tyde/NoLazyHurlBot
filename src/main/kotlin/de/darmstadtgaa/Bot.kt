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
import java.io.File
import java.sql.Connection
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


fun main() {
    val conf = Config()
    val token = conf.token

    ApiContextInitializer.init()
    val botsApi = TelegramBotsApi()
    val noLazyHurlBot = NoLazyHurlBot(token)
    botsApi.registerBot(noLazyHurlBot)


    Database.connect("jdbc:sqlite:data.db", "org.sqlite.JDBC")
    TransactionManager.manager.defaultIsolationLevel =
        Connection.TRANSACTION_SERIALIZABLE // Or Connection.TRANSACTION_READ_UNCOMMITTED
    transaction {
        //addLogger(StdOutSqlLogger)
        SchemaUtils.create(Users)
        SchemaUtils.create(Runs)
        //println("Tournaments: ${Tournaments.selectAll()}")
    }

}


val logger = LoggerFactory.getLogger("de.darmstadtgaa.nolazyhurlbot")
class Config {
    private val config = ConfigurationProperties.systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationProperties.fromFile(File("bot.properties"))
    private val tokenKey = Key("bot.token", stringType)

    val token: String
        get() = config[tokenKey]

}


class NoLazyHurlBot(private val token: String) : TelegramLongPollingBot() {
    override fun getBotUsername(): String {
        return "NoLazyHurlBot"
    }

    override fun getBotToken(): String {
        return token
    }

    val hashtagRegex = Regex("#nolazyhurl",RegexOption.IGNORE_CASE)
    val lengthRegex = Regex("""(\d+)[\.,]*(\d*)\s*(km|mi[\s$])*""",RegexOption.IGNORE_CASE)
    var numberFormatter: NumberFormat = DecimalFormat("#0.00")

    private final val CORRECT_VALUE_CALLBACK = "correctValue"
    private final val WRONG_VALUE_CALLBACK = "wrongValue"

    private final val MILES_TO_KM = 1.609344

    override fun onUpdateReceived(update: Update?) {
        update?.let {
            logger.debug(it.toString())
            if(it.hasMessage()) {
                handleSimpleMessage(update)
            } else if (it.hasCallbackQuery()) {

                val command = it.callbackQuery.data
                val runId = command.split("/").getOrNull(1)?.toInt()
                if (command.startsWith(CORRECT_VALUE_CALLBACK)) {
                    confirmRunLength(runId, it)
                } else if(command.startsWith(WRONG_VALUE_CALLBACK)) {
                    cancelRunLength(runId, it)
                }
                logger.debug("Callback pressed")

            }
        }
    }

    private fun cancelRunLength(runId: Int?, it: Update) {
        transaction {
            val run = runId?.let { it -> Run.find { Runs.id eq it }.firstOrNull() }
            run?.delete()
        }

        //Change Message
        val messageIdToEdit = it.callbackQuery.message.messageId
        val chatIdToEdit = it.callbackQuery.message.chatId
        execute(DeleteMessage().apply {
            chatId = chatIdToEdit.toString()
            messageId = messageIdToEdit
        })
    }

    private fun confirmRunLength(runId: Int?, it: Update) {
        logger.debug("Value correct")

        //Confirm run
        transaction {
            val run = runId?.let { it -> Run.find { Runs.id eq it }.firstOrNull() }
            run?.isConfirmed = true
        }
        var composedList = composeList()


        //Change Message
        val messageIdToEdit = it.callbackQuery.message.messageId
        val chatIdToEdit = it.callbackQuery.message.chatId
        execute(EditMessageText().apply {
            chatId = chatIdToEdit.toString()
            messageId = messageIdToEdit
            text = composedList
        })
    }

    private fun composeList(): String {
        //Compose Message
        var composedList = transaction {
            val sums = (Runs innerJoin Users)
                .slice(
                    Runs.length.sum(),
                    Runs.length.count(),
                    Users.customAlias,
                    Users.officalName
                )
                .select { Runs.isConfirmed eq true }
                .groupBy(Runs.user)
                .orderBy(Runs.length.sum() to SortOrder.DESC)
            var count=0
            sums.fold("#NoLazyHurlListe", { acc, res ->
                val name = res.getOrNull(Users.customAlias) ?: res[Users.officalName]
                count ++
                acc + System.lineSeparator() + count+". "+
                        "$name: ${numberFormatter.format(res[Runs.length.sum()])} km in ${res[Runs.length.count()]} Läufen"
            })
        }

        val total = transaction {
            Runs.slice(Runs.length.sum()).selectAll().firstOrNull()?.getOrNull(
                Runs.length.sum())
        }

        composedList += System.lineSeparator() + System.lineSeparator() +
                "Gesamt: ${numberFormatter.format(total)} km"
        return composedList
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
        if (recievedText != null && hashtagRegex.containsMatchIn(recievedText)) {
            handleHashtag(recievedText, message)
        } else if(recievedText != null && recievedText.startsWith("/")) {
            val isGroupCommand = recievedText.equals("/list@NoLazyHurlBot",ignoreCase = true) &&
                    (update.message.chat.isGroupChat || update.message.chat.isSuperGroupChat)
            val isPrivateCommand = recievedText.startsWith("/list",ignoreCase = true) &&
                    (update.message.chat.isUserChat)
            if (isGroupCommand || isPrivateCommand) {
                val listString = composeList()

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
                    Run.find { Runs.user eq user.id }.fold("", { acc, run ->
                        acc + System.lineSeparator() +
                                "${run.time.format(formatter)}: ${numberFormatter.format(run.length)} km"
                    })
                }
                execute(SendMessage().apply {
                    chatId = message.chatId.toString()
                    text = resultText
                })
            } else if(recievedText.startsWith("/allruns")&& update.message.chat.isUserChat) {
                val resultText = transaction {
                    Run.all().fold("", { acc, run ->
                        acc + System.lineSeparator() +
                                "${run.time.format(formatter)} - ${run.user.customAlias?:run.user.officialName}: ${numberFormatter.format(run.length)} km"
                    })
                }
                execute(SendMessage().apply {
                    chatId = message.chatId.toString()
                    text = resultText
                })
            }

        }
    }

    private fun handleHashtag(recievedText: String, message: Message) {
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
                message.from.lastName?: ""
            )
            val forwardFrom = message.forwardFrom
            if (forwardFrom != null) {
                user = User.fromIdOrCreate(
                    forwardFrom.id,
                    forwardFrom.firstName ?: "",
                    forwardFrom.lastName ?: ""
                )
            }
            transaction {
                val run = Run.new {
                    this.user = user
                    this.length = number.toBigDecimal()
                    this.time = LocalDateTime.now()
                    this.isConfirmed = false
                }


                val textReturn = "Du bist ${numberFormatter.format(number)} km gelaufen, korrekt?"
                val command = SendMessage().apply {
                    chatId = message.chatId.toString()
                    text = textReturn
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


}
