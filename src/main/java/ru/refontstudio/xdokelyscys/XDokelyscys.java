package ru.refontstudio.xdokelyscys;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.LiteralText;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Environment(EnvType.CLIENT)
public class XDokelyscys implements ClientModInitializer {
    // Статические поля для хранения состояния автоходьбы
    public static String currentDirection = "";
    public static int remainingTicks = 0;
    public static boolean isLooped = false;
    public static boolean isActive = false;
    public static int originalDuration = 0;

    // Константы
    public static final String[] VALID_DIRECTIONS = {"forward", "back", "left", "right"};
    public static final int DEFAULT_LOOP_SECONDS = 3600; // 1 час по умолчанию для бесконечного цикла
    public static final float CAMERA_SMOOTHING = 0.15f; // Коэффициент плавности поворота камеры (0.1 - 0.3)

    // Для записи скриптов
    public static boolean isRecording = false;
    private static String currentScriptName = null;
    private static List<InputAction> recordedActions = new ArrayList<>();
    private static List<ChatCommandAction> recordedChatCommands = new ArrayList<>();
    private static List<SlotSelectAction> recordedSlotSelections = new ArrayList<>();
    private static long recordStartTime = 0;
    private static Map<String, ScriptData> savedScripts = new HashMap<>();
    private static int lastSelectedSlot = -1; // Для отслеживания изменений слота

    // Для воспроизведения скриптов
    private static boolean isPlayingScript = false;
    private static boolean isLoopingScript = false; // Новое поле для зацикливания скрипта
    private static List<InputAction> currentScript = null;
    private static List<ChatCommandAction> currentChatCommands = null;
    private static List<SlotSelectAction> currentSlotSelections = null;
    private static int currentActionIndex = 0;
    private static int currentChatCommandIndex = 0;
    private static int currentSlotSelectionIndex = 0;
    private static long playbackStartTime = 0;
    private static long nextActionTime = 0;

    // Для плавности поворотов камеры
    private static float targetYaw = 0;
    private static float targetPitch = 0;
    private static boolean hasTargetRotation = false;

    @Override
    public void onInitializeClient() {
        System.out.println("XDokelyscys Mod initialized - готов перехватывать команды .autowalk");
        // Создаем папку для скриптов, если её нет
        File scriptDir = new File(MinecraftClient.getInstance().runDirectory, "xdokelyscys-scripts");
        if (!scriptDir.exists()) {
            scriptDir.mkdirs();
        }
    }

