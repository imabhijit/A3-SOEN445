public enum Status {
    OK(200, "OK"),
    NOT_FOUND(404, "Not Found"),
    CREATED(201, "Created"),
    BAD_REQUEST(400, "Bad Request"),
    FORBIDDEN(403, "Forbidden");

    private final int code;
    private final String message;

    Status(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String toString() {
        return this.code + " " + this.message;
    }
}
