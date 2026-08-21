package com.vibeplayer.app.model

/** A cast / crew member associated with a media item. */
data class MediaPerson(
    val id: String = "",
    val name: String = "",
    val role: String = "",
    val type: String = "",
    val imageTag: String = "",
    val imageUrl: String = ""
)
