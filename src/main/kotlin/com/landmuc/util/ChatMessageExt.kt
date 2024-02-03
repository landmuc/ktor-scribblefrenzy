package com.landmuc.util

import com.landmuc.data.models.ChatMessage

fun ChatMessage.matchesWord(word: String): Boolean {
    return message.lowercase().trim() == word.lowercase().trim()
}