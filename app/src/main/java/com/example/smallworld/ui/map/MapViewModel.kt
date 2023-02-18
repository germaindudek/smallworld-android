package com.example.smallworld.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smallworld.data.friends.FriendsRepository
import com.example.smallworld.data.profile.Profile
import com.example.smallworld.data.profile.ProfileRepository
import com.example.smallworld.data.profile.models.FriendshipStatus
import com.example.smallworld.data.search.SearchRepository
import com.example.smallworld.data.search.User
import com.example.smallworld.services.NetworkService
import com.example.smallworld.ui.map.components.BottomSheetVisibility
import com.example.smallworld.ui.snackbar.SnackBarMessage
import com.example.smallworld.ui.snackbar.SnackBarMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class MapSearchResultsState {
    NO_QUERY,
    NO_RESULTS,
    RESULTS
}

data class MapScreenState(
    val query: String = "",
    val searchResults: List<User> = emptyList(),
    val searchResultsState: MapSearchResultsState = MapSearchResultsState.NO_QUERY,
    val bottomSheetVisibility: BottomSheetVisibility = BottomSheetVisibility.HIDDEN,
    val profile: Profile? = null,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val networkService: NetworkService,
    private val snackBarMessageBus: SnackBarMessageBus,
    private val profileRepository: ProfileRepository,
    private val friendsRepository: FriendsRepository
) : ViewModel() {
    private val query: MutableStateFlow<String> = MutableStateFlow("")

    @OptIn(FlowPreview::class)
    private val debouncedQuery =
        query
            .debounce(100)
            .shareIn(viewModelScope, SharingStarted.Eagerly)

    private val searchResults: Flow<List<User>> = debouncedQuery
        .map {
            if (it.isBlank())
                emptyList()
            else try {
                searchRepository.search(it)
            } catch (e: Throwable) {
                if (networkService.isOnlineStateFlow.value) {
                    Timber.e(e)
                    snackBarMessageBus.sendMessage(SnackBarMessage.ERROR_UNKNOWN)
                } else {
                    snackBarMessageBus.sendMessage(SnackBarMessage.NO_NETWORK)
                }
                null
            }
        }
        .filterNotNull()
        .shareIn(viewModelScope, SharingStarted.Eagerly)

    private val searchResultsState: Flow<MapSearchResultsState> =
        debouncedQuery.zip(searchResults) { query, searchResults ->
            when {
                query.isBlank() -> MapSearchResultsState.NO_QUERY
                searchResults.isEmpty() -> MapSearchResultsState.NO_RESULTS
                else -> MapSearchResultsState.RESULTS
            }
        }

    private val bottomSheetVisibility = MutableStateFlow(BottomSheetVisibility.HIDDEN)
    private val profile = MutableStateFlow<Profile?>(null)

    private val _moveBottomSheet = MutableSharedFlow<BottomSheetVisibility>()
    val moveBottomSheet: SharedFlow<BottomSheetVisibility> = _moveBottomSheet

    val state: StateFlow<MapScreenState> = combine(
        query,
        searchResults,
        searchResultsState,
        bottomSheetVisibility,
        profile
    ) { query, searchResults, searchResultsState, bottomSheetVisibility, profile ->
        MapScreenState(query, searchResults, searchResultsState, bottomSheetVisibility, profile)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MapScreenState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSearchItemClick(user: User) {
        viewModelScope.launch {
            val profileResponse = try {
                profileRepository.getProfile(user.id)
            } catch (e: Throwable) {
                if (networkService.isOnlineStateFlow.value) {
                    Timber.e(e)
                    snackBarMessageBus.sendMessage(SnackBarMessage.ERROR_UNKNOWN)
                } else {
                    snackBarMessageBus.sendMessage(SnackBarMessage.NO_NETWORK)
                }
                return@launch
            }

            _moveBottomSheet.emit(BottomSheetVisibility.SHOWING)
            profile.value = profileResponse
        }
    }

    fun onFriendButtonClick() {
        viewModelScope.launch {
            val userId = profile.value?.userId ?: return@launch
            try {
                friendsRepository.sendRequest(userId)
                profile.value =
                    profile.value?.copy(friendshipStatus = FriendshipStatus.OUTGOING_REQUEST)
            } catch (e: Throwable) {
                if (networkService.isOnlineStateFlow.value) {
                    Timber.e(e)
                    snackBarMessageBus.sendMessage(SnackBarMessage.ERROR_UNKNOWN)
                } else {
                    snackBarMessageBus.sendMessage(SnackBarMessage.NO_NETWORK)
                }
                return@launch
            }
        }
    }

    fun onSheetVisbilityChanged(visibility: BottomSheetVisibility) {
        bottomSheetVisibility.value = visibility
    }
}