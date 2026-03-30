# iSeries OTel Bridge - Horizontal Scaling Design

## Problem Statement

When log volume exceeds what a single pod can process, we need horizontal scaling. However, multiple pods reading the same log file causes:
- **Duplicate processing**: Each pod reads from the same position
- **Out-of-order logs**: Pods process at different speeds

## Architecture Options

### Option A: Single Pod (No Scaling) - Baseline

```
┌─────────────────────────────────────────────┐
│  Log File (PVC)                             │
│                                             │
│  ┌─────────────────────────────────────┐   │
│  │  iSeries-OTel-Bridge Pod           │   │
│  │  ┌─────────┐  ┌─────────┐  ┌──────┐ │   │
│  │  │ Tailing │→│  Grok   │→│ OTel │ │   │
│  │  │ Reader  │  │ Process │  │Writer│ │   │
│  │  └─────────┘  └─────────┘  └──────┘ │   │
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
                    │
                    ▼
             ┌─────────────┐
             │   OTel      │
             │  Collector  │
             └─────────────┘
```

**Infrastructure Required:**
| Component | Local | AWS |
|-----------|-------|-----|
| Docker | ✅ | - |
| OTel Collector | ✅ (docker-compose) | ✅ (ECS/EKS sidecar or DaemonSet) |
| OTel Backend (Jaeger/Tempo) | ✅ (docker-compose) | ✅ (AWS managed: X-Ray, or self-hosted) |
| Shared Storage (PVC) | ✅ (hostPath/nfs) | ✅ (EFS, EBS, or S3) |

---

### Option B: Kafka-Based Scaling (Recommended)

```
┌──────────────────────────────────────────────────────────────────┐
│  Log File (EFS/PVC)                                             │
│                                                                  │
│  ┌──────────────┐                                               │
│  │   Tailer     │──────┐                                         │
│  │   Pod (1x)   │      │                                         │
│  └──────────────┘      │      ┌─────────────────────────┐       │
│                       │      │     Kafka Cluster        │       │
│                       │─────▶│  Partition by timestamp  │       │
│                              └───────────┬─────────────┘       │
│                                          │                     │
│               ┌───────────────────────────┼───────────────┐    │
│               │                           │               │    │
│               ▼                           ▼               ▼    │
│      ┌──────────────┐           ┌──────────────┐ ┌──────────────┐
│      │  Consumer    │           │  Consumer    │ │  Consumer    │
│      │  Pod #1      │           │  Pod #2      │ │  Pod #N      │
│      │  (Grok+OTel)│           │  (Grok+OTel) │ │  (Grok+OTel) │
│      └──────┬───────┘           └──────┬───────┘ └──────┬───────┘
│             │                          │               │        │
│             └──────────────────────────┴───────────────┘        │
│                                    │                             │
│                                    ▼                             │
│                           ┌──────────────┐                        │
│                           │   OTel       │                        │
│                           │  Collector   │                        │
│                           └──────────────┘                        │
└──────────────────────────────────────────────────────────────────┘
```

**How it works:**
1. **Single Tailer Pod**: Reads log file, publishes raw lines to Kafka (keyed by log timestamp for ordering)
2. **Consumer Group**: N pods consume from Kafka, each processing its partition independently
3. **Exactly-Once**: Kafka offset management ensures no duplicates

---

### Option C: StatefulSet with Cursor Files

Each pod maintains its own cursor:

```
┌─────────────────────────────────────────────────────────────┐
│  Log File (EFS/PVC)                                        │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ Pod #0       │  │ Pod #1       │  │ Pod #N       │     │
│  │ cursor0.json│  │ cursor1.json │  │ cursorN.json │     │
│  │ offset: 0   │  │ offset: 100K │  │ offset: 200K │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

**Pros/Cons:**
- ✅ No Kafka required
- ❌ Only works with append-only logs
- ❌ Complex cursor management
- ❌ No backpressure mechanism

---

## Infrastructure Comparison

### Local Development

| Option | Components to Setup | Complexity |
|--------|---------------------|------------|
| A (Single Pod) | Docker, OTel Collector, Jaeger | ⭐ Easy |
| B (Kafka) | Docker, Kafka (single broker), OTel Collector, Jaeger | ⭐⭐⭐ Medium |
| C (StatefulSet) | Docker, OTel Collector, Jaeger, NFS simulation | ⭐⭐ Medium |

**Docker Compose for Option B (Local):**
```yaml
# docker-compose.local.yaml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT

  otel-collector:
    image: otel/opentelemetry-collector:latest
    ports:
      - "4317:4317"
    volumes:
      - ./otel-collector-config.yaml:/etc/otelcol-contrib/config.yaml

  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "16686:16686"
