package com.vibeplayer.app.ui.services

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.vibeplayer.app.model.ServiceType

/**
 * Single source of truth for how a [ServiceType] is drawn, so the "add server"
 * type picker and the service cards always show the same glyph.
 *
 * The brand-ish Material icons are deliberately distinct at 24dp: Emby = play
 * badge, Jellyfin = water drop (jellyfish), WebDAV = cloud, IPTV = live TV,
 * Link = link.
 */
val ServiceType.pickerIcon: ImageVector
    get() = when (this) {
        ServiceType.EMBY -> Icons.Outlined.PlayCircle
        ServiceType.JELLYFIN -> Icons.Outlined.WaterDrop
        ServiceType.WEBDAV -> Icons.Outlined.Cloud
        ServiceType.IPTV -> Icons.Outlined.LiveTv
        ServiceType.LINK -> Icons.Outlined.Link
    }