    /**
     * Этот метод вызывается из ClientTickMixin в конце каждого тика
     */
    public static void onClientTick(MinecraftClient client) {
        try {
            // Проверяем, есть ли клиент и его настройки
            if (client == null || client.options == null || client.player == null) {
                return;
            }

            // Обработка записи скрипта
            if (isRecording) {
                // Запоминаем текущее состояние кнопок
                boolean forward = client.options.keyForward.isPressed();
                boolean back = client.options.keyBack.isPressed();
                boolean left = client.options.keyLeft.isPressed();
                boolean right = client.options.keyRight.isPressed();
                boolean jump = client.options.keyJump.isPressed();
                boolean sprint = client.options.keySprint.isPressed();
                boolean sneak = client.options.keySneak.isPressed();

                // Запоминаем текущий поворот камеры
                float yaw = client.player.yaw;
                float pitch = client.player.pitch;

                // Проверяем, изменился ли выбранный слот
                int currentSlot = client.player.inventory.selectedSlot;
                if (lastSelectedSlot != -1 && lastSelectedSlot != currentSlot) {
                    recordSlotSelection(currentSlot);
                }
                lastSelectedSlot = currentSlot;

                // Проверяем, изменилось ли состояние с последней записи
                if (recordedActions.isEmpty() || hasStateChanged(recordedActions.get(recordedActions.size() - 1),
                        forward, back, left, right, jump, sprint, sneak, yaw, pitch)) {
                    long currentTime = System.currentTimeMillis();
                    long timeSinceStart = currentTime - recordStartTime;

                    InputAction action = new InputAction(
                            timeSinceStart,
                            forward,
                            back,
                            left,
                            right,
                            jump,
                            sprint,
                            sneak,
                            yaw,
                            pitch
                    );

                    recordedActions.add(action);
                    System.out.println("Записано действие: " + action);
                }
            }

            // Обработка воспроизведения скрипта
            if (isPlayingScript && currentScript != null && currentActionIndex < currentScript.size()) {
                long currentTime = System.currentTimeMillis();
                InputAction currentAction = currentScript.get(currentActionIndex);

                if (currentTime >= nextActionTime) {
                    // Применяем состояние клавиш из скрипта
                    client.options.keyForward.setPressed(currentAction.forward);
                    client.options.keyBack.setPressed(currentAction.back);
                    client.options.keyLeft.setPressed(currentAction.left);
                    client.options.keyRight.setPressed(currentAction.right);
                    client.options.keyJump.setPressed(currentAction.jump);
                    client.options.keySprint.setPressed(currentAction.sprint);
                    client.options.keySneak.setPressed(currentAction.sneak);

                    // Устанавливаем целевой поворот камеры для плавной интерполяции
                    targetYaw = currentAction.yaw;
                    targetPitch = currentAction.pitch;
                    hasTargetRotation = true;

                    currentActionIndex++;

                    // Если есть следующее действие, вычисляем время для него
                    if (currentActionIndex < currentScript.size()) {
                        InputAction nextAction = currentScript.get(currentActionIndex);
                        nextActionTime = playbackStartTime + nextAction.timestamp;
                    } else if (isLoopingScript) {
                        // Перезапускаем скрипт, если включено зацикливание
                        currentActionIndex = 0;
                        currentChatCommandIndex = 0;
                        currentSlotSelectionIndex = 0;
                        playbackStartTime = System.currentTimeMillis();
                        if (currentScript.size() > 0) {
                            nextActionTime = playbackStartTime + currentScript.get(0).timestamp;
                        }
                        client.player.sendMessage(new LiteralText("§a[AutoWalk] §fСкрипт перезапущен (цикл)"), false);
                    } else {
                        // Это было последнее действие
                        stopPlayback();
                    }
                }

                // Плавная интерполяция поворота камеры
                if (hasTargetRotation) {
                    // Для yaw нужна специальная обработка, т.к. он может переходить через 360 градусов
                    float yawDiff = normalizeAngle(targetYaw - client.player.yaw);
                    client.player.yaw += yawDiff * CAMERA_SMOOTHING;

                    // Для pitch обычная интерполяция
                    float pitchDiff = targetPitch - client.player.pitch;
                    client.player.pitch += pitchDiff * CAMERA_SMOOTHING;

                    // Если мы достаточно близко к целевым значениям, можно считать, что достигли их
                    if (Math.abs(yawDiff) < 0.1f && Math.abs(pitchDiff) < 0.1f) {
                        client.player.yaw = targetYaw;
                        client.player.pitch = targetPitch;
                    }
                }

                // Проверяем и выполняем команды чата
                checkAndExecuteChatCommands();

                // Проверяем и выполняем переключения слотов
                checkAndExecuteSlotSelections();
            }

            // Если автоходьба активна и не воспроизводится скрипт
            if (isActive && !isPlayingScript) {
                // Если время вышло
                if (remainingTicks <= 0) {
                    if (isLooped) {
                        // Сбрасываем счетчик для зацикленного движения
                        remainingTicks = originalDuration;
                        if (client.player != null) {
                            client.player.sendMessage(new LiteralText("§a[AutoWalk] §fДвижение " + currentDirection + " перезапущено"), false);
                        }
                    } else {
                        // Останавливаем движение
                        stopMovement();
                    }
                    return;
                }

                // Уменьшаем счетчик
                remainingTicks--;

                // Сначала отпускаем все клавиши движения
                client.options.keyForward.setPressed(false);
                client.options.keyBack.setPressed(false);
                client.options.keyLeft.setPressed(false);
                client.options.keyRight.setPressed(false);

                // Нажимаем нужную клавишу в зависимости от направления
                switch (currentDirection) {
                    case "forward":
                        client.options.keyForward.setPressed(true);
                        break;
                    case "back":
                        client.options.keyBack.setPressed(true);
                        break;
                    case "left":
                        client.options.keyLeft.setPressed(true);
                        break;
                    case "right":
                        client.options.keyRight.setPressed(true);
                        break;
                }
            }
        } catch (Exception e) {
            System.out.println("XDokelyscys: Ошибка при обновлении движения: " + e.getMessage());
            e.printStackTrace();
            isActive = false;

            // В случае ошибки отпускаем все клавиши
            releaseAllKeys(client);
        }
    }

