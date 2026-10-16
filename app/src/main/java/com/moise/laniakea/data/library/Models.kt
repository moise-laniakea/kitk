package com.moise.laniakea.data.library

import com.moise.laniakea.data.db.SongEntity

data class ArtistItem(val name: String, val songCount: Int, val albumCount: Int)

data class AlbumItem(
    val key: String,            // albumArtist + album
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<SongEntity>,
)

/** One folder in the linear view (flat list) or a node in the hierarchical view. */
data class FolderItem(val path: String, val name: String, val songCount: Int)

data class FolderNode(
    val path: String,
    val name: String,
    val children: List<FolderNode>,
    val songs: List<SongEntity>,       // songs directly in this folder
    val totalSongs: Int,
)

/** Four-section artist profile hub. */
data class ArtistHub(
    val artist: String,
    val albums: List<AlbumItem>,       // multi-track releases by this artist
    val singles: List<AlbumItem>,      // one-track releases
    val appearsIn: List<AlbumItem>,    // albums by other artists with a feature credit
    val songs: List<SongEntity>,
)
