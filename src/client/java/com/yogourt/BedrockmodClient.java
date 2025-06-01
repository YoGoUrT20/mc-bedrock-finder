package com.yogourt;

import net.fabricmc.api.ClientModInitializer;

public class BedrockmodClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BedrockScanCommand.register();
	}
}