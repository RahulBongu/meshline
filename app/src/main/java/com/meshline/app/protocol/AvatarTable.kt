package com.meshline.app.protocol

/**
 * Fixed append-only AVATAR TABLE (PROTOCOL.md §6.1.1). Index 0..15 are frozen;
 * 16+ are reserved for emoji-font fruits (mango/coconut/blueberries) and
 * MUST NOT be advertised until the bundled emoji2 font lands.
 */
object AvatarTable {
    val FRUITS = listOf(
        "apple", "green apple", "pear", "orange", "lemon", "banana",
        "watermelon", "grapes", "strawberry", "melon", "cherries",
        "peach", "pineapple", "kiwi", "tomato", "avocado"
    )
    const val NEUTRAL = "neutral"
    const val MAX_INDEX = 15

    fun name(index: Int): String = FRUITS.getOrElse(index) { NEUTRAL }
    fun isKnown(index: Int): Boolean = index in 0..MAX_INDEX
}
