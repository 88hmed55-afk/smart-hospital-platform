package com.smarthospital.platform.iam;

/** A stored password hash, used to enforce the "last N passwords" policy. */
public class PasswordHashRecord {

    private final String hash;
    private final byte[] salt;
    private final String algorithm;
    private final int iterations;

    public PasswordHashRecord(String hash, byte[] salt, String algorithm, int iterations) {
        this.hash = hash;
        this.salt = salt;
        this.algorithm = algorithm;
        this.iterations = iterations;
    }

    public String getHash() {
        return hash;
    }

    public byte[] getSalt() {
        return salt;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public int getIterations() {
        return iterations;
    }
}
