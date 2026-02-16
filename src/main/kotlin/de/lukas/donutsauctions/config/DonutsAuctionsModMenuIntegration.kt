package de.lukas.donutsauctions.config

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

class DonutsAuctionsModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent -> DonutsAuctionsConfigScreenFactory.create(parent) }
    }
}
