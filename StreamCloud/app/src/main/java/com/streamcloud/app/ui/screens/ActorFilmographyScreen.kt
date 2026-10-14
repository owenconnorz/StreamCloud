package com.streamcloud.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamcloud.app.data.ServiceLocator
import com.streamcloud.app.data.api.TmdbMovie
import com.streamcloud.app.ui.theme.LocalUiFormFactor
import com.streamcloud.app.ui.theme.UiFormFactor
import com.streamcloud.app.ui.theme.tvFocusBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class FilmographyCredit(
    val item: TmdbMovie,
    val mediaType: String,
    val role: String?,
)

@Composable
fun ActorFilmographyScreen(
    personId: Long,
    personName: String,
    onBack: () -> Unit,
    onMovieClick: (Long) -> Unit,
    onTvClick: (Long) -> Unit,
) {
    val context = LocalContext.current
    val serviceLocator = remember(context) { ServiceLocator.get(context) }
    val isTv = LocalUiFormFactor.current == UiFormFactor.Tv
    var credits by remember(personId) { mutableStateOf<List<FilmographyCredit>>(emptyList()) }
    var loading by remember(personId) { mutableStateOf(true) }
    var error by remember(personId) { mutableStateOf<String?>(null) }

    LaunchedEffect(personId) {
        loading = true
        error = null
        try {
            val (movieCredits, tvCredits) = coroutineScope {
                val movies = async {
                    serviceLocator.tmdb.personMovieCredits(personId, serviceLocator.tmdbApiKey)
                }
                val series = async {
                    serviceLocator.tmdb.personTvCredits(personId, serviceLocator.tmdbApiKey)
                }
                movies.await() to series.await()
            }
            credits = buildList {
                movieCredits.cast.forEach { add(FilmographyCredit(it, "movie", it.character)) }
                movieCredits.crew.forEach { add(FilmographyCredit(it, "movie", it.job)) }
                tvCredits.cast.forEach { add(FilmographyCredit(it, "tv", it.character)) }
                tvCredits.crew.forEach { add(FilmographyCredit(it, "tv", it.job)) }
            }
                .distinctBy { "${it.mediaType}:${it.item.id}" }
                .sortedByDescending { it.item.releaseDate ?: it.item.firstAirDate ?: "" }
            if (credits.isEmpty()) error = "No film or television credits were found."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message?.takeIf { it.isNotBlank() }
                ?: "Could not load this actor's credits."
        } finally {
            loading = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                personName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Movies and TV series",
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            credits.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (isTv) 170.dp else 132.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(
                    items = credits,
                    key = { "${it.mediaType}_${it.item.id}" },
                ) { credit ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .tvFocusBorder(RoundedCornerShape(10.dp))
                            .clickable {
                                if (credit.mediaType == "tv") onTvClick(credit.item.id)
                                else onMovieClick(credit.item.id)
                            },
                    ) {
                        AsyncImage(
                            model = credit.item.posterUrl,
                            contentDescription = credit.item.displayTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            credit.item.displayTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(if (credit.mediaType == "tv") "TV series" else "Movie")
                                val year = (credit.item.releaseDate ?: credit.item.firstAirDate)
                                    ?.take(4)
                                    ?.takeIf(String::isNotBlank)
                                if (year != null) append(" · $year")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        credit.role?.takeIf(String::isNotBlank)?.let { role ->
                            Text(
                                "as $role",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
