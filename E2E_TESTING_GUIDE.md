# iSeries OTel Bridge - E2E Testing Guide

## Environment Overview

**Requirements:**
- Java 17 (Eclipse Temurin)
- Maven 3.8+
- Docker & Docker Compose (for middleware)

**Project:** Spring Batch application that bridges legacy iSeries logs to OpenTelemetry

---

## Quick Test Commands

```bash
# Run all tests
mvn test

# Run single test class
mvn test -Dtest=BatchIntegrationTest

# Run specific test method
mvn test -Dtest=BatchIntegrationTest#testJobExecution

# Skip code coverage (faster)
mvn test -Djacoco.skip=true

# Apply code formatting
mvn spotless:apply
```

---

## Middleware Setup (Docker Required)

### 1. Start Middleware Services

```bash
cd /home/wilson/opencode/iSeries-Otel-Bridge

# Start OTEL Collector, Kafka, Zookeeper
docker-compose up -d

# Verify services are running
docker-compose ps

# View OTEL collector logs
docker-compose logs -f otel-collector
```

### 2. Stop Middleware

```bash
docker-compose down
```

### 3. Docker Setup (WSL2)

If Docker is not accessible in WSL2:

1. Open **Docker Desktop** on Windows
2. Go to **Settings → Resources → WSL Integration**
3. Enable integration for your WSL distribution
4. Restart Docker Desktop

Alternative - install Docker directly in WSL2:

```bash
sudo apt-get install docker.io docker-compose
sudo service docker start
```

---

## AS/400 Test Data

### Test Data Location

**Monolithic files (by log type):**
```
src/test/resources/iseries*.log
```

**Hourly rotated files (by date/hour):**
```
src/test/resources/rotated/
  iseries_qhst/
    iseries_qhst.08    # 08:00 - 08:59
    iseries_qhst.09    # 09:00 - 09:59
    ...
  iseries_traces/
    iseries_traces.08
    iseries_traces.09
    ...
```

### Available AS/400 Test Log Files (28 total)

#### Core AS/400 Logs
| File | Description |
|------|-------------|
| `iseries_qhst.log` | QHST History Log |
| `iseries_joblog.log` | Job Log with MSGID TYPE SEV |
| `iseries_msgq.log` | Message Queue Log |
| `iseries_spool.log` | Spool File Output |
| `iseries_security.log` | Security Audit Log |
| `iseries_commtrace.log` | Communications Trace |

#### Application & Program Logs
| File | Description |
|------|-------------|
| `iseries_app_logs.log` | RPG/COBOL/CL Program Logs |
| `iseries_program_logs.log` | Program Execution Logs |
| `iseries_erp_logs.log` | ERP Application Logs |

#### Database & SQL Logs
| File | Description |
|------|-------------|
| `iseries_db2_logs.log` | DB2 for i Database Logs |
| `iseries_sql_trace.log` | SQL Trace with trace_id/span_id |
| `iseries_odbc_logs.log` | ODBC/JDBC Logs |

#### System & Network Logs
| File | Description |
|------|-------------|
| `iseries_network_logs.log` | TCPIP/Network Logs |
| `iseries_ftp_logs.log` | FTP Server/Client Logs |
| `iseries_http_logs.log` | HTTP Server Logs |
| `iseries_subsystem_logs.log` | Subsystem/Job Logs |
| `iseries_journal_logs.log` | Journal Logs |
| `iseries_service_logs.log` | Service Job Logs |
| `iseries_lock_logs.log` | Lock/Monitor Logs |

#### Additional Logs
| File | Description |
|------|-------------|
| `iseries_print_logs.log` | Print/Output Queue Logs |
| `iseries_dtaq_logs.log` | Data Queue Logs |
| `iseries_telnet_logs.log` | Telnet/SSH Logs |
| `iseries_backup_logs.log` | Backup/Save-Restore Logs |

#### Trace Files (with trace_id/span_id)
| File | Description |
|------|-------------|
| `iseries_traces.log` | Distributed Trace Logs |
| `iseries_trace_logs.log` | Performance Trace Logs |

#### Metrics Files (METRIC prefix)
| File | Description |
|------|-------------|
| `iseries_metrics.log` | Application Performance Metrics |
| `iseries_system_metrics.log` | System Metrics (CPU, memory, disk) |
| `iseries_database_metrics.log` | DB2 Database Metrics |

---

## AS/400 Grok Patterns Reference

### Standard AS/400 Log Pattern
```
(?s)%{TIMESTAMP_ISO8601:timestamp} %{WORD:msgid} %{GREEDYDATA:message}
```

