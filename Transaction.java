package porn.tokenparsa.dick.model;

public class Transaction {
    private String token;
    private String text;
    private String id;
    private boolean isNotification;

    public Transaction(String token, String text, String id, boolean isNotification) {
        this.token = token;
        this.text = text;
        this.id = id;
        this.isNotification = isNotification;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isNotification() {
        return isNotification;
    }

    public void setNotification(boolean notification) {
        isNotification = notification;
    }
}