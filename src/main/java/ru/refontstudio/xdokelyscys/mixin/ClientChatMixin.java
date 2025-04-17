package ru.refontstudio.xdokelyscys.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.LiteralText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.refontstudio.xdokelyscys.XDokelyscys;

import java.util.Arrays;

@Mixin(ClientPlayerEntity.class)
public class ClientChatMixin {

    /**
     * Перехватываем отправку сообщений чата на клиенте
     */
    @Inject(
            method = "sendChatMessage",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onSendChatMessage(String message, CallbackInfo ci) {
        try {
            if (message.startsWith(".autowalk")) {
                // Разбиваем команду на части
                String[] parts = message.trim().split("\\s+");

                // Обрабатываем команду stop
                if (parts.length >= 2 && "stop".equals(parts[1].toLowerCase())) {
                    XDokelyscys.stopMovement();
                    ci.cancel();
                    return;
                }

                // Обработка команд для скриптов
                if (parts.length >= 2) {
                    String command = parts[1].toLowerCase();

                    // Команды для записи скриптов
                    if ("record".equals(command)) {
                        if (parts.length < 3) {
                            ClientPlayerEntity player = (ClientPlayerEntity)(Object)this;
                            player.sendMessage(new LiteralText("§c[AutoWalk] §fУкажите имя скрипта: .autowalk record <name>"), false);
                            ci.cancel();
                            return;
                        }
                        XDokelyscys.startRecording(parts[2]);
                        ci.cancel();
                        return;
                    } else if ("stoprecord".equals(command)) {
                        XDokelyscys.stopRecording(true);
                        ci.cancel();
                        return;
                    } else if ("cancelrecord".equals(command)) {
                        XDokelyscys.stopRecording(false);
                        ci.cancel();
                        return;
                    }

                    // Команды для воспроизведения скриптов
                    if ("play".equals(command)) {
                        if (parts.length < 3) {
                            ClientPlayerEntity player = (ClientPlayerEntity)(Object)this;
                            player.sendMessage(new LiteralText("§c[AutoWalk] §fУкажите имя скрипта: .autowalk play <name>"), false);
                            ci.cancel();
                            return;
                        }
                        XDokelyscys.playScript(parts[2], false); // Обычное воспроизведение (без цикла)
                        ci.cancel();
                        return;
                    } else if ("loopplay".equals(command)) {
                        if (parts.length < 3) {
                            ClientPlayerEntity player = (ClientPlayerEntity)(Object)this;
                            player.sendMessage(new LiteralText("§c[AutoWalk] §fУкажите имя скрипта: .autowalk loopplay <name>"), false);
                            ci.cancel();
                            return;
                        }
                        XDokelyscys.playScript(parts[2], true); // Воспроизведение с зацикливанием
                        ci.cancel();
                        return;
                    } else if ("stopplay".equals(command)) {
                        XDokelyscys.stopPlayback();
                        ci.cancel();
                        return;
                    } else if ("scripts".equals(command)) {
                        XDokelyscys.listScripts();
                        ci.cancel();
                        return;
                    }

                    // Команда loop без указания времени
                    if ("loop".equals(command)) {
                        if (parts.length < 3) {
                            XDokelyscys.showUsage();
                            ci.cancel();
                            return;
                        }

                        // Получаем направление
                        String direction = parts[2].toLowerCase();
                        if (!Arrays.asList(XDokelyscys.VALID_DIRECTIONS).contains(direction)) {
                            ClientPlayerEntity player = (ClientPlayerEntity)(Object)this;
                            player.sendMessage(new LiteralText("§c[AutoWalk] §fНеизвестное направление: " + direction), false);
                            player.sendMessage(new LiteralText("§c[AutoWalk] §fДоступные направления: forward, back, left, right"), false);
                            ci.cancel();
                            return;
                        }

                        // Запускаем движение в цикле с большой длительностью по умолчанию
                        XDokelyscys.startMovement(direction, XDokelyscys.DEFAULT_LOOP_SECONDS, true);
                        ci.cancel();
                        return;
                    }
                }

                // Команда обычного движения: .autowalk <seconds> <direction>
                if (parts.length >= 3) {
                    int seconds;
                    try {
                        seconds = Integer.parseInt(parts[1]);
                        if (seconds <= 0) {
                            ClientPlayerEntity player = (ClientPlayerEntity)(Object)this;
                            player.sendMessage(new LiteralText("§c[AutoWalk] §fВремя должно быть больше 0 секунд"), false);
                            ci.cancel();
                            return;
                        }
                    } catch (NumberFormatException e) {
                        ClientPlayerEntity player = (ClientPlayerEntity)(Object)this;
                        player.sendMessage(new LiteralText("§c[AutoWalk] §fНеверный формат времени: " + parts[1]), false);
                        XDokelyscys.showUsage();
                        ci.cancel();
                        return;
                    }

                    String direction = parts[2].toLowerCase();
                    if (!Arrays.asList(XDokelyscys.VALID_DIRECTIONS).contains(direction)) {
                        ClientPlayerEntity player = (ClientPlayerEntity)(Object)this;
                        player.sendMessage(new LiteralText("§c[AutoWalk] §fНеизвестное направление: " + direction), false);
                        player.sendMessage(new LiteralText("§c[AutoWalk] §fДоступные направления: forward, back, left, right"), false);
                        ci.cancel();
                        return;
                    }

                    // Запускаем обычное движение
                    XDokelyscys.startMovement(direction, seconds, false);
                    ci.cancel();
                    return;
                }

                // Если дошли до сюда, значит команда неполная
                XDokelyscys.showUsage();
                ci.cancel();
            }
        } catch (Exception e) {
            System.out.println("XDokelyscys: Ошибка при обработке команды: " + e.getMessage());
            e.printStackTrace();
        }
    }
}