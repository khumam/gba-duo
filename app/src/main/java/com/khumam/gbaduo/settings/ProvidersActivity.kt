package com.khumam.gbaduo.settings

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.khumam.gbaduo.R

/** Settings > Scraping > Providers: the sources covers come from, each with its own settings. */
class ProvidersActivity : PixelPage() {

    private val summaries = mutableListOf<Pair<Provider, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.providers_title))
        note(getString(R.string.providers_intro), top = 0)
        var first: android.view.View? = null
        for (provider in Providers.all) {
            val (card, summary) = menuCard(provider.icon, provider.tile, getString(provider.titleRes), "") {
                startActivity(Intent(this, provider.settings))
            }
            summaries += provider to summary
            if (first == null) first = card
        }
        focusFirst(first!!)
    }

    override fun onStart() {
        super.onStart()
        for ((provider, view) in summaries) view.text = provider.summary(this)
    }
}