```

---

### AWS Deployment

| Option | AWS Services Required | Managed vs Self-Hosted |
|--------|----------------------|----------------------|
| A (Single Pod) | EKS, EFS/EBS, X-Ray or self-hosted OTel | Mix |
| B (Kafka) | EKS, EFS, MSK (Kafka), X-Ray or self-hosted OTel | Mostly Managed |
| C (StatefulSet) | EKS, EFS, X-Ray or self-hosted OTel | Mix |

**Option B - AWS Architecture:**

```
┌─────────────────────────────────────────────────────────────────┐
│                         AWS Region                              │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                      EKS Cluster                         │  │
│  │                                                          │  │
│  │  ┌────────────┐                                         │  │
│  │  │   Tailer   │                                         │  │
│  │  │   Pod      │────┐                                    │  │
│  │  └────────────┘    │                                    │  │
│  │                     │     ┌─────────────────────┐       │  │
│  │                     ├────▶│       MSK           │       │  │
│  │                     │     │   (Kafka Cluster)   │       │  │
│  │                     │     └──────────┬──────────┘       │  │
│  │                     │                │                   │  │
│  │                     │  ┌─────────────┼─────────────┐    │  │
│  │                     │  │             │             │    │  │
│  │                     │  ▼             ▼             ▼    │  │
│  │               ┌──────────┐  ┌──────────┐  ┌──────────┐ │  │
│  │               │ Consumer │  │ Consumer │  │ Consumer │ │  │
│  │               │  Pod #1  │  │  Pod #2  │  │  Pod #N  │ │  │
│  │               └──────────┘  └──────────┘  └──────────┘ │  │
│  │                                                          │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │                                  │
│                              ▼                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              OTel Collector (DaemonSet)                  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │                                  │
│                              ▼                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              AWS X-Ray / ADOT Collector                  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌──────────────────┐
                    │  AWS X-Ray       │
                    │  (or S3 + 查询)  │
                    └──────────────────┘
```

---

## Infrastructure Summary

### Option A: Single Pod (Simplest)

| Environment | Infra Components | Count |
|-------------|------------------|-------|
| **Local** | Docker | 1 |
| | OTel Collector (docker) | 1 |
| | Jaeger (docker) | 1 |
| | **Total** | **3** |
| **AWS** | EKS Cluster | 1 |
| | EFS/EBS (PVC) | 1 |
| | OTel Collector (DaemonSet) | 1 |
| | X-Ray (managed) | 0 (managed) |
| | **Total** | **3-4** |

### Option B: Kafka-Based (Recommended for High Volume)

| Environment | Infra Components | Count |
|-------------|------------------|-------|
| **Local** | Docker | 1 |
| | Zookeeper | 1 |
| | Kafka | 1 |
| | OTel Collector | 1 |
| | Jaeger | 1 |
| | **Total** | **5** |
| **AWS** | EKS Cluster | 1 |
| | EFS (shared storage) | 1 |
| | MSK (Kafka) | 1 |
| | OTel Collector (DaemonSet) | 1 |
| | X-Ray (managed) | 0 (managed) |
| | **Total** | **4-5** |

### Option C: StatefulSet (No Kafka)

| Environment | Infra Components | Count |
|-------------|------------------|-------|
| **Local** | Docker | 1 |
| | NFS Server (or minio/nfs-ganesha) | 1 |
| | OTel Collector | 1 |
| | Jaeger | 1 |
| | **Total** | **4** |
| **AWS** | EKS Cluster | 1 |
| | EFS (PVC) | 1 |
| | OTel Collector | 1 |
| | X-Ray (managed) | 0 (managed) |
| | **Total** | **3-4** |

---

## Recommendation

| Scenario | Recommended Option | Why |
|----------|-------------------|-----|
| < 1,000 logs/sec | A (Single Pod) | Simple, sufficient |
| 1,000 - 10,000 logs/sec | B (Kafka) | True scaling, exactly-once |
| > 10,000 logs/sec | B (Kafka) + Auto-scaling | Handles bursts |
| No Kafka allowed | C (StatefulSet) | Avoids MSK cost |

For AWS, consider using **MSK Serverless** to avoid capacity planning for Kafka.
