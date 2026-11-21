package com.smarthospital.platform.settings;

import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.json.JsonUtil;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SYS settings administration (FR-SYS). Sensitive values are write-only from
 * the UI: reads return a mask, and every change is audited with before/after.
 */
@Stateless
public class SettingsAdminService implements SettingsAdminServiceLocal {

    private static final String MASK = "********";

    @EJB
    private SettingsRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @Override
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Setting setting : repository.findAll()) {
            items.add(map(setting));
        }
        return items;
    }

    @Override
    public Map<String, Object> update(String key, String value, Long actorUserId, String ip) {
        Setting setting = repository.findByKey(key)
                .orElseThrow(() -> AppException.notFound("SETTING_NOT_FOUND",
                        "الإعداد غير موجود.",
                        "Setting not found."));
        validate(setting.getValueType(), value);
        String before = setting.isSensitive() ? MASK : setting.getValue();
        setting.setValue(value);
        setting.setUpdatedBy(actorUserId);
        setting.setUpdatedAt(LocalDateTime.now());
        repository.save(setting);

        Map<String, Object> beforeJson = new LinkedHashMap<>();
        beforeJson.put("key", key);
        beforeJson.put("value", before);
        Map<String, Object> afterJson = new LinkedHashMap<>();
        afterJson.put("key", key);
        afterJson.put("value", setting.isSensitive() ? MASK : value);
        audit.record(actorUserId, "SETTING_UPDATED", "SETTING", null,
                JsonUtil.toJson(beforeJson), JsonUtil.toJson(afterJson), null, ip);
        return map(setting);
    }

    private Map<String, Object> map(Setting setting) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", setting.getKey());
        item.put("value", setting.isSensitive() ? MASK : setting.getValue());
        item.put("valueType", setting.getValueType());
        item.put("category", setting.getCategory());
        item.put("sensitive", setting.isSensitive());
        item.put("updatedBy", setting.getUpdatedBy());
        item.put("updatedAt", setting.getUpdatedAt() == null ? null : setting.getUpdatedAt().toString());
        item.put("version", setting.getVersion());
        return item;
    }

    private static void validate(String type, String value) {
        if (value == null) {
            return;
        }
        switch (type) {
            case "INT" -> {
                try {
                    Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    throw AppException.unprocessable("INVALID_SETTING_VALUE",
                            "القيمة يجب أن تكون عددًا صحيحًا.",
                            "The value must be an integer.");
                }
            }
            case "BOOL" -> {
                String normalized = value.trim().toLowerCase();
                if (!normalized.equals("true") && !normalized.equals("false")) {
                    throw AppException.unprocessable("INVALID_SETTING_VALUE",
                            "القيمة يجب أن تكون true أو false.",
                            "The value must be true or false.");
                }
            }
            case "JSON" -> {
                try {
                    jakarta.json.Json.createReader(new java.io.StringReader(value)).readValue();
                } catch (RuntimeException ex) {
                    throw AppException.unprocessable("INVALID_SETTING_VALUE",
                            "القيمة يجب أن تكون JSON صالحًا.",
                            "The value must be valid JSON.");
                }
            }
            default -> {
                // STRING: no validation.
            }
        }
    }
}
