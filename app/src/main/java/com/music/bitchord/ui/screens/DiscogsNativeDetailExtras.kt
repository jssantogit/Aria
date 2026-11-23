package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.DiscogsNativePage

/**
 * Source-owned footer items for the ONE existing native DetailScreen.
 *
 * This is deliberately not a screen, route or DetailPage replacement.
 * MusicBrainz, Apple and Discogs all share the same artist/release compositor.
 */
internal fun LazyListScope.discogsNativeDetailExtras(
    page: DiscogsNativePage,
    onOpenSource: (String) -> Unit,
) {
    if (page.incomplete) item(key = "discogs:artist:partial") {
        Text(
            "Este é um recorte do catálogo Discogs; pode haver outros lançamentos.",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    page.attributionUrl?.let { url ->
        item(key = "discogs:source:disclaimer") {
            Text(
                "This application uses Discogs’ API but is not affiliated with, sponsored or endorsed by Discogs. ‘Discogs’ is a trademark of Zink Media, LLC.",
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = 20.dp, vertical = 12.dp,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "discogs:source:attribution") {
            Text(
                "Data provided by Discogs. · Abrir fonte",
                modifier = Modifier.fillMaxWidth()
                    .clickable { onOpenSource(url) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
