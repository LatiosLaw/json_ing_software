package analizador;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.Map.Entry;

public class Analizador {

    // Rutas fijas (ajustar según tu entorno)
    private static final String LOG_FILE_PATH = "C:/Users/Law/Downloads/simulator.json.log";
    private static final String CONFIG_FILE_PATH = "C:/Users/Law/Downloads/simulation_config.json";
    private static final String HTTP_LOG_FILE_PATH = "C:/Users/Law/Downloads/simulator-access.json.log";

    private static final double RANGE = 0.5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class RoomStats {
        double expectedTemp;
        double energyKWh;
        List<Double> temps = new ArrayList<>();
        int heaterOnCount = 0;
        double lastEnergyWh = 0;
        double minTemp = Double.POSITIVE_INFINITY;
        double maxTemp = Double.NEGATIVE_INFINITY;
        int userInteractions = 0;
        int lowTicks = 0;
        int highTicks = 0;
        double lastLowKWh = 0;
        double lastHighKWh = 0;
        double totalLowKWh = 0;
        double totalHighKWh = 0;
    }


    public static void main(String[] args) {
        Path logPath = Paths.get(LOG_FILE_PATH);
        Path configPath = Paths.get(CONFIG_FILE_PATH);
        Path httpPath = Paths.get(HTTP_LOG_FILE_PATH);

        if (!Files.exists(logPath)) {
            System.err.println("Archivo de log no encontrado: " + logPath.toAbsolutePath());
            return;
        }
        if (!Files.exists(configPath)) {
            System.err.println("Archivo de configuración no encontrado: " + configPath.toAbsolutePath());
            return;
        }

        Map<Integer, RoomStats> statsByRoom = new HashMap<>();
        double maxEnergyKWh = 0;

        // ===============================================================
        // 1️ Leer configuración
        // ===============================================================
        try {
            JsonNode root = MAPPER.readTree(configPath.toFile());
            JsonNode units = root.path("units");
            if (units.isArray()) {
                for (JsonNode unit : units) {
                    JsonNode room = unit.path("room");
                    int id = room.path("id").asInt();

                    RoomStats st = new RoomStats();
                    st.expectedTemp = parseDouble(room.path("expectedTemp").asText("22"));
                    st.energyKWh = parseEnergy(room.path("energy").asText("2 kWh"));
                    statsByRoom.put(id, st);
                }
            }

            String maxE = root.path("simulacion").path("maxEnergy").asText("0");
            maxEnergyKWh = parseEnergy(maxE);

            System.out.println("✅ Configuración cargada correctamente:");
            statsByRoom.forEach((id, st) -> System.out.printf(
                    "  - Room %d → expectedTemp=%.1f°C%n", id, st.expectedTemp
            ));
            System.out.printf("  - Energía máxima total del sitio: %.2f kWh%n%n", maxEnergyKWh);

        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // ===============================================================
        // 2️ Procesar log del simulador
        // ===============================================================
        int totalLines = 0, validJson = 0;
        Map<Long, Set<Integer>> roomsOnAtTime = new HashMap<>();
        long minSimTime = Long.MAX_VALUE;
        long maxSimTime = Long.MIN_VALUE;

        try (BufferedReader br = Files.newBufferedReader(logPath)) {
            String line;
            while ((line = br.readLine()) != null) {
                totalLines++;
                if (line.contains("\"logger\":\"STDOUT\"")) continue;

                int start = line.lastIndexOf('{');
                if (start < 0) continue;

                try {
                    String jsonPart = line.substring(start);
                    JsonNode root = MAPPER.readTree(jsonPart);

                    if (root.has("T_C") && root.has("roomId")) {
                        int roomId = root.get("roomId").asInt();
                        RoomStats st = statsByRoom.computeIfAbsent(roomId, k -> new RoomStats());

                        double temp = root.get("T_C").asDouble();
                        st.temps.add(temp);
                        if (temp < st.minTemp) st.minTemp = temp;
                        if (temp > st.maxTemp) st.maxTemp = temp;

                        boolean heaterOn = root.has("heaterOn") && root.get("heaterOn").asBoolean();
                        if (heaterOn) st.heaterOnCount++;

                        if (root.has("energy_Wh"))
                            st.lastEnergyWh = root.get("energy_Wh").asDouble();

                        // ⭐ NUEVO: Contar ticks de tarifa baja y alta
                        double low = root.has("lowKWh") ? root.get("lowKWh").asDouble() : st.lastLowKWh;
                        double high = root.has("highKWh") ? root.get("highKWh").asDouble() : st.lastHighKWh;

                        if (heaterOn) {
                            if (low > st.lastLowKWh) st.lowTicks++;
                            if (high > st.lastHighKWh) st.highTicks++;
                        }

                        st.lastLowKWh = low;
                        st.lastHighKWh = high;
                        
                        st.totalLowKWh = low;
                        st.totalHighKWh = high;


                        if (root.has("simTimeMs")) {
                            long simTime = root.get("simTimeMs").asLong();
                            if (simTime < minSimTime) minSimTime = simTime;
                            if (simTime > maxSimTime) maxSimTime = simTime;

                            Set<Integer> activeRooms = roomsOnAtTime.computeIfAbsent(simTime, k -> new HashSet<>());
                            if (heaterOn) activeRooms.add(roomId);
                        }

                        validJson++;
                    }
                } catch (Exception ignore) { }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // ===============================================================
        // 3️⃣ Procesar log HTTP (interacciones del usuario)
        // ===============================================================
        if (Files.exists(httpPath)) {
            System.out.println("📡 Analizando interacciones HTTP...");
            try (BufferedReader br = Files.newBufferedReader(httpPath)) {
                Set<String> uniqueRequests = new HashSet<>();

                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.contains("\"method\":\"POST\"")) continue;
                    if (!line.contains("\"path\":\"/switch/")) continue;
                    if (!line.contains("\"status\":200")) continue;

                    try {
                        JsonNode root = MAPPER.readTree(line);
                        String path = root.path("path").asText("");
                        String timestamp = root.path("@timestamp").asText("");
                        if (path.startsWith("/switch/") && !timestamp.isEmpty()) {
                            String key = path + "|" + timestamp;
                            if (uniqueRequests.add(key)) {
                                String[] parts = path.split("/");
                                int roomId = Integer.parseInt(parts[2]);
                                RoomStats st = statsByRoom.computeIfAbsent(roomId, k -> new RoomStats());
                                st.userInteractions++;
                            }
                        }
                    } catch (Exception ignore) { }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("⚠️ Archivo HTTP log no encontrado, se omite análisis de POST.");
        }

        // ===============================================================
        // 4️⃣ Calcular pico de consumo simultáneo
        // ===============================================================
        double peakKWh = 0.0;
        for (Set<Integer> activeRooms : roomsOnAtTime.values()) {
            double total = 0.0;
            for (int roomId : activeRooms) {
                RoomStats st = statsByRoom.get(roomId);
                if (st != null) total += st.energyKWh;
            }
            if (total > peakKWh) peakKWh = total;
        }

        // ===============================================================
        // 5️⃣ Resultados
        // ===============================================================
        System.out.printf("%n📊 Resumen del análisis:%n");
        System.out.printf("Total líneas procesadas: %d | JSON válidos: %d%n", totalLines, validJson);

        if (minSimTime != Long.MAX_VALUE && maxSimTime != Long.MIN_VALUE) {
            long durationMs = maxSimTime - minSimTime;
            double durationSec = durationMs / 1000.0;
            long hours = (long) (durationSec / 3600);
            long minutes = (long) ((durationSec % 3600) / 60);
            long seconds = (long) (durationSec % 60);
            System.out.printf("🕒 Duración simulada: %.1f s (%02dh %02dm %02ds)%n",
                    durationSec, hours, minutes, seconds);
        }

        System.out.printf("🔺 Pico máximo de consumo: %.2f kWh (%.2f / %.2f → %.1f%% del total)%n%n",
                peakKWh, peakKWh, maxEnergyKWh,
                (maxEnergyKWh > 0 ? (peakKWh / maxEnergyKWh * 100.0) : 0));

        // ===============================================================
        // 6️⃣ Reporte por room
        // ===============================================================
        for (Entry<Integer, RoomStats> entry : statsByRoom.entrySet()) {
            int roomId = entry.getKey();
            RoomStats st = entry.getValue();
            long total = st.temps.size();
            if (total == 0) continue;

            long within = st.temps.stream().filter(t -> Math.abs(t - st.expectedTemp) <= RANGE).count();
            long below  = st.temps.stream().filter(t -> t < st.expectedTemp - RANGE).count();
            long above  = st.temps.stream().filter(t -> t > st.expectedTemp + RANGE).count();

            double pctWithin = (within * 100.0 / total);
            double pctBelow  = (below * 100.0 / total);
            double pctAbove  = (above * 100.0 / total);
            double pctHeaterOn = (st.heaterOnCount * 100.0 / total);
            double energyKWh = st.lastEnergyWh / 1000.0;

            long tariffTicks = st.lowTicks + st.highTicks;
            double pctLow = tariffTicks > 0 ? (st.lowTicks * 100.0 / tariffTicks) : 0;
            double pctHigh = tariffTicks > 0 ? (st.highTicks * 100.0 / tariffTicks) : 0;

            System.out.printf(
                    "Room %d:%n" +
                    "  - Esperada: %.1f°C%n" +
                    "  - Temperatura: min=%.2f°C / max=%.2f°C%n" +
                    "  - Dentro del rango esperado: %.1f%% | Debajo: %.1f%% | Encima: %.1f%%%n" +
                    "  - Heater encendido: %.1f%%%n" +
                    "  - Energía acumulada: %.3f kWh%n" +
                    "  - Interacciones de usuario (POST): %d%n" +
                    "  - Tarifa baja: %.1f%% (%d ticks)%n" +
                    "  - Tarifa alta: %.1f%% (%d ticks)%n" +
                    "  - Consumo total en tarifa baja: %.3f kWh%n" +
                    "  - Consumo total en tarifa alta: %.3f kWh%n%n",
                    roomId, st.expectedTemp, st.minTemp, st.maxTemp,
                    pctWithin, pctBelow, pctAbove,
                    pctHeaterOn, energyKWh,
                    st.userInteractions,
                    pctLow, st.lowTicks,
                    pctHigh, st.highTicks,
                    st.totalLowKWh,
                    st.totalHighKWh
            );

        }
    }

    // ===============================================================
    // 🔧 Helpers
    // ===============================================================
    private static double parseDouble(String text) {
        try { return Double.parseDouble(text.replace(",", ".").trim()); }
        catch (Exception e) { return 0.0; }
    }

    private static double parseEnergy(String energyText) {
        if (energyText == null) return 0;
        String clean = energyText.trim().toLowerCase().replace(",", ".");
        if (clean.endsWith("kwh")) return Double.parseDouble(clean.replace("kwh", "").trim());
        if (clean.endsWith("wh")) return Double.parseDouble(clean.replace("wh", "").trim()) / 1000.0;
        return Double.parseDouble(clean);
    }
}
