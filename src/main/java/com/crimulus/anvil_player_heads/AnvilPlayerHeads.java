package com.crimulus.anvil_player_heads;

import com.crimulus.anvil_player_heads.mixin.AnvilScreenHandlerAccessor;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.UserCache;
import net.minecraft.util.collection.DefaultedList;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AnvilPlayerHeads implements ModInitializer {
    public static final String MOD_ID = "anvil-player-heads";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Map<Integer, String> ENTITY_ID_TO_LOOKUP_NAME = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
    }

    public static boolean applyRenaming(final PlayerEntity player, @NotNull DefaultedList<Slot> slots, final String newItemName) {
        Item leftItem = slots.get(0).getStack().getItem();
        int middleCount = slots.get(1).getStack().getCount();

        if (
                leftItem != Items.PLAYER_HEAD ||
                middleCount != 0 ||
                !(player instanceof final ServerPlayerEntity serverPlayer) ||
                !(serverPlayer.currentScreenHandler instanceof AnvilScreenHandler anvilHandler)
        ) {
            return false;
        }

        ENTITY_ID_TO_LOOKUP_NAME.put(player.getId(), newItemName);

        if (newItemName.isEmpty()) {
            // If player removes custom name, gives back a default PlayerHead without a custom name
            perform_rename(serverPlayer, anvilHandler, null, null);
            return true;
        }

        CompletableFuture.supplyAsync(
                () -> fetch_player_profile(serverPlayer, anvilHandler, newItemName)
        );
        return true;
    }

    static Optional<String> fetch_player_profile(final ServerPlayerEntity serverPlayer, final AnvilScreenHandler anvilHandler, final String newItemName) {
        try {
            Thread.sleep(200); // Don't call the API at every keystroke. Wait a tiny bit and check if the Player stopped typing
        } catch (InterruptedException e) {
            // Interrupted
        }
        if (is_rename_outdated(serverPlayer, newItemName)) {
            return Optional.empty(); // Player changed the head name, return early
        }

        // This prevents MC to cache an OfflinePlayerData in Singleplayer if the HTTP call fails for some reason
        // (too many requests / Timeout / ...)
        UserCache.setUseRemote(true);

        SkullBlockEntity.fetchProfileByName(newItemName).thenAcceptAsync(profile -> {
            if (is_rename_outdated(serverPlayer, newItemName)) {
                return;
            }
            ItemStack current_item = anvilHandler.slots.getFirst().getStack();
            ProfileComponent previous_value = current_item.get(DataComponentTypes.PROFILE);
            GameProfile previous_profile = previous_value != null ? previous_value.gameProfile() : null;
            Text custom_name = current_item.getCustomName();
            // Begin by keeping the previous item attributes
            String new_name = custom_name != null ? custom_name.getString() : null;
            GameProfile new_profile = previous_profile;

            if (profile.isPresent() && !profile.get().getProperties().isEmpty()) {
                // If user inputted a valid profile name, then use it.
                new_profile = profile.get();

                if (previous_value != null && previous_value.gameProfile().getId() != new_profile.getId()) {
                    new_name = null; // reset custom name if Profile Changed
                }
            } else {
                new_name = newItemName; // Profile does not exists, just perform rename
            }

            perform_rename(serverPlayer, anvilHandler, new_name, new_profile);
        }, SkullBlockEntity.EXECUTOR);
        return Optional.empty();
    }

    static boolean is_rename_outdated(@NotNull ServerPlayerEntity serverPlayer, String newItemName) {
        return !ENTITY_ID_TO_LOOKUP_NAME.containsKey(serverPlayer.getId()) || !ENTITY_ID_TO_LOOKUP_NAME.get(serverPlayer.getId()).equals(newItemName);
    }

    static void perform_rename(@NotNull ServerPlayerEntity serverPlayer, AnvilScreenHandler anvilHandler, String new_name, GameProfile new_profile) {
        serverPlayer.server.executeSync(() -> {
            ((AnvilScreenHandlerAccessor) anvilHandler).aph$getLevelCost().set(1);
            ItemStack newItem = anvilHandler.slots.getFirst().getStack().copy();

            if (new_name != null) {
                newItem.set(DataComponentTypes.CUSTOM_NAME, Text.literal(new_name));
            } else {
                newItem.remove(DataComponentTypes.CUSTOM_NAME);
            }
            if (new_profile != null) {
                newItem.set(DataComponentTypes.PROFILE, new ProfileComponent(new_profile));
            } else {
                newItem.remove(DataComponentTypes.PROFILE);
            }

            anvilHandler.slots.get(2).setStack(newItem);
            anvilHandler.sendContentUpdates();
        });
    }
}