package porn.tokenparsa.dick.model;

public class Subscription {
    private String network;
    private String tokenContractAddress;
    private String mexcName;
    private String displayName;
    private String symbol;
    private double notifyThreshold;
    private double viewThreshold;

    // Constructor
    public Subscription() {
    }

    // Getters and Setters
    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getTokenContractAddress() {
        return tokenContractAddress;
    }

    public void setTokenContractAddress(String tokenContractAddress) {
        this.tokenContractAddress = tokenContractAddress;
    }

    public String getMexcName() {
        return mexcName;
    }

    public void setMexcName(String mexcName) {
        this.mexcName = mexcName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public double getNotifyThreshold() {
        return notifyThreshold;
    }

    public void setNotifyThreshold(double notifyThreshold) {
        this.notifyThreshold = notifyThreshold;
    }

    public void setViewThreshold(double viewThreshold) {
        this.viewThreshold = viewThreshold;
    }

    public double getViewThreshold() {
        return viewThreshold;
    }
}