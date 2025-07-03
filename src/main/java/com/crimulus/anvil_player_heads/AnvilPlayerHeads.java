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
            Thread.sleep(500); // Don't call the API at every keystroke. Wait a tiny bit and check if the Player stopped typing
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
            ProfileComponent previous_profile_component = current_item.getItem().getComponents().get(DataComponentTypes.PROFILE);
            GameProfile previous_profile = previous_profile_component != null ? previous_profile_component.gameProfile() : null;
            Text custom_name = current_item.getCustomName();
            String custom_name_to_assign = custom_name != null ? custom_name.getString() : null;
            GameProfile profile_to_assign = previous_profile;

            custom_name_to_assign = newItemName; // Profile does not exists, just perform rename

            // Text entered links to a valid profile
            if (profile.isPresent() && !profile.get().getProperties().isEmpty()) {
                profile_to_assign = profile.get();
                custom_name_to_assign = null; // We don't want the head renamed if there is a new profile assigned to it
            }

            perform_rename(serverPlayer, anvilHandler, custom_name_to_assign, profile_to_assign);
        }, SkullBlockEntity.EXECUTOR);
        return Optional.empty();
    }

    static boolean is_rename_outdated(@NotNull ServerPlayerEntity serverPlayer, String newItemName) {
        return !ENTITY_ID_TO_LOOKUP_NAME.containsKey(serverPlayer.getId()) || !ENTITY_ID_TO_LOOKUP_NAME.get(serverPlayer.getId()).equals(newItemName);
    }

    static void perform_rename(@NotNull ServerPlayerEntity serverPlayer, AnvilScreenHandler anvilHandler, String new_name, GameProfile new_profile) {
        serverPlayer.getServer().executeSync(() -> {
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