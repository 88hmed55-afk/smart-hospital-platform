package com.smarthospital.platform.iam;

import java.io.Serializable;

/** Data-scope assignment returned to the client. */
public class ScopeView implements Serializable {

    private String type;
    private Long refId;

    public ScopeView() {
    }

    public ScopeView(String type, Long refId) {
        this.type = type;
        this.refId = refId;
    }

    public String getType() {
        return type;
    }

    public Long getRefId() {
        return refId;
    }
}
