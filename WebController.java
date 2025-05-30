package porn.tokenparsa.dick.controller;

import porn.tokenparsa.dick.model.Subscription;
import porn.tokenparsa.dick.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
public class WebController {
    private static final Logger logger = LoggerFactory.getLogger(WebController.class);
    private final TokenService tokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public WebController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("subscriptions", tokenService.getSubscriptions());
        return "index";
    }

    @GetMapping("/icon/{coinName}")
    @ResponseBody
    public Map<String, String> getIcon(@PathVariable String coinName) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://fluoxiaapi.onrender.com/getcryptoicon?ticker=" + coinName))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), Map.class);
            }
        } catch (Exception e) {
            logger.error("Error fetching icon for {}: {}", coinName, e.getMessage());
        }
        return Map.of("icon", "sosal", "name", "404 not found coin");
    }

    @PostMapping("/add_token")
    @ResponseBody
    public Map<String, Object> addToken(@RequestBody Map<String, String> data) {
        try {
            String gmgnUrl = data.get("gmgn_url");
            String mexcName = data.get("mexc_name");
            String displayName = data.get("display_name");
            String symbolName = data.get("symbolname");

            Pattern pattern = Pattern.compile("https://gmgn\\.ai/([^/]+)/token/([^/?]+)");
            Matcher matcher = pattern.matcher(gmgnUrl);
            if (!matcher.matches()) {
                return Map.of("success", false, "error", "Invalid GMGN URL format");
            }

            String network = matcher.group(1);
            String rawToken = matcher.group(2);
            String tokenAddress = rawToken.split("_")[rawToken.split("_").length - 1];

            if (tokenService.getSubscriptions().stream().anyMatch(sub -> sub.getMexcName().equalsIgnoreCase(mexcName))) {
                return Map.of("success", false, "error", "Token with this MEXC name already exists");
            }

            Subscription subscription = new Subscription();
            subscription.setNetwork(network);
            subscription.setTokenContractAddress(tokenAddress);
            subscription.setMexcName(mexcName.toUpperCase());
            subscription.setDisplayName(displayName != null && !displayName.isEmpty() ? displayName : mexcName.split("_")[0]);
            subscription.setSymbol(symbolName != null && !symbolName.isEmpty() ? symbolName : mexcName.split("_")[0]);
            subscription.setViewThreshold(1000);
            subscription.setNotifyThreshold(5000);

            tokenService.addSubscription(subscription);
            return Map.of("success", true, "token", tokenAddress, "viewThreshold", 500.0, "notifyThreshold", 4000.0);
        } catch (Exception e) {
            logger.error("Error adding token: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PostMapping("/update_threshold")
    @ResponseBody
    public Map<String, Object> updateThreshold(@RequestBody Map<String, String> data) {
        try {
            String token = data.get("token");
            double notifyThreshold = Double.parseDouble(data.get("notifyThreshold"));
            Subscription subscription = tokenService.getSubscription(token);
            if (subscription == null) {
                return Map.of("success", false, "error", "Subscription not found");
            }
            subscription.setNotifyThreshold(notifyThreshold);
            logger.info("Updated notifyThreshold for token {} to {}", token, notifyThreshold);
            return Map.of("success", true, "threshold", notifyThreshold);
        } catch (Exception e) {
            logger.error("Error updating threshold: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PostMapping("/remove_token")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> removeToken(@RequestBody Map<String, String> request) {
        String token = request.get("token");
        tokenService.removeSubscription(token);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("token", token);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/clear_transactions")
    @ResponseBody
    public Map<String, Object> clearTransactions(@RequestBody Map<String, String> data) {
        try {
            String token = data.get("token");
            tokenService.messagingTemplate.convertAndSend("/topic/clear_transactions", Map.of("token", token));
            return Map.of("success", true);
        } catch (Exception e) {
            logger.error("Error clearing transactions: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }
}