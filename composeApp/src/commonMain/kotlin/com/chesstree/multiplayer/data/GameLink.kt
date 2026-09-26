package com.chesstree.multiplayer.data

fun isValidGameCode(code: String): Boolean = code.matches(GAME_CODE)

fun gameCodeFromUrl(url: String): String? = url
    .substringBefore('#')
    .substringBefore('?')
    .substringAfter("/g/", missingDelimiterValue = "")
    .substringBefore('/')
    .uppercase()
    .takeIf(::isValidGameCode)

private val GAME_CODE = Regex("[0-9A-HJKMNP-TV-Z]{7}")
