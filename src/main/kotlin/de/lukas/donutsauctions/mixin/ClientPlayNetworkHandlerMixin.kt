package de.lukas.donutsauctions.mixin

import de.lukas.donutsauctions.DonutsAuctionsClient
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket
import org.slf4j.LoggerFactory
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientPlayNetworkHandler::class)
abstract class ClientPlayNetworkHandlerMixin {

    @Shadow
    @Final
    private lateinit var client: MinecraftClient

    @Inject(method = ["onOpenScreen"], at = [At("HEAD")], cancellable = true)
    private fun suppressAutomationScreen(packet: OpenScreenS2CPacket, ci: CallbackInfo) {
        val rawTitle = packet.name.string
        if (!DonutsAuctionsClient.shouldSuppressAuctionScreenOpen(rawTitle)) {
            return
        }

        val player = client.player ?: return
        try {
            val handler = packet.screenHandlerType.create(packet.syncId, player.inventory)
            player.currentScreenHandler = handler
            DonutsAuctionsClient.onHeadlessAuctionScreenOpened(rawTitle, packet.syncId)
            ci.cancel()
        } catch (ex: Exception) {
            logger.error("Failed to open headless auction handler", ex)
            DonutsAuctionsClient.onHeadlessAuctionScreenClosed("Headless handler setup failed")
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger("DonutsAuctions")
    }
}
