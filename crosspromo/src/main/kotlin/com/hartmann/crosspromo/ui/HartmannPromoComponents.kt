package com.hartmann.crosspromo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.launcher.PlayStoreLauncher
import com.hartmann.crosspromo.model.PromoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One promo card. Impression fires once when the card first composes visibly
 * (deduplicated per card+placement for the lifetime of this composable instance),
 * NOT on every recomposition.
 */
@Composable
fun HartmannPromoCard(
    app: PromoApp,
    placement: String,
    rankPosition: Int,
    requestId: String?,
    modifier: Modifier = Modifier,
    cardWidth: Int? = null,
) {
    val context = LocalContext.current
    var impressionSent by remember(app.packageName) { mutableStateOf(false) }

    // One impression per card instance — never per recomposition.
    LaunchedEffect(app.packageName) {
        if (!impressionSent) {
            impressionSent = true
            HartmannCrossPromo.impression(placement, app, app.selectionType, rankPosition, requestId)
        }
    }

    val clickLabel = "View ${app.name} on Google Play"
    Card(
        modifier = modifier
            .then(if (cardWidth != null) Modifier.width(cardWidth.dp) else Modifier)
            .semantics { contentDescription = clickLabel },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = {
            HartmannCrossPromo.click(placement, app, app.selectionType, rankPosition, requestId)
            PlayStoreLauncher.openPlayStore(
                context,
                app.packageName,
                referrer = PlayStoreLauncher.buildReferrer(
                    HartmannCrossPromo.sourcePackage,
                    app.packageName,
                ),
            )
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(app.iconUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null, // decorative; card has its own description
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    app.ratingLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            app.shortDescription?.let {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(4.dp))
            TextButton(onClick = {
                HartmannCrossPromo.click(placement, app, app.selectionType, rankPosition, requestId)
                PlayStoreLauncher.openPlayStore(
                    context,
                    app.packageName,
                    referrer = PlayStoreLauncher.buildReferrer(
                        HartmannCrossPromo.sourcePackage,
                        app.packageName,
                    ),
                )
            }) {
                Text("View app")
            }
        }
    }
}

/**
 * Vertical promo list (stacked cards). Renders nothing when there is no content
 * or when the SDK was never initialized.
 */
@Composable
fun HartmannPromoList(
    placement: String,
    modifier: Modifier = Modifier,
    sectionLabel: String = "More from Hartmann Studios",
    maxCards: Int = 3,
) {
    val repo = HartmannCrossPromo.repository(placement) ?: return
    var state by remember(placement) {
        mutableStateOf(repo.state.value)
    }
    var loaded by remember(placement) { mutableStateOf(false) }

    LaunchedEffect(placement, maxCards) {
        val current = HartmannCrossPromo.repository(placement) ?: return@LaunchedEffect
        val collectJob = launch {
            current.state.collect { state = it }
        }
        HartmannCrossPromo.loadPlacement(placement, maxCards) { }
        loaded = true
        collectJob.join()
    }

    val apps = state.apps.take(maxCards)
    if (apps.isEmpty()) return // hide silently when nothing to show

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = sectionLabel,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        apps.forEachIndexed { index, app ->
            HartmannPromoCard(
                app = app,
                placement = placement,
                rankPosition = index + 1,
                requestId = state.requestId,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Horizontal promo carousel — the default safe layout: one row, 2-3 cards.
 * Renders nothing when there is no content or when the SDK was never initialized.
 */
@Composable
fun HartmannPromoCarousel(
    placement: String,
    modifier: Modifier = Modifier,
    sectionLabel: String = "More from Hartmann Studios",
    maxCards: Int = 3,
    cardWidth: Int = 260,
) {
    val repo = HartmannCrossPromo.repository(placement) ?: return
    var state by remember(placement) {
        mutableStateOf(repo.state.value)
    }

    LaunchedEffect(placement, maxCards) {
        val current = HartmannCrossPromo.repository(placement) ?: return@LaunchedEffect
        val collectJob = launch {
            current.state.collect { state = it }
        }
        HartmannCrossPromo.loadPlacement(placement, maxCards) { }
        collectJob.join()
    }

    val apps = state.apps.take(maxCards)
    if (apps.isEmpty()) return // hide silently when nothing to show

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = sectionLabel,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                HartmannPromoCard(
                    app = app,
                    placement = placement,
                    rankPosition = apps.indexOf(app) + 1,
                    requestId = state.requestId,
                    cardWidth = cardWidth,
                )
            }
        }
    }
}

/** Convenience alias matching the requested XML-free default component name. */
@Composable
fun HartmannCrossPromoRow(
    placement: String,
    modifier: Modifier = Modifier,
    sectionLabel: String = "More from Hartmann Studios",
    maxCards: Int = 3,
) {
    HartmannPromoCarousel(placement, modifier, sectionLabel, maxCards)
}
