package porn.tokenparsa.dick.service;

import porn.tokenparsa.dick.model.PriceUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.net.URI;
import java.util.Map;

public class MexcWebSocketClient extends WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(MexcWebSocketClient.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final TokenService tokenService;
    private final String symbol;
    private final String token;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private long lastPriceUpdate = System.currentTimeMillis();

    public MexcWebSocketClient(SimpMessagingTemplate messagingTemplate, TokenService tokenService, String symbol, String token) {
        super(URI.create("wss://contract.mexc.com/edge"));
        this.messagingTemplate = messagingTemplate;
        this.tokenService = tokenService;
        this.symbol = symbol;
        this.token = token;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("MEXC WebSocket connected for {}", symbol);
        try {
            send(objectMapper.writeValueAsString(Map.of("method", "sub.kline", "param", Map.of("symbol", symbol, "interval", "Min1"))));
            new Thread(() -> {
                while (isOpen()) {
                    try {
                        send(objectMapper.writeValueAsString(Map.of("method", "ping")));
                        Thread.sleep(5000);
                    } catch (Exception e) {
                        logger.error("Error sending MEXC ping: {}", e.getMessage());
                    }
                }
            }).start();
        } catch (Exception e) {
            logger.error("Error subscribing to MEXC: {}", e.getMessage());
        }
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode data = objectMapper.readTree(message);
            if ("push.kline".equals(data.get("channel").asText()) && data.has("data")) {
                double price = data.get("data").get("c").asDouble();
                if (price > 0) {
                    tokenService.updateMexcPrice(symbol, price);
                    messagingTemplate.convertAndSend("/topic/price_update", new PriceUpdate(token, 0, price));
                    lastPriceUpdate = System.currentTimeMillis();
                    logger.info("MEXC price updated for {}: ${}", symbol, price);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing MEXC message: {}", e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.warn("MEXC WebSocket closed for {}: {} {}", symbol, code, reason);
        tokenService.checkInactiveConnections(symbol);
    }

    @Override
    public void onError(Exception ex) {
        logger.error("MEXC WebSocket error for {}: {}", symbol, ex.getMessage());
    }

    public long getLastPriceUpdate() {
        return lastPriceUpdate;
    }
}