package com.defe.cloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DeFEPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DeFEProvider())
    }
}
