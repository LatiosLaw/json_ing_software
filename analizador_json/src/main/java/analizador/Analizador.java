package analizador;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.Map.Entry;

public class Analizador {

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

    // ===============================================================
    // MAIN: recibe paths por parámetro
    // ===============================================================
    public static void main(String[] args) {

        if (args.length != 3) {
            System.err.println("Uso correcto:");
            System.err.println("java analizador.Analizador <sim-log> <config-json> <http-log>");
            return;
        }

        Path logPath = Paths.get(args[0]);
        Path configPath = Paths.get(args[1]);
        Path httpPath = Paths.get(args[2]);

        analizarLogs(logPath, configPath, httpPath);
    }

    // ===============================================================
    // Función principal de análisis
    // ===============================================================
    public static void analizarLogs(Path logPath, Path configPath, Path httpPath) {

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
        // 1) Leer configuración
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

            System.out.println("Configuración cargada correctamente:");
            statsByRoom.forEach((id, st) -> System.out.printf(
                    "  - Room %d - expectedTemp=%.1f°C%n", id, st.expectedTemp
            ));
            System.out.printf("  - Energía máxima total del sitio: %.2f kWh%n%n", maxEnergyKWh);

        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // ===============================================================
        // 2) Procesar log del simulador
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
                        st.minTemp = Math.min(st.minTemp, temp);
                        st.maxTemp = Math.max(st.maxTemp, temp);

                        boolean heaterOn = root.path("heaterOn").asBoolean(false);
                        if (heaterOn) st.heaterOnCount++;

                        st.lastEnergyWh = root.path("energy_Wh").asDouble(st.lastEnergyWh);

                        double low = root.path("lowKWh").asDouble(st.lastLowKWh);
                        double high = root.path("highKWh").asDouble(st.lastHighKWh);

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
                            minSimTime = Math.min(minSimTime, simTime);
                            maxSimTime = Math.max(maxSimTime, simTime);

                            Set<Integer> activeRooms =
                                    roomsOnAtTime.computeIfAbsent(simTime, k -> new HashSet<>());
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
        // 3) Procesar log HTTP
        // ===============================================================
        if (Files.exists(httpPath)) {
            System.out.println("Analizando interacciones HTTP...");
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

                        if (!path.startsWith("/switch/") || timestamp.isEmpty())
                            continue;

                        String key = path + "|" + timestamp;

                        if (uniqueRequests.add(key)) {
                            int roomId = Integer.parseInt(path.split("/")[2]);
                            statsByRoom.computeIfAbsent(roomId, k -> new RoomStats()).userInteractions++;
                        }

                    } catch (Exception ignore) {}
                }
            } catch (IOException e) { e.printStackTrace(); }
        } else {
            System.out.println("Archivo HTTP log no encontrado.");
        }

        // ===============================================================
        // 4) Pico de consumo simultáneo
        // ===============================================================
        double peakKWh = roomsOnAtTime.values().stream()
                .mapToDouble(activeRooms -> activeRooms.stream()
                        .mapToDouble(r -> statsByRoom.get(r).energyKWh)
                        .sum())
                .max()
                .orElse(0.0);

        // ===============================================================
        // 5) Reporte final
        // ===============================================================
        System.out.printf("%nResumen del análisis:%n");
        System.out.printf("Total líneas procesadas: %d | JSON válidos: %d%n", totalLines, validJson);

        if (minSimTime != Long.MAX_VALUE) {
            long durationMs = maxSimTime - minSimTime;
            System.out.printf("Duración simulada: %.1f s%n", durationMs / 1000.0);
        }

        System.out.printf("Pico máximo de consumo: %.2f kWh%n%n", peakKWh);

        for (Entry<Integer, RoomStats> entry : statsByRoom.entrySet()) {
            int roomId = entry.getKey();
            RoomStats st = entry.getValue();
            long total = st.temps.size();
            if (total == 0) continue;

            long tariffTicks = st.lowTicks + st.highTicks;

            System.out.printf(
                "Room %d:%n" +
                "  Temperatura: min=%.2f / max=%.2f%n" +
                "  Heater encendido: %.1f%%%n" +
                "  Interacciones usuario: %d%n" +
                "  Tarifa baja: %.1f%% (%d ticks)%n" +
                "  Tarifa alta: %.1f%% (%d ticks)%n" +
                "  Consumo baja: %.3f kWh%n" +
                "  Consumo alta: %.3f kWh%n%n",
                roomId,
                st.minTemp, st.maxTemp,
                (st.heaterOnCount * 100.0 / total),
                st.userInteractions,
                tariffTicks > 0 ? (st.lowTicks * 100.0 / tariffTicks) : 0, st.lowTicks,
                tariffTicks > 0 ? (st.highTicks * 100.0 / tariffTicks) : 0, st.highTicks,
                st.totalLowKWh, st.totalHighKWh
            );
        }
    }

    // Helpers
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