### Job Log Pattern
```
(?s)%{WORD:msgid} %{SPACE}%{WORD:msgtype} %{SPACE}%{INT:severity} %{SPACE}%{DATA:date} %{SPACE}%{DATA:time} %{SPACE}%{DATA:from_pgm} %{SPACE}%{DATA:from_inst} %{SPACE}%{DATA:to_pgm} %{SPACE}%{GREEDYDATA:message}
```

### Trace Context Pattern
```
(?s)%{TIMESTAMP_ISO8601:timestamp} %{WORD:level} \[trace_id=%{DATA:trace_id} span_id=%{DATA:span_id}\] %{GREEDYDATA:message}
```

### Metrics Pattern
```
(?s)%{TIMESTAMP_ISO8601:timestamp} METRIC %{GREEDYDATA:metrics}
```

### Security Audit Pattern
```
(?s)%{TIMESTAMP_ISO8601:timestamp} %{WORD:audit_type} %{WORD:user} %{DATA:job} %{GREEDYDATA:details}
```

---

## How to Utilize the Project in OpenTelemetry

### Architecture Overview

```
AS/400 Log Files --> iSeries-Otel-Bridge --> OTEL Collector --> Backend
(Input)              (Spring Batch)                              (Jaeger,
                     - Grok Parsing                               Tempo,
                     - Multiline                                  Prometheus)
                     - Trace Context
                       Injection
```

### 1. Running the Application

#### Build the JAR
```bash
mvn clean package -DskipTests
```

#### Run with Environment Variables
```bash
java -jar target/iseries-otel-bridge-1.0-SNAPSHOT.jar \
  --input.file.path=/path/to/your/logfile.log \
  --parser.grok.pattern='(?s)%{TIMESTAMP_ISO8601:timestamp} %{WORD:msgid} %{GREEDYDATA:message}' \
  --otel.exporter.otlp.endpoint=http://localhost:4317 \
  --otel.service.name=my-iseries-app
```

#### Environment Variables
| Variable | Default | Description |
|----------|---------|-------------|
| `INPUT_FILE_PATH_TAIL` | - | Log file path to tail (for file tailing mode) |
| `PARSER_GROK_PATTERN` | `%{GREEDYDATA:message}` | Grok pattern for parsing |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP gRPC endpoint |
| `OTEL_SERVICE_NAME` | `iseries-log-bridge` | Service name in OTel backend |

### 2. OpenTelemetry Collector Configuration

The `otel-collector-config.yaml`:

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:

exporters:
  debug:
    verbosity: detailed

service:
  pipelines:
    logs:
      receivers: [otlp]
      processors: [batch]
      exporters: [debug]
```

---

## Testing OpenTelemetry Integration

### Run Connectivity Test
```bash
# Start middleware first
docker-compose up -d

# Run connectivity test
mvn test -Dtest=OtelConnectivityTest

# Check Docker logs
docker-compose logs otel-collector | grep "Hello OpenTelemetry"
```

### Verify Trace Context Linking
```bash
# Create a test log with trace context
echo '2023-10-27 10:00:00 INFO [trace_id=4bf92f3577b34da6a3ce929d0e0e4736 span_id=00f067aa0ba902b7] Test trace link' > /tmp/trace_test.log

# Run the bridge
java -jar target/iseries-otel-bridge-1.0-SNAPSHOT.jar \
  --input.file.path=/tmp/trace_test.log \
  --parser.grok.pattern='(?s)%{TIMESTAMP_ISO8601:timestamp} %{WORD:level} \[trace_id=%{DATA:trace_id} span_id=%{DATA:span_id}\] %{GREEDYDATA:message}' \
  --otel.exporter.otlp.endpoint=http://localhost:4317

# View in Jaeger UI at http://localhost:16686
```

---

## Troubleshooting

### Kafka Connection Warnings
Tests use mock exporters and don't require Kafka. Warnings can be ignored.

### JaCoCo Coverage Check Fails
```bash
mvn test -Djacoco.skip=true
```

### Docker "Cannot Connect to Daemon"
- Ensure Docker Desktop is running on Windows
- Check WSL integration is enabled
- Or run Docker commands from Windows PowerShell

---

## Sample Test Output

```
=== [SIMULATION] EXPORTED OTEL LOGS ===
These logs were successfully converted and passed to the OpenTelemetry Exporter:
--------------------------------------------------------------------------------
Timestamp: 1773845933416195129 (nanos)
Severity: INFO (INFO)
Body:  System initialized
Attributes: {HOUR="[10, null]", MINUTE="[00, null]", ...}
--------------------------------------------------------------------------------
Total Logs Exported: 4
```

---

## Metrics vs Logs vs Traces in This Project

| Signal | Supported | Notes |
|--------|-----------|-------|
| **Logs** | Yes | Primary function - converts log files to OTel logs |
| **Traces** | Link Only | Extracts trace_id/span_id to link logs to existing traces |
| **Metrics** | No | This project does not export metrics. Use Prometheus exporters instead |
