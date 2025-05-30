package porn.tokenparsa.dick.service;

import porn.tokenparsa.dick.model.PriceUpdate;
import porn.tokenparsa.dick.model.Subscription;
import porn.tokenparsa.dick.model.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.net.URI;
import java.util.*;

public class GmgnWebSocketClient extends WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(GmgnWebSocketClient.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final TokenService tokenService;
    private final String network;
    private final String token;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private long lastPriceUpdate = System.currentTimeMillis();

    public GmgnWebSocketClient(SimpMessagingTemplate messagingTemplate, TokenService tokenService, String network, String token) {
        super(URI.create("wss://ws.gmgn.mobi/quotation"));
        this.messagingTemplate = messagingTemplate;
        this.tokenService = tokenService;
        this.network = network;
        this.token = token;
        addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36");
        addHeader("Origin", "https://gmgn.mobi");
        addHeader("Host", "ws.gmgn.mobi");
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("GMGN WebSocket connected for {}/{}", network, token);
        List<Map<String, Object>> messages = List.of(
                Map.of("action", "subscribe", "channel", "token_stat", "id", UUID.randomUUID().toString(), "data", List.of(Map.of("chain", network, "addresses", token))),
                Map.of("action", "subscribe", "channel", "token_activity", "id", UUID.randomUUID().toString(), "data", List.of(Map.of("chain", network, "addresses", token)))
        );
        for (Map<String, Object> msg : messages) {
            try {
                send(objectMapper.writeValueAsString(msg));
            } catch (Exception e) {
                logger.error("Error sending subscription message: {}", e.getMessage());
            }
        }
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode data = objectMapper.readTree(message);
            String channel = data.get("channel").asText();
            if ("token_stat".equals(channel)) {
                for (JsonNode stat : data.get("data")) {
                    String tokenAddr = stat.get("a").asText();
                    double price = stat.get("p").asDouble();
                    if (price > 0 && tokenAddr.equals(token)) {
                        tokenService.updateLastPrice(tokenAddr, price);
                        messagingTemplate.convertAndSend("/topic/price_update", new PriceUpdate(tokenAddr, price, 0));
                        lastPriceUpdate = System.currentTimeMillis();
                        logger.info("Last price updated for {}: ${}", tokenAddr, price);
                    }
                }
            } else if ("token_activity".equals(channel)) {
                Map<String, Map<String, Object>> transactionsBySecond = new HashMap<>();
                for (JsonNode activity : data.get("data")) {
                    String tokenAddr = activity.get("a").asText();
                    String eventType = activity.get("e").asText();
                    if (!List.of("buy", "sell").contains(eventType) || !tokenAddr.equals(token)) {
                        continue;
                    }
                    double volume = activity.get("au").asDouble();
                    long timestamp = activity.get("t").asLong(System.currentTimeMillis() / 1000);
                    Subscription sub = tokenService.getSubscription(tokenAddr);
                    if (sub == null) {
                        logger.warn("Subscription not found for token: {}", tokenAddr);
                        continue;
                    }

                    String secondKey = timestamp + "_" + eventType + "_" + tokenAddr;
                    Map<String, Object> transData = transactionsBySecond.computeIfAbsent(secondKey, k -> new HashMap<>());
                    List<Double> volumes = (List<Double>) transData.computeIfAbsent("volumes", k -> new ArrayList<Double>());
                    volumes.add(volume);
                    transData.put("token", tokenAddr);
                    transData.put("event_type", eventType);
                    transData.put("timestamp", timestamp);
                    transData.put("sub", sub);
                }

                for (Map<String, Object> transData : transactionsBySecond.values()) {
                    String tokenAddr = (String) transData.get("token");
                    String eventType = (String) transData.get("event_type");
                    long timestamp = (Long) transData.get("timestamp");
                    List<Double> volumes = (List<Double>) transData.get("volumes");
                    Subscription sub = (Subscription) transData.get("sub");

                    double totalVolume = volumes.stream().mapToDouble(Double::doubleValue).sum();
                    String uniqueId = timestamp + "_" + totalVolume + "_" + eventType + "_" + tokenAddr;

                    if (tokenService.addProcessedTransaction(uniqueId)) {
                        String status = eventType.equals("buy") ? "Buy" : "Sell";
                        String formattedTime = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date(timestamp * 1000));
                        String transText = volumes.size() > 1
                                ? String.format("[%s] %s $%,.0f (%d combined)", formattedTime, status, totalVolume, volumes.size())
                                : String.format("[%s] %s $%,.0f", formattedTime, status, totalVolume);
                        logger.info("Adding transaction: {}", transText);

                        double currentNotifyThreshold = sub.getNotifyThreshold();
                        boolean isNotification = totalVolume >= currentNotifyThreshold;
                        logger.info("Transaction volume: {}, notifyThreshold: {}, isNotification: {}", totalVolume, currentNotifyThreshold, isNotification);
                        Transaction transaction = new Transaction(tokenAddr, transText, uniqueId, isNotification);
                        transaction.setText(transaction.getText() + (isNotification ? " notification" : "") + " " + eventType.toLowerCase());
                        messagingTemplate.convertAndSend("/topic/new_transaction", transaction);
                        double initialPrice = tokenService.getMexcPrice(sub.getMexcName()) != 0
                                ? tokenService.getMexcPrice(sub.getMexcName())
                                : tokenService.getLastPrice(tokenAddr);
                        tokenService.startTracking(sub.getMexcName(), tokenAddr, initialPrice, uniqueId, transText);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error processing GMGN message: {}", e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.warn("GMGN WebSocket closed for {}/{}: {} {}", network, token, code, reason);
        tokenService.checkInactiveConnections(network + "_" + token);
    }

    @Override
    public void onError(Exception ex) {
        logger.error("GMGN WebSocket error for {}/{}: {}", network, token, ex.getMessage());
    }

    public long getLastPriceUpdate() {
        return lastPriceUpdate;
    }
}