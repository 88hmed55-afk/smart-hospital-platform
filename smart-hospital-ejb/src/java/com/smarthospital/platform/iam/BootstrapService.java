package com.smarthospital.platform.iam;

import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.json.JsonUtil;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * First-run bootstrap. Creates a single {@code admin} account only when the
 * users table is empty, so restarts and redeployments never overwrite real
 * accounts. In PROD an explicit bootstrap password is mandatory.
 */
@Stateless
public class BootstrapService {

    private static final Logger LOG = Logger.getLogger(BootstrapService.class.getName());
    private static final String DEV_DEFAULT_PASSWORD = "Admin@12345";

    @EJB
    private IamRepository repository;

    @EJB
    private AuditServiceLocal audit;

    public void bootstrapIfEmpty(String environment, String configuredPassword) {
        if (repository.countUsers(null) > 0) {
            return;
        }

        String password = configuredPassword;
        if (password == null || password.isBlank()) {
            if ("PROD".equalsIgnoreCase(environment)) {
                LOG.severe("No users exist and iam.bootstrap.admin_password is not set; "
                        + "refusing to create a default administrator in PROD.");
                return;
            }
            password = DEV_DEFAULT_PASSWORD;
            LOG.warning("Creating development administrator 'admin' with the documented DEV password. "
                    + "Change it immediately after first login.");
        }

        byte[] salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(password, salt, PasswordHasher.DEFAULT_ITERATIONS);
        User admin = new User(
                Ulid.next(),
                "admin",
                hash,
                salt,
                PasswordHasher.ALGORITHM,
                PasswordHasher.DEFAULT_ITERATIONS,
                "مدير النظام",
                "System Administrator");
        admin.setPasswordChangedAt(LocalDateTime.now());
        admin.setMustChangePassword(true);
        repository.saveUser(admin);
        repository.addPasswordHistory(admin.getId(), hash, salt,
                PasswordHasher.ALGORITHM, PasswordHasher.DEFAULT_ITERATIONS);

        Role sysAdmin = repository.findRoleByCode("SYS_ADMIN");
        if (sysAdmin != null) {
            repository.replaceUserRoles(admin.getId(), List.of(sysAdmin.getId()), admin.getId());
        } else {
            LOG.severe("SYS_ADMIN role is missing; bootstrap administrator has no permissions. "
                    + "Run the S001/S002 seeds.");
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("username", "admin");
        after.put("roles", List.of("SYS_ADMIN"));
        audit.record(null, "BOOTSTRAP_ADMIN_CREATED", "USER", admin.getPublicId(),
                null, JsonUtil.toJson(after), "first-run bootstrap", null);
        LOG.info(() -> "Bootstrap administrator created: " + admin.getPublicId());
    }
}
