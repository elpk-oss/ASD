package com.recetasutiles.smarttools;

import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

public class SmartToolSwitcherClient implements ClientModInitializer {

	// Durabilidad restante a partir de la cual una herramienta se "guarda" y no se usa más
	private static final int MIN_DURABILITY = 5;

	private static final List<List<TagKey<Item>>> PRESETS = List.of(
			List.of(ItemTags.SWORDS, ItemTags.AXES, ItemTags.PICKAXES, ItemTags.SHOVELS),
			List.of(ItemTags.AXES, ItemTags.PICKAXES, ItemTags.SWORDS, ItemTags.SHOVELS));
	private static final List<String> PRESET_NAMES = List.of(
			"Espada > Hacha > Pico > Pala",
			"Hacha > Pico > Espada > Pala");

	private static KeyBinding toggleKey, combatKey, sortKey, presetKey, storeKey;
	private static boolean toolSwitching = true;
	private static boolean weaponSwitching = false;
	private static int preset = 0;
	private static int warnCooldown = 0;

	@Override
	public void onInitializeClient() {
		toggleKey = register("toggle", GLFW.GLFW_KEY_R);
		combatKey = register("combat", GLFW.GLFW_KEY_V);
		sortKey = register("sort", GLFW.GLFW_KEY_G);
		presetKey = register("preset", GLFW.GLFW_KEY_H);
		storeKey = register("store", GLFW.GLFW_KEY_K);

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

		// Teclas dentro de pantallas (inventario / cofres)
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof HandledScreen<?> handled) {
				ScreenKeyboardEvents.afterKeyPress(screen).register((s, key, scancode, modifiers) -> {
					MinecraftClient mc = MinecraftClient.getInstance();
					if (mc.player == null) return;
					if (storeKey.matchesKey(key, scancode)) {
						storeAll(mc, handled);
					} else if (sortKey.matchesKey(key, scancode) && s instanceof InventoryScreen) {
						sortHotbar(mc);
					} else if (presetKey.matchesKey(key, scancode)) {
						cyclePreset(mc);
					}
				});
			}
		});
	}

	private static KeyBinding register(String name, int key) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.smarttoolswitcher." + name, InputUtil.Type.KEYSYM, key, "category.smarttoolswitcher"));
	}

	private void onTick(MinecraftClient client) {
		if (client.player == null || client.world == null) return;

		while (toggleKey.wasPressed()) {
			toolSwitching = !toolSwitching;
			msg(client, "Cambio automático de herramienta: " + (toolSwitching ? "ACTIVADO" : "DESACTIVADO"));
		}
		while (combatKey.wasPressed()) {
			weaponSwitching = !weaponSwitching;
			msg(client, "Cambio automático de arma: " + (weaponSwitching ? "ACTIVADO" : "DESACTIVADO"));
		}
		while (sortKey.wasPressed()) {
			sortHotbar(client);
		}
		while (presetKey.wasPressed()) {
			cyclePreset(client);
		}
		while (storeKey.wasPressed()) {
			// solo funciona con un cofre abierto (ver evento de pantalla)
		}

		if (warnCooldown > 0) warnCooldown--;
		if (client.currentScreen != null) return;
		if (!client.options.attackKey.isPressed()) return;
		if (client.player.isUsingItem()) return;

		HitResult hit = client.crosshairTarget;
		if (hit == null) return;

		if (hit.getType() == HitResult.Type.BLOCK && toolSwitching) {
			handleBlock(client, (BlockHitResult) hit);
		} else if (hit.getType() == HitResult.Type.ENTITY && weaponSwitching) {
			handleWeapon(client);
		}
	}

	// ---------- Cambio automático de herramienta ----------

	private static boolean isLow(ItemStack s) {
		return s.isDamageable() && (s.getMaxDamage() - s.getDamage()) <= MIN_DURABILITY;
	}

	private static float usableSpeed(ItemStack s, BlockState state) {
		if (s.isEmpty()) return 1.0f;
		if (isLow(s)) return 0.0f;
		if (state.isToolRequired() && !s.isSuitableFor(state)) return 0.0f;
		return s.getMiningSpeedMultiplier(state);
	}

	private void handleBlock(MinecraftClient client, BlockHitResult hit) {
		BlockState state = client.world.getBlockState(hit.getBlockPos());
		if (state.isAir()) return;

		PlayerInventory inv = client.player.getInventory();
		ItemStack held = inv.getMainHandStack();

		if (isLow(held) && warnCooldown == 0) {
			msg(client, "¡Herramienta a punto de romperse! Se cambiará por otra.");
			warnCooldown = 100;
		}

		float bestSpeed = Math.max(usableSpeed(held, state), 1.0f);
		int best = -1;
		for (int i = 0; i < 36; i++) {
			if (i == inv.selectedSlot) continue;
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			float sp = usableSpeed(s, state);
			if (sp > bestSpeed + 0.001f) {
				bestSpeed = sp;
				best = i;
			}
		}
		if (best != -1) equip(client, best);
	}

	// ---------- Cambio automático de arma ----------

	private void handleWeapon(MinecraftClient client) {
		PlayerInventory inv = client.player.getInventory();
		ItemStack held = inv.getMainHandStack();
		if (held.isIn(ItemTags.SWORDS) && !isLow(held)) return;

		int best = findBest(inv, ItemTags.SWORDS, 0);
		if (best == -1) best = findBest(inv, ItemTags.AXES, 0);
		if (best != -1 && best != inv.selectedSlot) equip(client, best);
	}

	// ---------- Utilidades de inventario ----------

	/** Mejor item (mayor durabilidad máxima, luego mayor durabilidad restante) con el tag, desde el slot "from". */
	private static int findBest(PlayerInventory inv, TagKey<Item> tag, int from) {
		int best = -1;
		for (int j = from; j < 36; j++) {
			ItemStack s = inv.getStack(j);
			if (s.isEmpty() || !s.isIn(tag) || isLow(s)) continue;
			if (best == -1) {
				best = j;
				continue;
			}
			ItemStack b = inv.getStack(best);
			int sMax = s.getMaxDamage(), bMax = b.getMaxDamage();
			int sLeft = sMax - s.getDamage(), bLeft = bMax - b.getDamage();
			if (sMax > bMax || (sMax == bMax && sLeft > bLeft)) best = j;
		}
		return best;
	}

	private static int slotId(int invIndex) {
		return invIndex < 9 ? 36 + invIndex : invIndex;
	}

	private static void equip(MinecraftClient client, int invIndex) {
		PlayerInventory inv = client.player.getInventory();
		if (invIndex < 9) {
			inv.selectedSlot = invIndex;
		} else {
			client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId,
					invIndex, inv.selectedSlot, SlotActionType.SWAP, client.player);
		}
	}

	private static void swapToHotbar(MinecraftClient client, int invIndex, int hotbarPos) {
		client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId,
				slotId(invIndex), hotbarPos, SlotActionType.SWAP, client.player);
	}

	private static void sortHotbar(MinecraftClient client) {
		if (client.player == null || client.interactionManager == null) return;
		PlayerInventory inv = client.player.getInventory();
		List<TagKey<Item>> order = PRESETS.get(preset);
		for (int pos = 0; pos < order.size(); pos++) {
			int best = findBest(inv, order.get(pos), pos);
			if (best != -1 && best != pos) swapToHotbar(client, best, pos);
		}
		msg(client, "Hotbar ordenada: " + PRESET_NAMES.get(preset));
	}

	private static void cyclePreset(MinecraftClient client) {
		preset = (preset + 1) % PRESETS.size();
		msg(client, "Orden de hotbar: " + PRESET_NAMES.get(preset));
	}

	// ---------- Guardar todo en el cofre ----------

	private static void storeAll(MinecraftClient client, HandledScreen<?> screen) {
		ScreenHandler handler = screen.getScreenHandler();
		if (!(handler instanceof GenericContainerScreenHandler) && !(handler instanceof ShulkerBoxScreenHandler)) return;
		int moved = 0;
		for (Slot slot : handler.slots) {
			if (slot.inventory != client.player.getInventory()) continue;
			int idx = slot.getIndex();
			if (idx < 9 || idx > 35 || !slot.hasStack()) continue; // solo mochila, no hotbar
			client.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, client.player);
			moved++;
		}
		msg(client, moved > 0 ? "Inventario guardado en el cofre (la hotbar se mantiene)." : "No hay nada para guardar.");
	}

	private static void msg(MinecraftClient client, String text) {
		if (client.player != null) client.player.sendMessage(Text.literal(text), true);
	}
}