    /**
     * Проверяет и выполняет команды чата в нужное время
     */
    private static void checkAndExecuteChatCommands() {
        if (!isPlayingScript || currentChatCommands == null || currentChatCommandIndex >= currentChatCommands.size()) {
            return;
        }

        long currentTime = System.currentTimeMillis() - playbackStartTime;

        while (currentChatCommandIndex < currentChatCommands.size()) {
            ChatCommandAction command = currentChatCommands.get(currentChatCommandIndex);

            // Если время команды наступило
            if (currentTime >= command.timestamp) {
                // Выполняем команду
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.player != null) {
                    client.player.sendChatMessage(command.command);
                    System.out.println("XDokelyscys: Выполнена команда чата: " + command.command);
                }

                // Переходим к следующей команде
                currentChatCommandIndex++;
            } else {
                // Если время следующей команды ещё не наступило, выходим из цикла
                break;
            }
        }

        // Если скрипт зациклен и все команды выполнены, перезапускаем
        if (isLoopingScript && currentChatCommandIndex >= currentChatCommands.size() &&
                currentActionIndex >= currentScript.size() &&
                currentSlotSelectionIndex >= currentSlotSelections.size()) {
            currentChatCommandIndex = 0;
        }
    }

    /**
     * Проверяет и выполняет переключение слотов в нужное время
     */
    private static void checkAndExecuteSlotSelections() {
        if (!isPlayingScript || currentSlotSelections == null || currentSlotSelectionIndex >= currentSlotSelections.size()) {
            return;
        }

        long currentTime = System.currentTimeMillis() - playbackStartTime;

        while (currentSlotSelectionIndex < currentSlotSelections.size()) {
            SlotSelectAction slotAction = currentSlotSelections.get(currentSlotSelectionIndex);

            // Если время действия наступило
            if (currentTime >= slotAction.timestamp) {
                // Выполняем переключение слота
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.player != null) {
                    client.player.inventory.selectedSlot = slotAction.slotNumber;
                    System.out.println("XDokelyscys: Выбран слот: " + slotAction.slotNumber);
                }

                // Переходим к следующему действию
                currentSlotSelectionIndex++;
            } else {
                // Если время следующего действия ещё не наступило, выходим из цикла
                break;
            }
        }

        // Если скрипт зациклен и все действия выполнены, перезапускаем
        if (isLoopingScript && currentSlotSelectionIndex >= currentSlotSelections.size() &&
                currentActionIndex >= currentScript.size() &&
                currentChatCommandIndex >= currentChatCommands.size()) {
            currentSlotSelectionIndex = 0;
        }
    }

    /**
     * Записывает выбор слота при записи скрипта
     */
    public static void recordSlotSelection(int slotNumber) {
        if (!isRecording) return;

        long currentTime = System.currentTimeMillis();
        long timeSinceStart = currentTime - recordStartTime;

        SlotSelectAction action = new SlotSelectAction(timeSinceStart, slotNumber);
        recordedSlotSelections.add(action);
        System.out.println("Записано переключение слота: " + action);
    }

    /**
     * Нормализует угол для плавного перехода через 360 градусов
     */
    private static float normalizeAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    /**
     * Проверяет, изменилось ли состояние клавиш или поворот камеры
     */
    private static boolean hasStateChanged(InputAction lastAction,
                                           boolean forward, boolean back, boolean left, boolean right,
                                           boolean jump, boolean sprint, boolean sneak,
                                           float yaw, float pitch) {
        return lastAction.forward != forward ||
                lastAction.back != back ||
                lastAction.left != left ||
                lastAction.right != right ||
                lastAction.jump != jump ||
                lastAction.sprint != sprint ||
                lastAction.sneak != sneak ||
                Math.abs(normalizeAngle(lastAction.yaw - yaw)) > 0.5f ||  // Немного увеличен порог для записи
                Math.abs(lastAction.pitch - pitch) > 0.5f;
    }

    /**
     * Отпускает все клавиши
     */
    private static void releaseAllKeys(MinecraftClient client) {
        if (client != null && client.options != null) {
            client.options.keyForward.setPressed(false);
            client.options.keyBack.setPressed(false);
            client.options.keyLeft.setPressed(false);
            client.options.keyRight.setPressed(false);
            client.options.keyJump.setPressed(false);
            client.options.keySprint.setPressed(false);
            client.options.keySneak.setPressed(false);
        }
    }

    /**
     * Запускает движение игрока
     */
    public static void startMovement(String direction, int seconds, boolean loop) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        // Останавливаем предыдущее движение
        if (isActive) {
            releaseAllKeys(client);
        }

        // Устанавливаем новые параметры
        currentDirection = direction;
        remainingTicks = seconds * 20; // 20 тиков в секунду
        originalDuration = seconds * 20; // сохраняем оригинальную длительность
        isLooped = loop;
        isActive = true;

        // Отправляем сообщение
        String message;
        if (loop && seconds == DEFAULT_LOOP_SECONDS) {
            message = String.format("§a[AutoWalk] §fНачато движение %s в цикле", direction);
        } else if (loop) {
            message = String.format("§a[AutoWalk] §fНачато движение %s на %d секунд (зациклено)", direction, seconds);
        } else {
            message = String.format("§a[AutoWalk] §fНачато движение %s на %d секунд", direction, seconds);
        }

        client.player.sendMessage(new LiteralText(message), false);

        // Выводим отладочную информацию
        System.out.println("XDokelyscys: Запущено движение " + direction + " на " + seconds + " секунд, зацикленность: " + loop);
    }

    /**
     * Останавливает движение игрока
     */
    public static void stopMovement() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        isActive = false;
        currentDirection = "";
        remainingTicks = 0;
        originalDuration = 0;
        isLooped = false;

        // Отпускаем все клавиши движения
        releaseAllKeys(client);

        // Отправляем сообщение
        if (client.player != null) {
            client.player.sendMessage(new LiteralText("§a[AutoWalk] §fДвижение остановлено"), false);
        }
        System.out.println("XDokelyscys: Движение остановлено");
    }

    /**
     * Записывает команду чата при записи скрипта
     */
    public static void recordChatCommand(String message) {
        if (!isRecording) return;

        long currentTime = System.currentTimeMillis();
        long timeSinceStart = currentTime - recordStartTime;

        ChatCommandAction action = new ChatCommandAction(timeSinceStart, message);
        recordedChatCommands.add(action);
        System.out.println("Записана команда чата: " + action);
    }

    /**
     * Начинает запись скрипта
     */
    public static void startRecording(String scriptName) {
        if (isRecording) {
            stopRecording(false);
        }

        isRecording = true;
        currentScriptName = scriptName;
        recordedActions.clear();
        recordedChatCommands.clear();
        recordedSlotSelections.clear();
        recordStartTime = System.currentTimeMillis();

        // Инициализируем текущий слот
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            lastSelectedSlot = client.player.inventory.selectedSlot;
        } else {
            lastSelectedSlot = -1;
        }

        if (client != null && client.player != null) {
            client.player.sendMessage(new LiteralText("§a[AutoWalk] §fНачата запись скрипта: " + scriptName), false);
        }
        System.out.println("XDokelyscys: Начата запись скрипта " + scriptName);
    }

    /**
     * Останавливает запись скрипта
     */
    public static void stopRecording(boolean save) {
        if (!isRecording) return;

        isRecording = false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            if (save && currentScriptName != null && !recordedActions.isEmpty()) {
                savedScripts.put(currentScriptName, new ScriptData(
                        new ArrayList<>(recordedActions),
                        new ArrayList<>(recordedChatCommands),
                        new ArrayList<>(recordedSlotSelections)
                ));

                client.player.sendMessage(new LiteralText("§a[AutoWalk] §fЗапись скрипта сохранена: " + currentScriptName), false);

                // Сохраняем скрипт в файл
                saveScriptToFile(currentScriptName);
            } else {
                client.player.sendMessage(new LiteralText("§a[AutoWalk] §fЗапись скрипта отменена"), false);
            }
        }

        currentScriptName = null;
        recordedActions.clear();
        recordedChatCommands.clear();
        recordedSlotSelections.clear();
        lastSelectedSlot = -1;
    }

    /**
     * Сохраняет скрипт в файл
     */
    private static void saveScriptToFile(String scriptName) {
        File scriptDir = new File(MinecraftClient.getInstance().runDirectory, "xdokelyscys-scripts");
        File scriptFile = new File(scriptDir, scriptName + ".script");

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(scriptFile))) {
            // Сохраняем действия
            oos.writeObject(recordedActions);

            // Сохраняем команды чата
            oos.writeObject(recordedChatCommands);

            // Сохраняем выборы слотов
            oos.writeObject(recordedSlotSelections);

            System.out.println("XDokelyscys: Скрипт сохранен в файл: " + scriptFile.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("XDokelyscys: Ошибка при сохранении скрипта: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Загружает скрипт из файла
     */
    @SuppressWarnings("unchecked")
    private static ScriptData loadScriptFromFile(String scriptName) {
        File scriptDir = new File(MinecraftClient.getInstance().runDirectory, "xdokelyscys-scripts");
        File scriptFile = new File(scriptDir, scriptName + ".script");

        if (!scriptFile.exists()) {
            System.out.println("XDokelyscys: Файл скрипта не найден: " + scriptFile.getAbsolutePath());
            return null;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(scriptFile))) {
            List<InputAction> actions = (List<InputAction>) ois.readObject();

            // Пытаемся загрузить команды чата (для обратной совместимости)
            List<ChatCommandAction> chatCommands = new ArrayList<>();
            try {
                chatCommands = (List<ChatCommandAction>) ois.readObject();
            } catch (Exception e) {
                // Если не удалось загрузить команды чата, оставляем пустой список
                System.out.println("XDokelyscys: Не удалось загрузить команды чата, возможно старый формат файла");
            }

            // Пытаемся загрузить выборы слотов (для обратной совместимости)
            List<SlotSelectAction> slotSelections = new ArrayList<>();
            try {
                slotSelections = (List<SlotSelectAction>) ois.readObject();
            } catch (Exception e) {
                // Если не удалось загрузить выборы слотов, оставляем пустой список
                System.out.println("XDokelyscys: Не удалось загрузить выборы слотов, возможно старый формат файла");
            }

            System.out.println("XDokelyscys: Скрипт загружен из файла: " + scriptFile.getAbsolutePath());
            return new ScriptData(actions, chatCommands, slotSelections);
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("XDokelyscys: Ошибка при загрузке скрипта: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Запускает воспроизведение скрипта
     */
    public static void playScript(String scriptName, boolean loop) {
        // Сначала пытаемся загрузить из оперативной памяти
        ScriptData scriptData = savedScripts.get(scriptName);

        // Если нет в памяти, пытаемся загрузить из файла
        if (scriptData == null) {
            scriptData = loadScriptFromFile(scriptName);
        }

        // Если всё равно не нашли
        if (scriptData == null || scriptData.actions.isEmpty()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                client.player.sendMessage(new LiteralText("§c[AutoWalk] §fСкрипт не найден: " + scriptName), false);
            }
            return;
        }

        // Останавливаем текущее движение
        if (isActive) {
            stopMovement();
        }

        // Останавливаем текущее воспроизведение, если оно активно
        if (isPlayingScript) {
            stopPlayback();
        }

        // Запускаем воспроизведение
        isPlayingScript = true;
        isLoopingScript = loop; // Устанавливаем режим зацикливания
        currentScript = scriptData.actions;
        currentChatCommands = scriptData.chatCommands;
        currentSlotSelections = scriptData.slotSelections;
        currentActionIndex = 0;
        currentChatCommandIndex = 0;
        currentSlotSelectionIndex = 0;
        playbackStartTime = System.currentTimeMillis();
        hasTargetRotation = false;

        // Устанавливаем время для первого действия
        if (!currentScript.isEmpty()) {
            nextActionTime = playbackStartTime + currentScript.get(0).timestamp;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            String message = loop
                    ? String.format("§a[AutoWalk] §fВоспроизведение скрипта в цикле: %s", scriptName)
                    : String.format("§a[AutoWalk] §fВоспроизведение скрипта: %s", scriptName);
            client.player.sendMessage(new LiteralText(message), false);
        }

        System.out.println("XDokelyscys: Запущено воспроизведение скрипта " + scriptName + (loop ? " (в цикле)" : ""));
    }

    /**
     * Останавливает воспроизведение скрипта
     */
    public static void stopPlayback() {
        if (!isPlayingScript) return;

        isPlayingScript = false;
        isLoopingScript = false;
        currentScript = null;
        currentChatCommands = null;
        currentSlotSelections = null;
        currentActionIndex = 0;
        currentChatCommandIndex = 0;
        currentSlotSelectionIndex = 0;
        hasTargetRotation = false;

        // Отпускаем все клавиши
        MinecraftClient client = MinecraftClient.getInstance();
        releaseAllKeys(client);

        if (client != null && client.player != null) {
            client.player.sendMessage(new LiteralText("§a[AutoWalk] §fВоспроизведение скрипта остановлено"), false);
        }
        System.out.println("XDokelyscys: Воспроизведение скрипта остановлено");
    }

    /**
     * Показывает список сохраненных скриптов
     */
    public static void listScripts() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        // Загружаем список файлов скриптов
        File scriptDir = new File(client.runDirectory, "xdokelyscys-scripts");
        File[] scriptFiles = scriptDir.listFiles((dir, name) -> name.endsWith(".script"));

        if ((savedScripts.isEmpty() || savedScripts.size() == 0) && (scriptFiles == null || scriptFiles.length == 0)) {
            client.player.sendMessage(new LiteralText("§c[AutoWalk] §fНет сохраненных скриптов"), false);
            return;
        }

        client.player.sendMessage(new LiteralText("§e--- Сохраненные скрипты ---"), false);

        // Выводим скрипты из оперативной памяти
        for (String name : savedScripts.keySet()) {
            ScriptData data = savedScripts.get(name);
            String chatInfo = data.chatCommands.isEmpty() ? "" : " (" + data.chatCommands.size() + " команд чата)";
            String slotInfo = data.slotSelections.isEmpty() ? "" : " (" + data.slotSelections.size() + " выборов слотов)";
            client.player.sendMessage(new LiteralText("§f- " + name + " (" + data.actions.size() + " действий)" + chatInfo + slotInfo), false);
        }

        // Выводим скрипты из файлов (если они не в памяти)
        if (scriptFiles != null) {
            for (File file : scriptFiles) {
                String name = file.getName().replace(".script", "");
                if (!savedScripts.containsKey(name)) {
                    client.player.sendMessage(new LiteralText("§f- " + name + " (на диске)"), false);
                }
            }
        }
    }

    /**
     * Показывает справку по использованию
     */
    public static void showUsage() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        client.player.sendMessage(new LiteralText("§e--- AutoWalk Использование ---"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk <seconds> <direction> - движение в указанном направлении"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk loop <direction> - бесконечное движение в указанном направлении"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk stop - остановить движение"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk record <name> - начать запись скрипта"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk stoprecord - сохранить запись скрипта"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk cancelrecord - отменить запись скрипта"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk play <name> - воспроизвести скрипт"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk loopplay <name> - воспроизвести скрипт в цикле"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk stopplay - остановить воспроизведение скрипта"), false);
        client.player.sendMessage(new LiteralText("§f.autowalk scripts - показать список скриптов"), false);
        client.player.sendMessage(new LiteralText("§fНаправления: forward, back, left, right"), false);
    }

    /**
     * Класс для хранения данных скрипта
     */
    public static class ScriptData {
        public final List<InputAction> actions;
        public final List<ChatCommandAction> chatCommands;
        public final List<SlotSelectAction> slotSelections;

        public ScriptData(List<InputAction> actions, List<ChatCommandAction> chatCommands, List<SlotSelectAction> slotSelections) {
            this.actions = actions;
            this.chatCommands = chatCommands;
            this.slotSelections = slotSelections;
        }
    }

    /**
     * Класс для хранения действий пользователя
     */
    public static class InputAction implements Serializable {
        private static final long serialVersionUID = 1L;

        public final long timestamp;
        public final boolean forward;
        public final boolean back;
        public final boolean left;
        public final boolean right;
        public final boolean jump;
        public final boolean sprint;
        public final boolean sneak;
        public final float yaw;    // Поворот по горизонтали (влево-вправо)
        public final float pitch;  // Поворот по вертикали (вверх-вниз)

        public InputAction(long timestamp, boolean forward, boolean back, boolean left,
                           boolean right, boolean jump, boolean sprint, boolean sneak,
                           float yaw, float pitch) {
            this.timestamp = timestamp;
            this.forward = forward;
            this.back = back;
            this.left = left;
            this.right = right;
            this.jump = jump;
            this.sprint = sprint;
            this.sneak = sneak;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        @Override
        public String toString() {
            return String.format("t:%d, f:%b, b:%b, l:%b, r:%b, j:%b, s:%b, sn:%b, yaw:%.2f, pitch:%.2f",
                    timestamp, forward, back, left, right, jump, sprint, sneak, yaw, pitch);
        }
    }

    /**
     * Класс для хранения команд чата
     */
    public static class ChatCommandAction implements Serializable {
        private static final long serialVersionUID = 2L;

        public final long timestamp;
        public final String command;

        public ChatCommandAction(long timestamp, String command) {
            this.timestamp = timestamp;
            this.command = command;
        }

        @Override
        public String toString() {
            return String.format("t:%d, cmd:%s", timestamp, command);
        }
    }

    /**
     * Класс для хранения выбора слота
     */
    public static class SlotSelectAction implements Serializable {
        private static final long serialVersionUID = 3L;

        public final long timestamp;
        public final int slotNumber; // 0-8 (соответствует слотам 1-9 в интерфейсе)

        public SlotSelectAction(long timestamp, int slotNumber) {
            this.timestamp = timestamp;
            this.slotNumber = slotNumber;
        }

        @Override
        public String toString() {
            return String.format("t:%d, slot:%d", timestamp, slotNumber);
        }
    }
}