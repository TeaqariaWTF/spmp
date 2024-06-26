package com.toasterofbread.spmp.ui.layout.nowplaying.container

import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import com.toasterofbread.spmp.model.settings.category.ThemeSettings
import com.toasterofbread.spmp.model.settings.rememberMutableEnumState
import com.toasterofbread.spmp.service.playercontroller.PlayerState
import com.toasterofbread.composekit.utils.common.getValue
import LocalPlayerState

@Composable
internal fun LandscapePlayerBackground(page_height: Dp) {
    val player: PlayerState = LocalPlayerState.current

    val default_background_opacity: Float by ThemeSettings.Key.NOWPLAYING_DEFAULT_BACKGROUND_IMAGE_OPACITY.rememberMutableState()
    val song_background_opacity: Float? by player.status.m_song?.BackgroundImageOpacity?.observe(player.database)

    val default_video_position: ThemeSettings.VideoPosition by ThemeSettings.Key.NOWPLAYING_DEFAULT_VIDEO_POSITION.rememberMutableEnumState()
    val song_video_position: ThemeSettings.VideoPosition? by player.status.m_song?.VideoPosition?.observe(player.database)

    var video_showing: Boolean = false

    if ((song_video_position ?: default_video_position) == ThemeSettings.VideoPosition.BACKGROUND) {
        video_showing = VideoBackground(
            Modifier.requiredSize(player.screen_size.width, page_height),
            getAlpha = {
                song_background_opacity ?: default_background_opacity
            }
        )
    }

    if (!video_showing) {
        ThumbnailBackground(
            Modifier.requiredSize(maxOf(page_height, player.screen_size.width)),
            getAlpha = {
                song_background_opacity ?: default_background_opacity
            }
        )
    }
}
