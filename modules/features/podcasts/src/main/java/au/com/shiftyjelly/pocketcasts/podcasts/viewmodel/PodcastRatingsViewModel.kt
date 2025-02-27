package au.com.shiftyjelly.pocketcasts.podcasts.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsEvent
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTrackerWrapper
import au.com.shiftyjelly.pocketcasts.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.repositories.ratings.RatingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

private const val MAX_STARS = 5

@HiltViewModel
class PodcastRatingsViewModel
@Inject constructor(
    private val ratingsManager: RatingsManager,
    private val analyticsTracker: AnalyticsTrackerWrapper,
) : ViewModel(), CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    private var _stateFlow: MutableStateFlow<RatingState> = MutableStateFlow(RatingState.Loading)
    val stateFlow: StateFlow<RatingState> = _stateFlow

    fun loadRatings(podcastUuid: String) {
        if (FeatureFlag.isEnabled(Feature.SHOW_RATINGS_ENABLED)) {
            viewModelScope.launch {
                try {
                    ratingsManager.podcastRatings(podcastUuid)
                        .stateIn(viewModelScope)
                        .collect { ratings ->
                            _stateFlow.update {
                                RatingState.Loaded(
                                    podcastUuid = ratings.podcastUuid,
                                    stars = getStars(ratings.average),
                                    total = ratings.total
                                )
                            }
                        }
                } catch (e: IOException) {
                    Timber.e(e, "Failed to load podcast ratings")
                    _stateFlow.update { RatingState.Error }
                }
            }
        }
    }

    fun refreshPodcastRatings(uuid: String) {
        launch(Dispatchers.IO) {
            try {
                ratingsManager.refreshPodcastRatings(uuid)
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh podcast ratings")
            }
        }
    }

    private fun getStars(rating: Double): List<Star> {
        // truncate the floating points off without rounding
        val ratingInt = rating.toInt()
        // Get the float value
        val half = rating % 1

        val stars = (0 until MAX_STARS).map { index ->
            getStarFor(index, ratingInt, half)
        }
        return stars
    }

    fun onRatingStarsTapped(podcastUuid: String) {
        analyticsTracker.track(
            AnalyticsEvent.RATING_STARS_TAPPED,
            AnalyticsProp.ratingStarsTapped(podcastUuid)
        )
    }

    private fun getStarFor(index: Int, rating: Int, half: Double) = when {
        index < rating -> Star.FilledStar
        (index == rating) && (half >= 0.5) -> Star.HalfStar
        else -> Star.BorderedStar
    }

    sealed class RatingState {
        object Loading : RatingState()
        data class Loaded(
            val podcastUuid: String,
            val stars: List<Star>,
            val total: Int?,
        ) : RatingState() {
            val noRatings: Boolean
                get() = total == null || total == 0
        }

        object Error : RatingState()
    }

    enum class Star { FilledStar, HalfStar, BorderedStar }

    companion object {
        private object AnalyticsProp {
            private const val UUID_KEY = "uuid"
            fun ratingStarsTapped(podcastUuid: String) =
                mapOf(UUID_KEY to podcastUuid)
        }
    }
}
