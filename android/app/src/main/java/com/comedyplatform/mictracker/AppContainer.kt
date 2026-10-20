package com.comedyplatform.mictracker

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.comedyplatform.mictracker.data.ApiClient
import com.comedyplatform.mictracker.data.AuthRepository
import com.comedyplatform.mictracker.data.GeocodingService
import com.comedyplatform.mictracker.data.MicRepository
import com.comedyplatform.mictracker.data.SharedPreferencesSessionStore
import com.comedyplatform.mictracker.data.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection: creates every long-lived object once, when the
 * app starts (see [MicTrackerApplication]). Screens get what they need via
 * [appViewModel] / [appContainer] below.
 *
 *   config            Supabase URL + anon key from BuildConfig (may be blank)
 *   authRepository    sign in / out, token refresh
 *   apiClient         PostgREST + RPC calls with the user's token
 *   micRepository     THE shared data store (mics, sets, plans, ...) + every mutation
 *   geocodingService  address → coordinates with the phone's geocoder
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val config: SupabaseConfig = SupabaseConfig(
        url = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
    )

    /** Lives as long as the app; used for app-wide StateFlows. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val authRepository: AuthRepository = AuthRepository(
        config = config,
        http = httpClient,
        store = SharedPreferencesSessionStore(appContext),
    )

    val apiClient: ApiClient = ApiClient(
        config = config,
        http = httpClient,
        tokens = authRepository,
    )

    val micRepository: MicRepository = MicRepository(
        api = apiClient,
        scope = applicationScope,
        today = { today() },
    )

    val geocodingService: GeocodingService = GeocodingService(appContext)

    /** "Today" = the device's local date (spec 3.5). */
    fun today(): LocalDate = LocalDate.now()

    /**
     * Signs out (best-effort server logout), clears the stored tokens and
     * forgets all loaded data. The app's gate then shows the sign-in screen.
     * Manage → Account calls this.
     */
    suspend fun signOut() {
        authRepository.signOut()
        micRepository.clear()
    }
}

// =============================================================================
// Helpers for screens. Typical use inside a @Composable screen:
//
//     val vm = appViewModel { container -> TonightViewModel(container.micRepository) }
//
// For a ViewModel with arguments, pass a key so different arguments get
// different instances:
//
//     val vm = appViewModel(key = "mic-$micId") { c -> MicDetailViewModel(c.micRepository, micId) }
// =============================================================================

/** The app's [AppContainer], from inside any composable. */
@Composable
fun appContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as MicTrackerApplication).container }
}

/** A [ViewModelProvider.Factory] that builds a ViewModel from the [AppContainer]. */
inline fun <reified VM : ViewModel> appViewModelFactory(
    container: AppContainer,
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { create(container) }
}

/**
 * Gets (or creates) a ViewModel scoped to the current navigation destination,
 * built with dependencies from the [AppContainer].
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = appContainer()
    return viewModel(key = key, factory = appViewModelFactory(container, create))
}
