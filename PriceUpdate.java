package porn.tokenparsa.dick.model;

public class PriceUpdate {
    private String token;
    private double lastPrice;
    private double mexcPrice;

    // Constructor, getters, and setters
    public PriceUpdate(String token, double lastPrice, double mexcPrice) {
        this.token = token;
        this.lastPrice = lastPrice;
        this.mexcPrice = mexcPrice;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public double getLastPrice() { return lastPrice; }
    public void setLastPrice(double lastPrice) { this.lastPrice = lastPrice; }
    public double getMexcPrice() { return mexcPrice; }
    public void setMexcPrice(double mexcPrice) { this.mexcPrice = mexcPrice; }
}