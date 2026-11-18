package com.meshline.app.commands

/**
 * IRC-style slash command parser for Meshline.
 *
 * Supported commands:
 *  - `/help [command]` : Display command usage and available capabilities.
 *  - `/join <channel>` : Switch to or join a channel (e.g. `#general`, `#emergency`).
 *  - `/channels`       : List currently joined and active channels.
 *  - `/msg <peer> <text>` : Send a direct encrypted message to a peer.
 *  - `/who`            : List discovered mesh neighbours and their hops.
 *  - `/nick <name>`    : Change local display nickname.
 *  - `/block <peerId>` : Block a peer by ID.
 *  - `/unblock <peerId>`: Unblock a previously blocked peer.
 *  - `/clear`          : Clear messages in the active chat view.
 *  - `/slap <peer>`    : Playful IRC novelty action text.
 */
object CommandParser {

    sealed interface ParseResult {
        /** Ordinary chat text; not a slash command. */
        data class NotCommand(val text: String) : ParseResult

        /** Valid command successfully parsed. */
        data class Executable(val command: ChatCommand) : ParseResult

        /** Unknown command name. */
        data class Unknown(val commandName: String) : ParseResult

        /** Command syntax error or missing arguments. */
        data class Error(val message: String, val usage: String) : ParseResult
    }

    sealed interface ChatCommand {
        data class Help(val topic: String?) : ChatCommand
        data class Join(val channel: String) : ChatCommand
        data object Channels : ChatCommand
        data class Msg(val recipient: String, val messageText: String) : ChatCommand
        data object Who : ChatCommand
        data class Nick(val newNick: String) : ChatCommand
        data class Block(val target: String) : ChatCommand
        data class Unblock(val target: String) : ChatCommand
        data object Clear : ChatCommand
        data class Slap(val target: String) : ChatCommand
    }

    /**
     * Parses [input]. If it starts with a single `/`, it is parsed as a slash command.
     * Escaped `//` is treated as literal leading `/` in ordinary chat text.
     */
    fun parse(input: String): ParseResult {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) {
            return ParseResult.NotCommand(input)
        }
        if (trimmed.startsWith("//")) {
            // Escaped leading slash: send as normal text with single slash
            return ParseResult.NotCommand(input.replaceFirst("//", "/"))
        }

        val parts = trimmed.substring(1).trim().split(Regex("\\s+"), limit = 2)
        val cmdName = parts[0].lowercase()
        val rest = if (parts.size > 1) parts[1].trim() else ""

        return when (cmdName) {
            "help", "h", "?" -> {
                ParseResult.Executable(ChatCommand.Help(rest.ifBlank { null }))
            }
            "join", "j" -> {
                if (rest.isBlank()) {
                    ParseResult.Error("Channel name required.", "/join <#channel>")
                } else {
                    val normalized = if (rest.startsWith("#")) rest.lowercase() else "#${rest.lowercase()}"
                    ParseResult.Executable(ChatCommand.Join(normalized))
                }
            }
            "channels", "list" -> {
                ParseResult.Executable(ChatCommand.Channels)
            }
            "msg", "dm", "tell", "w" -> {
                val msgParts = rest.split(Regex("\\s+"), limit = 2)
                if (msgParts.size < 2 || msgParts[0].isBlank() || msgParts[1].isBlank()) {
                    ParseResult.Error("Recipient and message required.", "/msg <peerId_or_nick> <message>")
                } else {
                    ParseResult.Executable(ChatCommand.Msg(msgParts[0], msgParts[1]))
                }
            }
            "who", "names", "peers" -> {
                ParseResult.Executable(ChatCommand.Who)
            }
            "nick" -> {
                if (rest.isBlank()) {
                    ParseResult.Error("Nickname required.", "/nick <new_nickname>")
                } else {
                    ParseResult.Executable(ChatCommand.Nick(rest))
                }
            }
            "block" -> {
                if (rest.isBlank()) {
                    ParseResult.Error("Peer ID or nickname required.", "/block <peerId>")
                } else {
                    ParseResult.Executable(ChatCommand.Block(rest))
                }
            }
            "unblock" -> {
                if (rest.isBlank()) {
                    ParseResult.Error("Peer ID or nickname required.", "/unblock <peerId>")
                } else {
                    ParseResult.Executable(ChatCommand.Unblock(rest))
                }
            }
            "clear" -> {
                ParseResult.Executable(ChatCommand.Clear)
            }
            "slap" -> {
                if (rest.isBlank()) {
                    ParseResult.Error("Target required.", "/slap <peer>")
                } else {
                    ParseResult.Executable(ChatCommand.Slap(rest))
                }
            }
            else -> ParseResult.Unknown(cmdName)
        }
    }

    /** Formatted help text for all commands or a specific command. */
    fun formatHelp(topic: String? = null): String = getHelpText(topic)

    fun getHelpText(topic: String? = null): String = when (topic?.lowercase()) {
        "join" -> "Usage: /join <#channel>\nJoin or switch to a public/geographic channel (e.g. /join #general)."
        "msg" -> "Usage: /msg <peer> <text>\nSend an end-to-end encrypted direct private message to a peer."
        "who" -> "Usage: /who\nList active peers discovered in your local BLE mesh neighbourhood."
        "nick" -> "Usage: /nick <name>\nUpdate your display nickname across the mesh (max 32 chars)."
        "block" -> "Usage: /block <peerId>\nBlock messages from a specific peer ID."
        "unblock" -> "Usage: /unblock <peerId>\nUnblock a previously blocked peer ID."
        "clear" -> "Usage: /clear\nClear message history in the current chat view."
        "channels" -> "Usage: /channels\nList all available and joined mesh channels."
        "slap" -> "Usage: /slap <peer>\nPlayfully slap a peer with a large trout."
        else -> """
            Meshline Commands:
             /join <#channel>       - Join or switch to a topic channel
             /channels              - List active channels
             /msg <peer> <text>     - Send a direct private encrypted message
             /who                   - List nearby peers in the BLE mesh
             /nick <name>           - Change your display nickname
             /block <peerId>        - Block a peer ID
             /unblock <peerId>      - Unblock a peer ID
             /clear                 - Clear current chat view
             /slap <peer>           - Novelty IRC interaction
             /help [command]        - Show detailed help for a command
        """.trimIndent()
    }
}
