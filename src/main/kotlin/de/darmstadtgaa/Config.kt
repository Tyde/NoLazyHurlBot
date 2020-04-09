package de.darmstadtgaa

import com.natpryce.konfig.*
import java.io.File

class Config {
    private val config = ConfigurationProperties.systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationProperties.fromFile(
                File(
                    "bot.properties"
                )
            )
    private val tokenKey =
        Key("bot.token", stringType)
    private val publicChatKey = Key(
        "bot.public_group",
        stringType
    )

    val token: String
        get() = config[tokenKey]

    val publicChatId: String
        get() = config[publicChatKey]

}