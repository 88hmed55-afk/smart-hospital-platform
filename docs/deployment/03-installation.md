# 03 — Installation Runbook

**دليل تركيب الخادم من ISO فارغ إلى نظام يعمل — خطوة بخطوة وقابل للتكرار**

| Field | Value |
|---|---|
| Document ID | DEP-INS-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Target | Ubuntu Server 24.04 LTS · JDK 17 · GlassFish 7 · MySQL 8.0.38 · Nginx |
| Related | `01-server-architecture.md`, `02-network-topology.md`, `04-backup-recovery.md`, `database/04-migrations-seed.md` |

> **تحذير:** هذا Runbook تنفيذي. نفّذه على خادم اختبار أولًا، ثم STAGING، ثم الإنتاج. كل أمر بصلاحية root مكتوب صراحة. استبدل القيم بين `<...>`.

---

## 0. قائمة التحقق قبل البدء

- [ ] الخادم يلبي 8 vCPU / 16 GB RAM / 500 GB SSD (الموصى به 32 GB / 1 TB).
- [ ] عنوان IP ثابت وDNS داخلي محجوزان (ASM-024).
- [ ] شهادة TLS أو CA داخلي جاهز (PD-010).
- [ ] NAS/خزن نسخ احتياطي متاح (SFTP/SSH).
- [ ] UPS يعمل ومختبر.
- [ ] نسخة من EAR وملفات Migrations ببصمة SHA-256.
- [ ] نافذة صيانة معتمدة وإشعار للمستخدمين.
- [ ] خطة تراجع (EAR السابق + نسخة DB).

---

## 1. تثبيت نظام التشغيل

1. نزّل Ubuntu Server 24.04 LTS من المصدر الرسمي وتحقق من SHA-256.
2. ثبّت بنمط **Minimal**، مع LVM (إن أمكن) و`/` على SSD.
3. أنشئ مستخدمًا إداريًا (`sysadmin`) ولا تستخدم root للدخول اليومي.
4. اضبط الشبكة بعنوان ثابت:

```bash
sudo hostnamectl set-hostname his
sudo timedatectl set-timezone Asia/Amman
sudo nano /etc/netplan/50-cloud-init.yaml
sudo netplan apply
```

5. حدّث النظام وأعد التشغيل:

```bash
sudo apt update && sudo apt full-upgrade -y
sudo reboot
```

6. تحقق:

```bash
uname -a
lsb_release -a
ip -br a
timedatectl
```

---

## 2. التقوية الأساسية (Hardening)

```bash
# جدار حماية: اسمح فقط بما تحتاجه
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow from 10.20.10.0/24 to any port 22 proto tcp
sudo ufw allow from 10.20.30.0/23 to any port 443 proto tcp
sudo ufw allow from 10.20.40.0/23 to any port 443 proto tcp
sudo ufw allow from 10.20.30.0/23 to any port 80 proto tcp
sudo ufw allow from 10.20.40.0/23 to any port 80 proto tcp
sudo ufw enable
sudo ufw status numbered

# SSH: مفاتيح فقط
sudo sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sudo systemctl restart ssh

# تحديثات أمنية تلقائية
sudo apt install -y unattended-upgrades fail2ban chrony
sudo dpkg-reconfigure -plow unattended-upgrades
```

**تحقق:** لا يمكن الدخول بكلمة مرور، ولا يمكن الوصول إلى 22 من VLAN 30.

---

## 3. تثبيت JDK 17 (Temurin)

```bash
sudo apt install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-17-jdk
java -version
```

**المتوقع:** `openjdk version "17.0.x"`.

---

## 4. تثبيت MySQL 8.0.38

### 4.1 التثبيت

```bash
# استخدم مستودع MySQL APT أو حزمة موقعة، وثبّت الإصدار 8.0.38
sudo apt install -y mysql-server
mysql --version
```

> إذا لم يتوفر 8.0.38 في المستودع الافتراضي، ثبّت من مستودع MySQL الرسمي مع Pin للإصدار. لا تستخدم إصدارًا أحدث في الإنتاج قبل اختبار STAGING.

### 4.2 الإعداد

```bash
sudo nano /etc/mysql/mysql.conf.d/99-smart-hospital.cnf
```

