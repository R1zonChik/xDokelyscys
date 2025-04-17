package ru.refontstudio.xdokelyscys;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class AutoWalkClient implements ClientModInitializer {
    // Статические поля для хранения состояния автоходьбы
    public static String currentDirection = "";
    public static int remainingTicks = 0;
    public static boolean isLooped = false;
    public static boolean isActive = false;

    // Константы
    public static final String[] VALID_DIRECTIONS = {"forward", "back", "left", "right"};

    @Override
    public void onInitializeClient() {
        System.out.println("AutoWalk Client initialized - ready to intercept .autowalk commands");
    }
}