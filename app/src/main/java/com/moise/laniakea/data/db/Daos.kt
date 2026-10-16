package com.moise.laniakea.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun getAll(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): SongEntity?

    @Query("SELECT path FROM songs")
    suspend fun allPaths(): List<String>

    @Query("SELECT dedupKey FROM songs")
    suspend fun allDedupKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<SongEntity>)

    @Update
    suspend fun update(song: SongEntity)

    @Query("DELETE FROM songs WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayed = :now WHERE id = :id")
    suspend fun recordPlay(id: Long, now: Long)

    @Query("UPDATE songs SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE songs SET artworkUri = :art WHERE id IN (:ids)")
    suspend fun setArtwork(ids: List<Long>, art: String?)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :id ORDER BY position")
    suspend fun songIds(id: Long): List<Long>

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :id ORDER BY position")
    fun observeSongIds(id: Long): Flow<List<Long>>

    @Query("SELECT * FROM playlists WHERE parentId = :parent")
    suspend fun children(parent: Long): List<PlaylistEntity>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Update
    suspend fun update(p: PlaylistEntity)

    @Delete
    suspend fun delete(p: PlaylistEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun clearSongs(id: Long)

    @Insert
    suspend fun insertSongs(rows: List<PlaylistSongEntity>)

    @Transaction
    suspend fun setSongs(id: Long, songIds: List<Long>) {
        clearSongs(id)
        insertSongs(songIds.mapIndexed { i, s -> PlaylistSongEntity(id, i, s) })
    }

    @Query("UPDATE playlists SET parentId = :parent WHERE id = :id")
    suspend fun setParent(id: Long, parent: Long?)
}
