package porn.tokenparsa.dick.service;

import porn.tokenparsa.dick.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {
    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);
    public final SimpMessagingTemplate messagingTemplate;
    private final List<Subscription> subscriptions = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Double> lastPrices = new ConcurrentHashMap<>();
    private final Map<String, Double> mexcPrices = new ConcurrentHashMap<>();
    private final Set<String> processedTransactions = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, List<Map<String, Object>>> priceChangeTrackers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, GmgnWebSocketClient> gmgnClients = new ConcurrentHashMap<>();
    private final Map<String, MexcWebSocketClient> mexcClients = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUpdateTimes = new ConcurrentHashMap<>();

    public TokenService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        startPriceChangeTracker();
        startInactiveCheck();
    }

    public List<Subscription> getSubscriptions() {
        return new ArrayList<>(subscriptions);
    }

    public void addSubscription(Subscription subscription) {
        subscriptions.add(subscription);
        activeConnections.put(subscription.getTokenContractAddress(), true);
        String key = subscription.getNetwork() + "_" + subscription.getTokenContractAddress();
        if (!gmgnClients.containsKey(key)) {
            GmgnWebSocketClient client = new GmgnWebSocketClient(messagingTemplate, this, subscription.getNetwork(), subscription.getTokenContractAddress());
            gmgnClients.put(key, client);
            try {
                client.connect();
            } catch (Exception e) {
                logger.error("Failed to connect GMGN WebSocket for {}/{}: {}", subscription.getNetwork(), subscription.getTokenContractAddress(), e.getMessage());
            }
        }
        if (!mexcClients.containsKey(subscription.getMexcName())) {
            MexcWebSocketClient client = new MexcWebSocketClient(messagingTemplate, this, subscription.getMexcName(), subscription.getTokenContractAddress());
            mexcClients.put(subscription.getMexcName(), client);
            try {
                client.connect();
            } catch (Exception e) {
                logger.error("Failed to connect MEXC WebSocket for {}: {}", subscription.getMexcName(), e.getMessage());
            }
        }
        lastUpdateTimes.put(key, System.currentTimeMillis());
    }

    public void removeSubscription(String token) {
        subscriptions.removeIf(sub -> sub.getTokenContractAddress().equals(token));
        activeConnections.remove(token);
        String gmgnKey = subscriptions.stream()
                .filter(sub -> sub.getTokenContractAddress().equals(token))
                .findFirst()
                .map(sub -> sub.getNetwork() + "_" + token)
                .orElse(null);
        if (gmgnKey != null) {
            GmgnWebSocketClient gmgnClient = gmgnClients.remove(gmgnKey);
            if (gmgnClient != null) gmgnClient.close();
        }
        String mexcKey = subscriptions.stream()
                .filter(sub -> sub.getTokenContractAddress().equals(token))
                .findFirst()
                .map(Subscription::getMexcName)
                .orElse(null);
        if (mexcKey != null) {
            MexcWebSocketClient mexcClient = mexcClients.remove(mexcKey);
            if (mexcClient != null) mexcClient.close();
        }
        lastUpdateTimes.remove(token);
    }

    public void updateLastPrice(String token, double price) {
        lastPrices.put(token, price);
        lastUpdateTimes.put(token, System.currentTimeMillis());
    }

    public void updateMexcPrice(String symbol, double price) {
        mexcPrices.put(symbol, price);
        lastUpdateTimes.put(symbol, System.currentTimeMillis());
    }

    public double getLastPrice(String token) {
        return lastPrices.getOrDefault(token, 0.0);
    }

    public double getMexcPrice(String symbol) {
        return mexcPrices.getOrDefault(symbol, 0.0);
    }

    public boolean addProcessedTransaction(String uniqueId) {
        return processedTransactions.add(uniqueId);
    }

    public Subscription getSubscription(String token) {
        return subscriptions.stream()
                .filter(sub -> sub.getTokenContractAddress().equals(token))
                .findFirst()
                .orElse(null);
    }

    public void startTracking(String symbol, String token, double initialPrice, String transactionId, String baseTransactionText) {
        if (initialPrice == 0) {
            long timeout = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < timeout) {
                initialPrice = mexcPrices.getOrDefault(symbol, 0.0);
                if (initialPrice > 0) {
                    logger.info("Found MEXC initial price for {}: ${}", symbol, initialPrice);
                    break;
                }
                initialPrice = lastPrices.getOrDefault(token, 0.0);
                if (initialPrice > 0) {
                    logger.info("Using last price as initial for {}: ${}", symbol, initialPrice);
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (initialPrice == 0) {
            logger.warn("No price available for {}", baseTransactionText);
            return;
        }

        Map<String, Object> tracker = new HashMap<>();
        tracker.put("symbol", symbol);
        tracker.put("token_address", token);
        tracker.put("initial_price", initialPrice);
        tracker.put("transaction_id", transactionId);
        tracker.put("base_transaction_text", baseTransactionText);
        tracker.put("end_time", System.currentTimeMillis() + 20000);
        tracker.put("price_changes", new ArrayList<Double>());

        priceChangeTrackers.computeIfAbsent(symbol, k -> new ArrayList<>()).add(tracker);
        logger.info("Started tracking for {} with initial price ${}", baseTransactionText, initialPrice);
    }

    private void startPriceChangeTracker() {
        new Thread(() -> {
            while (true) {
                long currentTime = System.currentTimeMillis();
                for (String symbol : new ArrayList<>(priceChangeTrackers.keySet())) {
                    for (Iterator<Map<String, Object>> it = priceChangeTrackers.get(symbol).iterator(); it.hasNext(); ) {
                        Map<String, Object> tracker = it.next();
                        String transId = (String) tracker.get("transaction_id");
                        String token = (String) tracker.get("token_address");
                        double initialPrice = (Double) tracker.get("initial_price");
                        String baseText = (String) tracker.get("base_transaction_text");
                        long endTime = (Long) tracker.get("end_time");
                        List<Double> priceChanges = (List<Double>) tracker.get("price_changes");

                        if (currentTime >= endTime) {
                            String finalText;
                            if (!priceChanges.isEmpty()) {
                                double minChange = Collections.min(priceChanges);
                                double maxChange = Collections.max(priceChanges);
                                String minColor = minChange >= 0 ? "success" : "error";
                                String maxColor = maxChange >= 0 ? "success" : "error";
                                finalText = String.format("%s | 20s Range: <span class='text-%s'>%+.2f%%</span> to <span class='text-%s'>%+.2f%%</span>",
                                        baseText, minColor, minChange, maxColor, maxChange);
                            } else {
                                finalText = baseText + " | No price changes in 20s";
                            }
                            messagingTemplate.convertAndSend("/topic/update_transaction", Map.of("id", transId, "text", finalText));
                            it.remove();
                            if (priceChangeTrackers.get(symbol).isEmpty()) {
                                priceChangeTrackers.remove(symbol);
                            }
                            continue;
                        }

                        double currentPrice = mexcPrices.getOrDefault(symbol, 0.0);
                        if (currentPrice == 0) {
                            currentPrice = lastPrices.getOrDefault(token, 0.0);
                            logger.debug("Fallback to last price for {}: ${}", symbol, currentPrice);
                        }

                        if (currentPrice > 0 && initialPrice > 0) {
                            double priceChange = ((currentPrice - initialPrice) / initialPrice) * 100;
                            priceChanges.add(priceChange);
                            String color = priceChange >= 0 ? "success" : "error";
                            String arrow = priceChange >= 0 ? "↑" : "↓";
                            String updatedText = String.format("%s | <span class='text-%s'>%s %.2f%%</span>", baseText, color, arrow, Math.abs(priceChange));
                            messagingTemplate.convertAndSend("/topic/update_transaction", Map.of("id", transId, "text", updatedText));
                            logger.debug("Updated price change: {}", updatedText);
                        }
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    private void startInactiveCheck() {
        new Thread(() -> {
            while (true) {
                long currentTime = System.currentTimeMillis();
                for (String key : new ArrayList<>(gmgnClients.keySet())) {
                    GmgnWebSocketClient client = gmgnClients.get(key);
                    if (client != null && (currentTime - client.getLastPriceUpdate() > 5 * 60 * 1000)) {
                        logger.warn("No price update for {}/{} in 5 minutes, disconnecting", key.split("_")[0], key.split("_")[1]);
                        client.close();
                        gmgnClients.remove(key);
                        String token = key.split("_")[1];
                        removeSubscription(token);
                    }
                }
                for (String key : new ArrayList<>(mexcClients.keySet())) {
                    MexcWebSocketClient client = mexcClients.get(key);
                    if (client != null && (currentTime - lastUpdateTimes.getOrDefault(key, 0L) > 5 * 60 * 1000)) {
                        logger.warn("No price update for {} in 5 minutes, disconnecting", key);
                        client.close();
                        mexcClients.remove(key);
                        subscriptions.removeIf(sub -> sub.getMexcName().equals(key));
                        activeConnections.remove(key);
                    }
                }
                try {
                    Thread.sleep(60000); // Check every minute
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    public void checkInactiveConnections(String key) {
        if (gmgnClients.containsKey(key) && (System.currentTimeMillis() - gmgnClients.get(key).getLastPriceUpdate() > 5 * 60 * 1000)) {
            logger.warn("No price update for {}, disconnecting", key);
            gmgnClients.get(key).close();
            gmgnClients.remove(key);
            String token = key.split("_")[1];
            removeSubscription(token);
        }
    }
}