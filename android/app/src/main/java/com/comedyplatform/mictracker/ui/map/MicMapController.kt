package com.comedyplatform.mictracker.ui.map

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.comedyplatform.mictracker.core.rules.GeoPoint
import com.comedyplatform.mictracker.core.rules.MapPreset
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Where to point the camera. */
data class CameraTarget(val latitude: Double, val longitude: Double, val zoom: Double) {
    companion object {
        fun of(preset: MapPreset): CameraTarget = CameraTarget(preset.latitude, preset.longitude, preset.zoom)
    }
}

/**
 * Everything that talks to MapLibre lives here, so the Compose screen only
 * says WHAT to show (style, pins, selection, location) and this class works
 * out HOW. All methods must be called on the main thread (Compose effects and
 * MapLibre callbacks already are).
 *
 * How the pins are drawn: one GeoJSON source holding a Point feature per mic
 * (properties "micId" and "color"), drawn by a CircleLayer whose color is
 * read from each feature's "color" property. A tap queries that layer around
 * the touch point and reports the mic id back through [onMicTapped].
 *
 * Changing the style (Voyager / Light / Dark) throws away all sources and
 * layers, so they're re-added every time a style finishes loading.
 *
 * @param tapRadiusPx how far from a circle's center a tap still counts
 */
