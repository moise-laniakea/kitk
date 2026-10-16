package com.moise.laniakea.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moise.laniakea.lyrics.LyricLine
import com.moise.laniakea.lyrics.Lyrics
import com.moise.laniakea.lyrics.LyricsKind

/**
 * Renders plain, line-synced and word/syllable-synced (karaoke) lyrics. Synced lyrics auto-scroll and
 * tapping a line seeks to it. In karaoke mode, syllables already sung use the accent color, the syllable in
 * progress is bright and the rest are dimmed.
 */
@Composable
fun LyricsView(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val active = remember(lyrics, positionMs / 100) { lyrics.activeIndex(positionMs) }

    LaunchedEffect(active) {
        if (active >= 0) state.animateScrollToItem((active - 2).coerceAtLeast(0))
    }

    if (lyrics.kind == LyricsKind.PLAIN) {
        LazyColumn(modifier.fillMaxSize(), state, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            itemsIndexed(lyrics.lines) { _, line ->
                Text(line.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
        return
    }

    LazyColumn(modifier.fillMaxSize(), state, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 120.dp, horizontal = 16.dp)) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isActive = index == active
            Box(Modifier.fillMaxWidth().clickable { onSeek(line.startMs) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (isActive && line.words.isNotEmpty()) karaoke(line, positionMs, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
                    else AnnotatedString(line.text),
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (isActive) 26.sp else 20.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal),
                    color = when {
                        isActive && line.words.isNotEmpty() -> Color.Unspecified
                        isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun karaoke(line: LyricLine, positionMs: Long, sung: Color, upcoming: Color): AnnotatedString = buildAnnotatedString {
    line.words.forEach { w ->
        val style = when {
            positionMs >= w.endMs && w.endMs > 0 -> SpanStyle(color = sung)
            positionMs >= w.startMs -> SpanStyle(color = sung.copy(alpha = 0.75f), fontWeight = FontWeight.ExtraBold)
            else -> SpanStyle(color = upcoming.copy(alpha = 0.5f))
        }
        withStyle(style) { append(w.text) }
    }
}
