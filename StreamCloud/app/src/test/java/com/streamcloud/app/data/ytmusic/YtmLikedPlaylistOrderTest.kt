package com.streamcloud.app.data.ytmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class YtmLikedPlaylistOrderTest {

    @Test
    fun prioritizeLikedMusicPlaylist_movesYouTubeLikedPlaylistToFront() {
        val playlists = listOf(
            playlist("PLmix", "My Mix"),
            playlist("VLLM", "Liked Music"),
            playlist("PLsaved", "Saved Playlist"),
        )

        val ordered = playlists.prioritizeLikedMusicPlaylist()

        assertEquals(listOf("VLLM", "PLmix", "PLsaved"), ordered.map { it.id })
    }

    @Test
    fun prioritizeLikedMusicPlaylist_recognizesLikedTitleWhenBrowseIdVaries() {
        val likedPlaylist = playlist("VLcustom", "Liked Songs")

        assertEquals(true, likedPlaylist.isLikedMusicPlaylist())
    }

    @Test
    fun prioritizeLikedMusicPlaylist_leavesListsWithoutLikedPlaylistUnchanged() {
        val playlists = listOf(
            playlist("PLmix", "My Mix"),
            playlist("PLsaved", "Saved Playlist"),
        )

        assertEquals(playlists, playlists.prioritizeLikedMusicPlaylist())
    }

    @Test
    fun prioritizeLikedMusicPlaylist_doesNotTreatLikedAlbumAsPlaylist() {
        val likedAlbum = playlist("VLLM", "Liked Music").copy(isAlbum = true)

        assertEquals(false, likedAlbum.isLikedMusicPlaylist())
        assertEquals(listOf(likedAlbum), listOf(likedAlbum).prioritizeLikedMusicPlaylist())
    }

    private fun playlist(id: String, title: String) = YtmPlaylist(
        id = id,
        title = title,
        thumbnail = null,
        subtitle = null,
    )
}
