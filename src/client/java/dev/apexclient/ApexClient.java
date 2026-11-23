package dev.apexclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class ApexClient implements ClientModInitializer {
    private static final MarketScanner SCANNER = new MarketScanner();
    private static KeyMapping openScannerKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("apexclient", "controls")
        );
        openScannerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.apexclient.open_scanner",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_M,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SCANNER.tick(client);

            while (openScannerKey.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreen(new AuctionScannerScreen(SCANNER));
                }
            }
        });
    }
}
