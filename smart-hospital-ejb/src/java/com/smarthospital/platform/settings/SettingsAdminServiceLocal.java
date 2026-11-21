package com.smarthospital.platform.settings;

import java.util.List;
import java.util.Map;

/** Local view of SYS settings administration. */
public interface SettingsAdminServiceLocal {

    List<Map<String, Object>> list();

    Map<String, Object> update(String key, String value, Long actorUserId, String ip);
}
