package com.chesstree.multiplayer.data

fun gameCodeFromUrl(url: String): String? = url
    .substringBefore('#')
    .substringBefore('?')
    .substringAfter("/g/", missingDelimiterValue = "")
    .substringBefore('/')
    .uppercase()
    .takeIf { it.matches(GAME_CODE) }

private val GAME_CODE = Regex("[0-9A-HJKMNP-TV-Z]{7}")
