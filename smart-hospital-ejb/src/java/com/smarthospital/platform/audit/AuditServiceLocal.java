package com.smarthospital.platform.audit;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Local view of the audit service. */
public interface AuditServiceLocal {

    void record(Long actorUserId,
                String action,
                String entityType,
                String entityPublicId,
                String beforeJson,
                String afterJson,
                String reason,
                String ip);

    /**
     * Writes an audit entry for a rejected action in its own transaction, so the
     * evidence survives when the caller rolls back after throwing the error.
     */
    void recordRejection(Long actorUserId,
                         String action,
                         String entityType,
                         String entityPublicId,
                         String reason,
                         String ip);

    List<Map<String, Object>> search(String action,
                                     String entityType,
                                     String entityPublicId,
                                     LocalDateTime from,
                                     LocalDateTime to,
                                     int first,
                                     int max);

    long count(String action,
               String entityType,
               String entityPublicId,
               LocalDateTime from,
               LocalDateTime to);

    ChainVerification verifyChain();

    /** Serializable result for the verification endpoint. */
    class ChainVerification implements Serializable {
        private final boolean valid;
        private final long checked;
        private final Long brokenAt;
        private final String expectedHash;
        private final String actualHash;

        public ChainVerification(boolean valid, long checked, Long brokenAt,
                                 String expectedHash, String actualHash) {
            this.valid = valid;
            this.checked = checked;
            this.brokenAt = brokenAt;
            this.expectedHash = expectedHash;
            this.actualHash = actualHash;
        }

        public boolean isValid() {
            return valid;
        }

        public long getChecked() {
            return checked;
        }

        public Long getBrokenAt() {
            return brokenAt;
        }

        public String getExpectedHash() {
            return expectedHash;
        }

        public String getActualHash() {
            return actualHash;
        }
    }
}
