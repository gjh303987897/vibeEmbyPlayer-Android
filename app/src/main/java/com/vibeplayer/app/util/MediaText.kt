package com.vibeplayer.app.util

import com.vibeplayer.app.model.MediaItem
import java.util.Locale

/**
 * Localized season / episode label, mirroring the desktop client's
 * [`formatSeasonEpisode`]. For `zh_CN` this renders "第 1 季 第 3 集"; for other
 * locales "Season 1 Episode 3". Only the parts that are present are included,
 * and an empty string is returned when the item carries no episode index.
 */
fun seasonEpisodeText(item: MediaItem): String {
    val season = item.parentIndexNumber.takeIf { it.isNotBlank() }
    val episode = item.indexNumber.takeIf { it.isNotBlank() } ?: return ""
    return if (isChineseLocale()) {
        buildList {
            if (!season.isNullOrBlank()) add("第 $season 季")
            if (episode.isNotBlank()) add("第 $episode 集")
        }.joinToString(" ")
    } else {
        buildList {
            if (!season.isNullOrBlank()) add("Season $season")
            if (episode.isNotBlank()) add("Episode $episode")
        }.joinToString(" ")
    }
}

private fun isChineseLocale(): Boolean {
    val tag = LocaleHelper.currentLocaleTag
    if (tag.isNullOrBlank()) {
        // Follow the system locale, mirroring the desktop client's
        // "system" language mode.
        return Locale.getDefault().language == "zh"
    }
    return tag.equals("zh_CN", ignoreCase = true)
}