```ini
[mysqld]
bind-address = 127.0.0.1
character-set-server = utf8mb4
collation-server = utf8mb4_0900_ai_ci
default-time-zone = '+00:00'
transaction-isolation = READ-COMMITTED

innodb_buffer_pool_size = 12G
innodb_redo_log_capacity = 2G
innodb_flush_log_at_trx_commit = 1
sync_binlog = 1

log_bin = mysql-bin
binlog_format = ROW
binlog_expire_logs_seconds = 1209600
max_binlog_size = 256M

max_connections = 200
slow_query_log = 1
long_query_time = 1
log_queries_not_using_indexes = 0

sql_mode = STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION
```

```bash
sudo systemctl restart mysql
sudo systemctl enable mysql
sudo mysql -e "SELECT @@version, @@transaction_isolation, @@time_zone, @@character_set_server;"
```

**ملاحظة:** على 16 GB RAM استخدم `innodb_buffer_pool_size = 6G` وJVM 4G.

### 4.3 قاعدة البيانات والمستخدمون

```bash
sudo mysql
```

```sql
CREATE DATABASE smart_hospital
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- مستخدم التطبيق: DML فقط، بلا DDL
CREATE USER 'sh_app'@'127.0.0.1' IDENTIFIED BY '<STRONG_APP_PASSWORD>';
GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE
  ON smart_hospital.* TO 'sh_app'@'127.0.0.1';

-- مستخدم الترحيل: DDL، يُستخدم فقط عند النشر
CREATE USER 'sh_migrate'@'127.0.0.1' IDENTIFIED BY '<STRONG_MIGRATE_PASSWORD>';
GRANT ALL PRIVILEGES ON smart_hospital.* TO 'sh_migrate'@'127.0.0.1';

-- مستخدم النسخ الاحتياطي
CREATE USER 'sh_backup'@'127.0.0.1' IDENTIFIED BY '<STRONG_BACKUP_PASSWORD>';
GRANT SELECT, LOCK TABLES, RELOAD, REPLICATION CLIENT, PROCESS, SHOW VIEW, EVENT, TRIGGER
  ON *.* TO 'sh_backup'@'127.0.0.1';

FLUSH PRIVILEGES;
```

**قاعدة:** لا تُمنح `DROP`/`ALTER` لمستخدم التطبيق. حماية `audit_logs` من UPDATE/DELETE تُطبّق عبر Trigger في ترحيل `V002__create_audit_outbox.sql` (لأن صلاحية مستوى القاعدة تتجاوز المنع على مستوى الجدول).

---

## 5. تثبيت GlassFish 7

```bash
sudo useradd -r -m -d /opt/glassfish7 -s /bin/bash glassfish
cd /tmp
# نزّل GlassFish 7 Full الرسمي وتحقق من SHA-256
sudo unzip -q glassfish-7.0.x.zip -d /opt
sudo chown -R glassfish:glassfish /opt/glassfish7
sudo -u glassfish /opt/glassfish7/bin/asadmin start-domain
```

### 5.1 تأمين الإدارة

```bash
sudo -u glassfish /opt/glassfish7/bin/asadmin change-admin-password
sudo -u glassfish /opt/glassfish7/bin/asadmin enable-secure-admin
sudo -u glassfish /opt/glassfish7/bin/asadmin restart-domain
```

- منفذ 4848 يبقى محجوبًا بـ UFW؛ الوصول عبر نفق SSH فقط:
  `ssh -L 4848:127.0.0.1:4848 sysadmin@his`
- لا تُفعّل الوصول الإداري من الشبكة.

### 5.2 برنامج تشغيل MySQL (Connector/J)

```bash
# السائق يجب أن يكون مرئيًا لـ classpath النطاق (المسار المُتحقق منه في المرحلة 1)
sudo cp mysql-connector-j-8.x.jar /opt/glassfish7/glassfish/domains/domain1/lib/
sudo chown glassfish:glassfish /opt/glassfish7/glassfish/domains/domain1/lib/mysql-connector-j-8.x.jar
sudo -u glassfish /opt/glassfish7/bin/asadmin restart-domain
```

> بديل: `/opt/glassfish7/glassfish/lib/` (classpath مشترك لكل النطاقات). لا تضع السائق في `WEB-INF/lib` مع EAR.

### 5.3 Connection Pool

