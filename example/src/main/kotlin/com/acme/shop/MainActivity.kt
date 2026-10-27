package com.acme.shop

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import link.lander.android.Lander
import link.lander.android.LanderConfidence
import link.lander.android.LanderException
import link.lander.android.LanderLink

/**
 * Acme Shop: a home screen and a product screen at /product/:id. The seeded
 * link https://acme.golander.link/p-123 resolves to /product/123.
 */
class MainActivity : ComponentActivity() {
    private lateinit var title: TextView
    private lateinit var linkInfo: TextView
    private lateinit var status: TextView
    private lateinit var productButtons: View

    private var lastLink: LanderLink? = null
    private var productId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        // Lander: hand over the launch intent (with or without a link).
        Lander.handleIntent(intent)

        // Lander: every link, including the launch link and deferred links.
        // Links that arrive while the activity is stopped are buffered.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Lander.deepLinks.collect(::open)
            }
        }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Lander: links that arrive while the app is running.
        Lander.handleIntent(intent)
    }

    private fun open(link: LanderLink) {
        lastLink = link
        productId = Regex("^/product/([^/?#]+)").find(link.route)?.groupValues?.get(1)
        render()
    }

    private fun render() {
        val id = productId
        title.text = if (id == null) "Acme Shop" else "Product $id"
        productButtons.visibility = if (id == null) View.GONE else View.VISIBLE
        val link = lastLink
        linkInfo.text = buildString {
            appendLine("Install ID: ${Lander.installId ?: "loading"}")
            if (link == null) {
                append("No link yet. Try: adb shell am start -a android.intent.action.VIEW -d \"acmeshop://product/123\" com.acme.shop")
                return@buildString
            }
            appendLine("Route: ${link.route}")
            appendLine("URI: ${link.uriString}")
            appendLine("Resolved: ${link.isResolved}")
            if (link.isResolved) {
                appendLine("Link ID: ${link.linkId}")
                appendLine("Params: ${link.params}")
                appendLine("Campaign: ${link.campaign ?: "none"}, channel: ${link.channel ?: "none"}")
                appendLine("Deferred: ${link.isDeferred}")
                append("Confidence: ${link.confidence.wireName} via ${link.method}")
                if (link.confidence == LanderConfidence.PROBABILISTIC) {
                    append("\nA probabilistic match is a good guess, not proof.")
                }
            }
        }
    }

    private fun share() {
        val id = productId ?: return
        status.text = "Creating a link..."
        lifecycleScope.launch {
            try {
                val link = Lander.createLink(
                    path = "/product/$id",
                    params = mapOf("productId" to id),
                    title = "Trail runner",
                    channel = "share",
                )
                status.text = "Created ${link.url}"
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.url)
                startActivity(Intent.createChooser(send, "Share product"))
            } catch (e: LanderException) {
                status.text = "Could not create a link: ${e.code} (${e.message})"
            }
        }
    }

    private fun buy() {
        val id = productId ?: return
        // Queued on the device and sent in batches; attributed to the link that
        // brought the user here, when there is one.
        Lander.trackEvent(
            "purchase",
            mapOf("productId" to id, "amount" to 49.99, "currency" to "USD"),
            linkId = lastLink?.linkId,
        )
        status.text = "Purchase tracked"
    }

    private fun buildUi(): View {
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }
        title = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f) }
        linkInfo = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(16), 0, dp(16))
            setTextIsSelectable(true)
        }
        status = TextView(this)
        productButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply { text = "Share"; setOnClickListener { share() } })
            addView(Button(context).apply { text = "Buy"; setOnClickListener { buy() } })
        }
        val home = Button(this).apply {
            text = "Home"
            setOnClickListener {
                productId = null
                render()
            }
        }
        listOf(title, linkInfo, productButtons, home, status).forEach(column::addView)
        return ScrollView(this).apply { addView(column) }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
