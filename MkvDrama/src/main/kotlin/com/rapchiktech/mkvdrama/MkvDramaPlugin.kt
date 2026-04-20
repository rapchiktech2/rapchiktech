package com.rapchiktech.mkvdrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MkvDramaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MkvDramaProvider())
    }
}
