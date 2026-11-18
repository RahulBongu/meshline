package com.meshline.app.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    @Test
    fun parseHelpCommand() {
        val res1 = CommandParser.parse("/help")
        assertTrue(res1 is CommandParser.ParseResult.Executable)
        val cmd1 = (res1 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd1 is CommandParser.ChatCommand.Help)
        assertNull((cmd1 as CommandParser.ChatCommand.Help).topic)

        val res2 = CommandParser.parse("/help join")
        assertTrue(res2 is CommandParser.ParseResult.Executable)
        val cmd2 = (res2 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd2 is CommandParser.ChatCommand.Help)
        assertEquals("join", (cmd2 as CommandParser.ChatCommand.Help).topic)

        val res3 = CommandParser.parse("/h")
        assertTrue(res3 is CommandParser.ParseResult.Executable)
        assertTrue((res3 as CommandParser.ParseResult.Executable).command is CommandParser.ChatCommand.Help)

        val res4 = CommandParser.parse("/?")
        assertTrue(res4 is CommandParser.ParseResult.Executable)
        assertTrue((res4 as CommandParser.ParseResult.Executable).command is CommandParser.ChatCommand.Help)
    }

    @Test
    fun parseJoinCommand() {
        val res1 = CommandParser.parse("/join #emergency")
        assertTrue(res1 is CommandParser.ParseResult.Executable)
        val cmd1 = (res1 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd1 is CommandParser.ChatCommand.Join)
        assertEquals("#emergency", (cmd1 as CommandParser.ChatCommand.Join).channel)

        val res2 = CommandParser.parse("/j crypto")
        assertTrue(res2 is CommandParser.ParseResult.Executable)
        val cmd2 = (res2 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd2 is CommandParser.ChatCommand.Join)
        assertEquals("#crypto", (cmd2 as CommandParser.ChatCommand.Join).channel)

        // Missing argument produces Error
        val res3 = CommandParser.parse("/join")
        assertTrue(res3 is CommandParser.ParseResult.Error)
    }

    @Test
    fun parseChannelsCommand() {
        val res1 = CommandParser.parse("/channels")
        assertTrue(res1 is CommandParser.ParseResult.Executable)
        assertTrue((res1 as CommandParser.ParseResult.Executable).command is CommandParser.ChatCommand.Channels)

        val res2 = CommandParser.parse("/list")
        assertTrue(res2 is CommandParser.ParseResult.Executable)
        assertTrue((res2 as CommandParser.ParseResult.Executable).command is CommandParser.ChatCommand.Channels)
    }

    @Test
    fun parseMsgCommand() {
        val res1 = CommandParser.parse("/msg a1b2c3d4 Hello Alice!")
        assertTrue(res1 is CommandParser.ParseResult.Executable)
        val cmd1 = (res1 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd1 is CommandParser.ChatCommand.Msg)
        assertEquals("a1b2c3d4", (cmd1 as CommandParser.ChatCommand.Msg).recipient)
        assertEquals("Hello Alice!", cmd1.messageText)

        val res2 = CommandParser.parse("/dm bob meet me at 5")
        assertTrue(res2 is CommandParser.ParseResult.Executable)
        val cmd2 = (res2 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd2 is CommandParser.ChatCommand.Msg)
        assertEquals("bob", (cmd2 as CommandParser.ChatCommand.Msg).recipient)
        assertEquals("meet me at 5", cmd2.messageText)

        // Missing text produces Error
        val res3 = CommandParser.parse("/msg bob")
        assertTrue(res3 is CommandParser.ParseResult.Error)
    }

    @Test
    fun parseWhoCommand() {
        val res1 = CommandParser.parse("/who")
        assertTrue(res1 is CommandParser.ParseResult.Executable)
        assertTrue((res1 as CommandParser.ParseResult.Executable).command is CommandParser.ChatCommand.Who)

        val res2 = CommandParser.parse("/peers")
        assertTrue(res2 is CommandParser.ParseResult.Executable)
        assertTrue((res2 as CommandParser.ParseResult.Executable).command is CommandParser.ChatCommand.Who)
    }

    @Test
    fun parseNickCommand() {
        val res1 = CommandParser.parse("/nick Satoshi")
        assertTrue(res1 is CommandParser.ParseResult.Executable)
        val cmd1 = (res1 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd1 is CommandParser.ChatCommand.Nick)
        assertEquals("Satoshi", (cmd1 as CommandParser.ChatCommand.Nick).newNick)

        val res2 = CommandParser.parse("/nick")
        assertTrue(res2 is CommandParser.ParseResult.Error)
    }

    @Test
    fun parseBlockAndUnblockCommand() {
        val res1 = CommandParser.parse("/block maliciousPeer")
        assertTrue(res1 is CommandParser.ParseResult.Executable)
        val cmd1 = (res1 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd1 is CommandParser.ChatCommand.Block)
        assertEquals("maliciousPeer", (cmd1 as CommandParser.ChatCommand.Block).target)

        val res2 = CommandParser.parse("/unblock maliciousPeer")
        assertTrue(res2 is CommandParser.ParseResult.Executable)
        val cmd2 = (res2 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd2 is CommandParser.ChatCommand.Unblock)
        assertEquals("maliciousPeer", (cmd2 as CommandParser.ChatCommand.Unblock).target)
    }

    @Test
    fun parseClearAndSlapCommand() {
        val res1 = CommandParser.parse("/clear")
        assertTrue(res1 is CommandParser.ParseResult.Executable)
        assertTrue((res1 as CommandParser.ParseResult.Executable).command is CommandParser.ChatCommand.Clear)

        val res2 = CommandParser.parse("/slap Mallory")
        assertTrue(res2 is CommandParser.ParseResult.Executable)
        val cmd2 = (res2 as CommandParser.ParseResult.Executable).command
        assertTrue(cmd2 is CommandParser.ChatCommand.Slap)
        assertEquals("Mallory", (cmd2 as CommandParser.ChatCommand.Slap).target)
    }

    @Test
    fun parseOrdinaryTextAndEscapedSlash() {
        val res1 = CommandParser.parse("Hello world!")
        assertTrue(res1 is CommandParser.ParseResult.NotCommand)
        assertEquals("Hello world!", (res1 as CommandParser.ParseResult.NotCommand).text)

        val res2 = CommandParser.parse("//escaped slash")
        assertTrue(res2 is CommandParser.ParseResult.NotCommand)
        assertEquals("/escaped slash", (res2 as CommandParser.ParseResult.NotCommand).text)
    }

    @Test
    fun parseUnknownCommand() {
        val res = CommandParser.parse("/foobar baz")
        assertTrue(res is CommandParser.ParseResult.Unknown)
        assertEquals("foobar", (res as CommandParser.ParseResult.Unknown).commandName)
    }
}