```bash
sudo -u glassfish /opt/glassfish7/bin/asadmin create-jdbc-connection-pool \
  --datasourceclassname com.mysql.cj.jdbc.MysqlDataSource \
  --restype javax.sql.DataSource \
  --property user=sh_app:password=<APP_PASSWORD>:serverName=127.0.0.1:portNumber=3306:databaseName=smart_hospital:useSSL=false:allowPublicKeyRetrieval=true:characterEncoding=utf8:serverTimezone=UTC \
  smartHospitalPool

sudo -u glassfish /opt/glassfish7/bin/asadmin create-jdbc-resource \
  --connectionpoolid smartHospitalPool jdbc/smartHospitalDS

sudo -u glassfish /opt/glassfish7/bin/asadmin set resources.jdbc-connection-pool.smartHospitalPool.steady-pool-size=20
sudo -u glassfish /opt/glassfish7/bin/asadmin set resources.jdbc-connection-pool.smartHospitalPool.max-pool-size=50
sudo -u glassfish /opt/glassfish7/bin/asadmin set resources.jdbc-connection-pool.smartHospitalPool.pool-resize-quantity=5
sudo -u glassfish /opt/glassfish7/bin/asadmin set resources.jdbc-connection-pool.smartHospitalPool.idle-timeout-in-seconds=300
sudo -u glassfish /opt/glassfish7/bin/asadmin set resources.jdbc-connection-pool.smartHospitalPool.connection-leak-timeout-in-seconds=30
sudo -u glassfish /opt/glassfish7/bin/asadmin ping-connection-pool smartHospitalPool
```

**المتوقع:** `ping-connection-pool` ينجح. الأسماء أعلاه هي نفسها المستخدمة في `persistence.xml` والمرحلة 1.

### 5.4 JMS

```bash
sudo -u glassfish /opt/glassfish7/bin/asadmin create-jms-resource \
  --restype jakarta.jms.ConnectionFactory jms/SmartHospitalConnectionFactory
sudo -u glassfish /opt/glassfish7/bin/asadmin create-jms-resource \
  --restype jakarta.jms.Queue --property Name=smartHospitalEvents jms/SmartHospitalEvents
```

---

## 6. الترحيلات والبيانات الأولية

```bash
sudo mkdir -p /opt/smart-hospital/app
sudo chown -R glassfish:glassfish /opt/smart-hospital
# انسخ محتوى db/ (migrations, seed, verify, tools, build.xml) إلى /opt/smart-hospital/app/db
cd /opt/smart-hospital/app/db

# استخدم db.properties بصلاحية 600 بدل كلمة المرور على سطر الأوامر
sudo -u glassfish ant db-migrate
sudo -u glassfish ant db-seed
sudo -u glassfish ant db-verify
sudo -u glassfish ant db-status
```

**قواعد:**
- الترحيل Forward-only. لا تعديل ملف مُطبّق.
- `db-seed` للبيانات المرجعية فقط (أقسام، أدوار، إعدادات، صلاحيات).
- `S006__demo_data.sql` **لا يُنفّذ في الإنتاج** (بيانات وهمية).
- تحقق من `schema_version` بعد الانتهاء (`db-status`).

---

## 7. نشر التطبيق

```bash
sudo -u glassfish /opt/glassfish7/bin/asadmin deploy \
  --force=true --contextroot / --name smart-hospital \
  /opt/smart-hospital/app/smart-hospital-ear/dist/smart-hospital.ear

sudo -u glassfish /opt/glassfish7/bin/asadmin list-applications
curl -k https://127.0.0.1/api/v1/health
```

**المتوقع:** استجابة 200 تحتوي `status=UP` و`database=UP` و`disk=UP`، مع رأس `X-Trace-Id`.

---

## 8. Nginx + TLS

```bash
sudo apt install -y nginx
sudo mkdir -p /etc/nginx/ssl
# انسخ الشهادة والمفتاح (صلاحية 600)
sudo nano /etc/nginx/sites-available/smart-hospital
```

```nginx
server {
    listen 80;
    server_name his.hospital.local;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name his.hospital.local;

    ssl_certificate     /etc/nginx/ssl/his.crt;
    ssl_certificate_key /etc/nginx/ssl/his.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;

    root /opt/smart-hospital/app/web;
    index index.html;
    client_max_body_size 10m;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }

    location /api/v1/events {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }

    location ~* \.(js|css|png|jpg|jpeg|gif|svg|woff2)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/smart-hospital /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

**ملاحظة CSP:** إذا استخدم Vite أنماطًا مضمّنة، عدّل السياسة بعد مراجعة أمنية؛ لا تُضف `unsafe-inline` للـ scripts أبدًا.

---

## 9. خدمات systemd

```bash
sudo nano /etc/systemd/system/glassfish.service
```

```ini
[Unit]
Description=GlassFish 7 (Smart Hospital)
After=network.target mysql.service
Wants=mysql.service

