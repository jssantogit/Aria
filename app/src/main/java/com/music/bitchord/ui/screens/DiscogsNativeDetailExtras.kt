package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
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
            "Parte dos dados do Discogs pode estar incompleta; a cobertura não foi confirmada.",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (page.editionFacts.isNotEmpty()) item(key = "discogs:source:edition-facts") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = 20.dp, vertical = 14.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                "Informações",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            page.editionFacts.take(6).forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
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
