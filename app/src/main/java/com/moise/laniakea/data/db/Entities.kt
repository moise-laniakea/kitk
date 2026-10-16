package com.moise.laniakea.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [Index("path", unique = true), Index("dedupKey"), Index("folder")],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val path: String,              // absolute path or relative path (MediaStore) used for folder browsing
    val folder: String,            // parent folder of path
    val title: String,
    val artist: String,            // raw tag string, may contain several artists
    val albumArtist: String,
    val album: String,
    val genre: String,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val durationMs: Long = 0,
    val mimeType: String = "",
    val extension: String = "",
    val size: Long = 0,
    val dateAdded: Long = 0,
    val dateModified: Long = 0,
    val playCount: Int = 0,
    val lastPlayed: Long = 0,
    val favorite: Boolean = false,
    val artworkUri: String? = null,
    val dedupKey: String = "",
    val hasEmbeddedLyrics: Boolean = false,
)

@Entity(tableName = "playlists", indices = [Index("parentId")])
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,       // playlist nesting: a playlist may contain child playlists
    val isSmart: Boolean = false,
    val rulesJson: String? = null,    // serialized SmartRules when isSmart
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "position"], indices = [Index("songId")])
data class PlaylistSongEntity(
    val playlistId: Long,
    val position: Int,
    val songId: Long,
)