class MicMapController(
    private val tapRadiusPx: Float,
    initialStyleUrl: String,
) {
    /** Called with the tapped mic's id, or null when the tap hit empty map. */
    var onMicTapped: (Int?) -> Unit = {}

    private var map: MapLibreMap? = null

    /** The loaded style, or null while a style is loading. */
    private var style: Style? = null
    private var requestedStyleUrl: String = initialStyleUrl
    private var detached = false

    // The latest data, kept so it can be (re)applied whenever a style loads.
    private var pins: List<MapPin> = emptyList()
    private var selectedPin: MapPin? = null
    private var myLocation: GeoPoint? = null

    /** A camera move requested before the map was ready. */
    private var pendingCamera: CameraTarget? = null

    /** Starts the (asynchronous) map setup. Call once, right after the MapView exists. */
    fun attach(mapView: MapView) {
        mapView.getMapAsync { readyMap ->
            if (!detached) onMapReady(readyMap)
        }
    }

    /** Where the camera is now (null before the map is ready). */
    fun currentCamera(): CameraTarget? {
        val position = map?.cameraPosition ?: return null
        val target = position.target ?: return null
        return CameraTarget(target.latitude, target.longitude, position.zoom)
    }

    /** Stops using the map (the MapView is about to be destroyed). */
    fun detach() {
        detached = true
        map = null
        style = null
    }

    fun setStyleUrl(url: String) {
        // Once the map is ready, requestedStyleUrl is always loaded or loading.
        if (url == requestedStyleUrl) return
        requestedStyleUrl = url
        loadStyle()
    }

    fun setPins(newPins: List<MapPin>) {
        pins = newPins
        updateSource(MIC_SOURCE_ID, micFeatures())
    }

    fun setSelected(pin: MapPin?) {
        selectedPin = pin
        updateSource(SELECTED_SOURCE_ID, selectedFeatures())
    }

    fun setMyLocation(point: GeoPoint?) {
        myLocation = point
        updateSource(ME_SOURCE_ID, myLocationFeatures())
    }

    /** Moves (animated) or jumps the camera. Remembered if the map isn't ready yet. */
    fun moveTo(target: CameraTarget, animate: Boolean = true) {
        val readyMap = map
        if (readyMap == null) {
            pendingCamera = target
            return
        }
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), target.zoom)
        if (animate) readyMap.animateCamera(update, CAMERA_ANIMATION_MS) else readyMap.moveCamera(update)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun onMapReady(readyMap: MapLibreMap) {
        map = readyMap
        // A flat, north-up city map is easier to read; rotating/tilting only confuses.
        readyMap.uiSettings.isRotateGesturesEnabled = false
        readyMap.uiSettings.isTiltGesturesEnabled = false
        readyMap.addOnMapClickListener { point -> handleTap(readyMap, point) }
        pendingCamera?.let { moveTo(it, animate = false) }
        pendingCamera = null
        loadStyle()
    }

    private fun loadStyle() {
        val readyMap = map ?: return // applied in onMapReady
        val url = requestedStyleUrl
        style = null
        readyMap.setStyle(Style.Builder().fromUri(url)) { loaded ->
            // Ignore a style that finished after we moved on (newer style, or screen gone).
            if (detached || map !== readyMap || url != requestedStyleUrl) return@setStyle
            style = loaded
            addMicLayers(loaded)
        }
    }

    /** Adds our three sources + layers to a freshly loaded style. */
    private fun addMicLayers(loaded: Style) {
        try {
            // 1. Highlight ring under the selected mic (drawn first = underneath).
            loaded.addSource(GeoJsonSource(SELECTED_SOURCE_ID, selectedFeatures()))
            loaded.addLayer(
                CircleLayer(SELECTED_LAYER_ID, SELECTED_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(13f),
                    PropertyFactory.circleOpacity(0f),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(SELECTED_RING_COLOR),
                ),
            )

            // 2. The mics, colored per feature by their "color" property (rule 17).
            loaded.addSource(GeoJsonSource(MIC_SOURCE_ID, micFeatures()))
            loaded.addLayer(
                CircleLayer(MIC_LAYER_ID, MIC_SOURCE_ID).withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get(PROP_COLOR))),
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleOpacity(0.9f),
                    PropertyFactory.circleStrokeWidth(1.5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                ),
            )

            // 3. "You are here" (white dot with a thick navy ring — unlike any borough color).
            loaded.addSource(GeoJsonSource(ME_SOURCE_ID, myLocationFeatures()))
            loaded.addLayer(
                CircleLayer(ME_LAYER_ID, ME_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(4f),
                    PropertyFactory.circleStrokeColor(ME_RING_COLOR),
                ),
            )
        } catch (e: RuntimeException) {
            // Adding the same id twice or touching a replaced style throws; the
            // next style load starts clean, so there's nothing else to do.
        }
    }

    private fun updateSource(sourceId: String, features: FeatureCollection) {
        val loaded = style ?: return // added with the latest data when the style loads
        try {
            if (!loaded.isFullyLoaded) return
            loaded.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(features)
        } catch (e: RuntimeException) {
            // The style was swapped out underneath us; the new one gets fresh data.
        }
    }

    /** Finds the mic under a tap (with some slack around the circle). */
    private fun handleTap(readyMap: MapLibreMap, point: LatLng): Boolean {
        if (style == null) return false
        val micId = try {
            val screen = readyMap.projection.toScreenLocation(point)
            val box = RectF(
                screen.x - tapRadiusPx,
                screen.y - tapRadiusPx,
                screen.x + tapRadiusPx,
                screen.y + tapRadiusPx,
            )
            readyMap.queryRenderedFeatures(box, MIC_LAYER_ID).firstNotNullOfOrNull { micIdOf(it) }
        } catch (e: RuntimeException) {
            null
        }
        onMicTapped(micId)
        return micId != null // true = we handled it
    }

    private fun micIdOf(feature: Feature): Int? = try {
        if (feature.hasNonNullValueForProperty(PROP_MIC_ID)) feature.getNumberProperty(PROP_MIC_ID)?.toInt() else null
    } catch (e: RuntimeException) {
        null // not a number
    }

    private fun micFeatures(): FeatureCollection = FeatureCollection.fromFeatures(
        pins.map { pin ->
            val feature = Feature.fromGeometry(Point.fromLngLat(pin.longitude, pin.latitude))
            feature.addNumberProperty(PROP_MIC_ID, pin.micId)
            feature.addStringProperty(PROP_COLOR, pin.colorHex)
            feature
        },
    )

    private fun selectedFeatures(): FeatureCollection {
        val pin = selectedPin ?: return FeatureCollection.fromFeatures(emptyList<Feature>())
        return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(pin.longitude, pin.latitude))))
    }

    private fun myLocationFeatures(): FeatureCollection {
        val point = myLocation ?: return FeatureCollection.fromFeatures(emptyList<Feature>())
        return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))))
    }

    companion object {
        private const val MIC_SOURCE_ID = "mics-source"
        private const val MIC_LAYER_ID = "mics-layer"
        private const val SELECTED_SOURCE_ID = "selected-mic-source"
        private const val SELECTED_LAYER_ID = "selected-mic-layer"
        private const val ME_SOURCE_ID = "my-location-source"
        private const val ME_LAYER_ID = "my-location-layer"

        private const val PROP_MIC_ID = "micId"
        private const val PROP_COLOR = "color"

        /** Yellow, like the web map's highlight. */
        private const val SELECTED_RING_COLOR = "#FFD600"
        private const val ME_RING_COLOR = "#0D47A1"

        private const val CAMERA_ANIMATION_MS = 600

        /** MapView options: start on [initialCamera] and use a TextureView (behaves inside Compose). */
        fun mapOptions(context: Context, initialCamera: CameraTarget): MapLibreMapOptions =
            MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .camera(
                    CameraPosition.Builder()
                        .target(LatLng(initialCamera.latitude, initialCamera.longitude))
                        .zoom(initialCamera.zoom)
                        .build(),
                )
    }
}

