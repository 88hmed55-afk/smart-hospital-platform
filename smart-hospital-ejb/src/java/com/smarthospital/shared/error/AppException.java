package com.smarthospital.shared.error;

/**
 * Business/HTTP error raised by EJB services and translated to the documented
 * JSON envelope by the web layer. Keeping it independent of Jakarta REST lets
 * services stay testable and avoids leaking HTTP types into the domain.
 */
public class AppException extends RuntimeException {

    private final int status;
    private final String code;
    private final String messageAr;
    private final String messageEn;

    public AppException(int status, String code, String messageAr, String messageEn) {
        super(messageEn);
        this.status = status;
        this.code = code;
        this.messageAr = messageAr;
        this.messageEn = messageEn;
    }

    public static AppException badRequest(String code, String messageAr, String messageEn) {
        return new AppException(400, code, messageAr, messageEn);
    }

    public static AppException unauthorized(String code, String messageAr, String messageEn) {
        return new AppException(401, code, messageAr, messageEn);
    }

    public static AppException forbidden(String code, String messageAr, String messageEn) {
        return new AppException(403, code, messageAr, messageEn);
    }

    public static AppException notFound(String code, String messageAr, String messageEn) {
        return new AppException(404, code, messageAr, messageEn);
    }

    public static AppException conflict(String code, String messageAr, String messageEn) {
        return new AppException(409, code, messageAr, messageEn);
    }

    public static AppException unprocessable(String code, String messageAr, String messageEn) {
        return new AppException(422, code, messageAr, messageEn);
    }

    public static AppException tooMany(String code, String messageAr, String messageEn) {
        return new AppException(429, code, messageAr, messageEn);
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getMessageAr() {
        return messageAr;
    }

    public String getMessageEn() {
        return messageEn;
    }
}
