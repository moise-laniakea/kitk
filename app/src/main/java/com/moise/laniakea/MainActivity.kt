package com.moise.laniakea

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.moise.laniakea.data.tags.ArtworkLoader
import com.moise.laniakea.ui.AlbumScreen
import com.moise.laniakea.ui.AppViewModel
import com.moise.laniakea.ui.ArtistScreen
import com.moise.laniakea.ui.CutterScreen
import com.moise.laniakea.ui.FolderScreen
import com.moise.laniakea.ui.LibraryScreen
import com.moise.laniakea.ui.NowPlayingScreen
import com.moise.laniakea.ui.PlaylistScreen
import com.moise.laniakea.ui.QueueScreen
import com.moise.laniakea.ui.Screen
import com.moise.laniakea.ui.SettingsScreen
import com.moise.laniakea.ui.TagEditorScreen
import com.moise.laniakea.ui.theme.MoiseTheme
import androidx.palette.graphics.Palette

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleViewIntent(intent)
        setContent { Root(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** "Open with" for audio files and playlists from other apps. */
    private fun handleViewIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        val name = contentResolver.query(data, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }.orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in com.moise.laniakea.data.playlist.PlaylistFormats.EXTENSIONS) {
            vm.importPlaylist(data, name)
        } else {
            vm.player.playUri(data, name.substringBeforeLast('.').ifBlank { "Audio" })
        }
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by vm.settings.collectAsState()
    val np by vm.nowPlaying.collectAsState()

    // ---- Permissions ----------------------------------------------------------------------------------
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissions = buildList {
        add(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.library.rescan() }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, audioPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permissions)
        }
    }

    // ---- System approval dialogs for delete/write requests --------------------------------------------------
    val approval by vm.approvalRequest.collectAsState()
    val approvalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.approvalFinished(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(approval) { approval?.let { approvalLauncher.launch(IntentSenderRequest.Builder(it).build()) } }

    val toast by vm.toast.collectAsState()
    LaunchedEffect(toast) { toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toast.value = null } }

    // ---- Playlist import picker -----------------------------------------------------------------------------------
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "Imported.m3u"
        vm.importPlaylist(uri, name)
    }

    // ---- Artwork-derived accent (Material 3 dynamic coloring from album art) --------------------------------------
    var seed by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(np.songId, settings.artworkColors, settings.useFolderArt) {
        seed = null
        val id = np.songId
        if (!settings.artworkColors || id == null) return@LaunchedEffect
        val song = vm.library.songsByIds(listOf(id)).firstOrNull() ?: return@LaunchedEffect
        val bmp = ArtworkLoader.load(context, song, 128, settings.useFolderArt) ?: return@LaunchedEffect
        val swatch = Palette.from(bmp).generate().let { it.vibrantSwatch ?: it.dominantSwatch }
        seed = swatch?.let { Color(it.rgb) }
    }

    BackHandler(enabled = vm.stack.size > 1 || vm.selecting) {
        if (vm.selecting) vm.clearSelection() else vm.back()
    }

    MoiseTheme(dynamicColor = settings.dynamicColor, artworkSeed = seed) {
        Surface(Modifier.fillMaxSize()) {
            when (val s = vm.stack.last()) {
                Screen.Library -> LibraryScreen(vm) { importer.launch(arrayOf("*/*")) }
                Screen.NowPlaying -> NowPlayingScreen(vm)
                Screen.Queue -> QueueScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                is Screen.Artist -> ArtistScreen(vm, s.name)
                is Screen.Album -> AlbumScreen(vm, s.key)
                is Screen.Folder -> FolderScreen(vm, s.path)
                is Screen.Playlist -> PlaylistScreen(vm, s.id)
                is Screen.TagEditor -> TagEditorScreen(vm, s.songIds)
                is Screen.Cutter -> CutterScreen(vm, s.songId)
            }
        }
    }
}