// =============================================================================
// MapView ↔ Compose lifecycle
//
// A MapView must be told about onCreate/onStart/onResume/onPause/onStop/
// onDestroy, normally by its Activity. Inside Compose we forward the events of
// the current LifecycleOwner (the navigation destination), and when the
// composable leaves the screen we shut the map down ourselves.
// =============================================================================

/**
 * Creates a [MapView] that follows the composable's lifecycle and is
 * destroyed when it leaves the composition.
 */
@Composable
internal fun rememberMapViewWithLifecycle(initialCamera: CameraTarget): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context, MicMapController.mapOptions(context, initialCamera)) }
    val tracker = remember(mapView) { MapViewLifecycleTracker(mapView) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Forward lifecycle events. Adding the observer replays the events up to
    // the current state (ON_CREATE, ON_START, ON_RESUME), so the map starts.
    DisposableEffect(lifecycleOwner, tracker) {
        val observer = LifecycleEventObserver { _, event -> tracker.onEvent(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Leaving the composition (switching tabs, opening a mic) → shut the map down.
    DisposableEffect(tracker) {
        onDispose { tracker.destroy() }
    }
    return mapView
}

/**
 * Calls the MapView's lifecycle methods in the only order it accepts
 * (create → start → resume → pause → stop → destroy), each at most once,
 * whatever order the events arrive in.
 */
internal class MapViewLifecycleTracker(private val mapView: MapView) {
    private var created = false
    private var started = false
    private var resumed = false
    private var destroyed = false

    fun onEvent(event: Lifecycle.Event) {
        if (destroyed) return
        when (event) {
            Lifecycle.Event.ON_CREATE -> create()
            Lifecycle.Event.ON_START -> {
                create()
                start()
            }
            Lifecycle.Event.ON_RESUME -> {
                create()
                start()
                resume()
            }
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> destroy()
            Lifecycle.Event.ON_ANY -> Unit
        }
    }

    fun destroy() {
        if (destroyed) return
        stop()
        if (created) mapView.onDestroy()
        destroyed = true
    }

    private fun create() {
        if (!created) {
            mapView.onCreate(null)
            created = true
        }
    }

    private fun start() {
        if (!started) {
            mapView.onStart()
            started = true
        }
    }

    private fun resume() {
        if (!resumed) {
            mapView.onResume()
            resumed = true
        }
    }

    private fun pause() {
        if (resumed) {
            mapView.onPause()
            resumed = false
        }
    }

    private fun stop() {
        pause()
        if (started) {
            mapView.onStop()
            started = false
        }
    }
}
