package com.comedyplatform.mictracker.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Opening things in other apps: links in the browser, addresses in a maps app.
 * Every function returns false (instead of crashing) when no app can handle it.
 */
object Links {

    /** Opens [url] with ACTION_VIEW (browser, Instagram app, YouTube, ...). */
    fun openUrl(context: Context, url: String): Boolean = start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /**
     * Opens an address in a maps app via a `geo:` intent; falls back to
     * Google Maps on the web when no maps app is installed.
     */
    fun openMap(context: Context, address: String): Boolean {
        val geo = Uri.parse("geo:0,0?q=" + Uri.encode(address))
        if (start(context, Intent(Intent.ACTION_VIEW, geo))) return true
        return openUrl(context, "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(address))
    }

    private fun start(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

/** What [rememberLinkOpener] returns: open a URL or an address, with a toast on failure. */
class LinkOpener(private val context: Context) {
    fun openUrl(url: String) {
        if (!Links.openUrl(context, url)) toast("No app on this phone can open that link.")
    }

    fun openMap(address: String) {
        if (!Links.openMap(context, address)) toast("No maps app or browser found.")
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

/** Use inside a composable: `val links = rememberLinkOpener(); links.openUrl(url)`. */
@Composable
fun rememberLinkOpener(): LinkOpener {
    val context = LocalContext.current
    return remember(context) { LinkOpener(context) }
}