[Service]
Type=oneshot
RemainAfterExit=yes
User=glassfish
Group=glassfish
ExecStart=/opt/glassfish7/bin/asadmin start-domain
ExecStop=/opt/glassfish7/bin/asadmin stop-domain
TimeoutStartSec=180

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable glassfish nginx mysql
sudo systemctl restart glassfish
sudo systemctl status glassfish --no-pager
```

**ترتيب الإقلاع:** MySQL → GlassFish → Nginx. تحقق بعد إعادة التشغيل.

---

## 10. اختبارات الدخان (Smoke Tests)

| # | الاختبار | الأمر/الطريقة | المتوقع |
|---|---|---|---|
| S-01 | Health | `curl -k https://his.hospital.local/api/v1/health` | 200 |
| S-02 | Ready | `curl -k .../health/ready` | DB/JMS/Disk = UP |
| S-03 | TLS | `openssl s_client -connect his.hospital.local:443` | شهادة صالحة، TLS 1.2+ |
| S-04 | Redirect | `curl -I http://his.hospital.local` | 301 → https |
| S-05 | Login | متصفح | صفحة الدخول RTL |
| S-06 | DB | `mysql -h127.0.0.1 -ush_app -p -e "SELECT 1"` | 1 |
| S-07 | JMS | إرسال حدث اختباري | يظهر في `outbox_events` ثم `PROCESSED` |
| S-08 | SSE | فتح `/api/v1/events` | اتصال مفتوح، لا قطع كل 60 ثانية |
| S-09 | Backup | تشغيل النسخة يدويًا | ملف مشفّر + checksum |
| S-10 | Restart | `sudo reboot` | الخدمة تعود ≤ 10 دقائق |

**سجل تحقق المرحلة 1 (2026-11-12، بيئة تطوير محلية):**
- S-01 نجح: `GET /api/v1/health` = 200، `database=UP`، `disk=UP`، `version=0.1.0-phase1`.
- نشر EAR نجح على جذر `/` باسم `smart-hospital`.
- `/app/dashboard` = 200 (`text/html`) عبر `SpaFallbackFilter`، و`/api/v1/nonexistent` = 404 بمغلّف JSON يحوي `traceId`.
- الواجهة: عربي RTL افتراضيًا، والتبديل إلى الإنجليزية يعطي `dir=ltr`، بلا أخطاء في وحدة التحكم.
- S-02/S-05/S-07/S-08 مؤجلة: نقاط `/health/ready` وJMS وSSE وتدفّق الدخول تُنفَّذ في المراحل 2 و6.


---

## 11. التراجع (Rollback)

| الحالة | الإجراء |
|---|---|
| فشل نشر EAR | `asadmin undeploy smart-hospital` ثم نشر النسخة السابقة من `/opt/smart-hospital/releases/` |
| فشل ترحيل | استعادة نسخة DB قبل الترحيل + نشر EAR السابق (الترحيل Forward-only) |
| فشل Nginx | `nginx -t` ثم استعادة آخر config من Git |
| فشل شهادة | استعادة الشهادة السابقة وإعادة التحميل |
| فشل MySQL | اتبع `04-backup-recovery.md` |

**قاعدة:** لا تراجع بـ `git checkout` على الخادم. كل شيء من حزمة موقعة.

---

## 12. قائمة Go-Live

- [ ] كل اختبارات الدخان ناجحة.
- [ ] النسخ الاحتياطي يعمل ويُختبر.
- [ ] المراقبة والتنبيهات مفعّلة.
- [ ] المستخدمون أُنشئوا بأدوارهم (لا `admin` مشترك).
- [ ] كلمات المرور الأولية تُغيّر عند أول دخول.
- [ ] الشهادات صالحة > 60 يومًا.
- [ ] السجلات تدور ولا تملأ القرص.
- [ ] خطة التراجع موثّقة ومعتمدة.
- [ ] الدعم وجهات الاتصال معروفة.
- [ ] إشعار المستخدمين موجه.
