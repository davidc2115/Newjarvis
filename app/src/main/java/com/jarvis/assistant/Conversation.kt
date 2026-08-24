package com.jarvis.assistant

data class Conversation(
    val id: String,
    var title: String,
    val messages: MutableList<Message> = mutableListOf()
)
