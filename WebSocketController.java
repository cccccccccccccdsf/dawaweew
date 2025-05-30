package porn.tokenparsa.dick.controller;

import porn.tokenparsa.dick.model.PriceUpdate;
import porn.tokenparsa.dick.service.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class WebSocketController {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketController.class);
    private final TokenService tokenService;

    @Autowired
    public WebSocketController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @MessageMapping("/activate_token")
    public void activateToken(@Payload Map<String, String> data) {
        String token = data.get("token");
        if (!tokenService.getSubscriptions().stream().anyMatch(sub -> sub.getTokenContractAddress().equals(token))) {
            return;
        }
        double lastPrice = tokenService.getLastPrice(token);
        double mexcPrice = tokenService.getMexcPrice(tokenService.getSubscription(token).getMexcName());
        tokenService.messagingTemplate.convertAndSend("/topic/price_update", new PriceUpdate(token, lastPrice, mexcPrice));
        logger.info("Sent initial prices for {}: GMGN=${}, MEXC=${}", token, lastPrice, mexcPrice);
    }
}