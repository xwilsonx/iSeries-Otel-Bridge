# iSeries OTel Bridge - Kubernetes Deployment Guide

## Overview

This application supports two deployment modes:

| Mode | Architecture | Use Case | Scaling |
|------|-------------|----------|---------|
| **Single Pod** | Direct file tailing → OTel | < 1,000 logs/sec | None (1 pod) |
| **Multiple Pod** | Tailer → Kafka → Consumers → OTel | 1,000+ logs/sec | Horizontal (N pods) |

---

## Mode 1: Single Pod Deployment

### Architecture

```
┌─────────────────────────────────────┐
│  Log File (PVC/EFS)                │
│                                     │
│  ┌─────────────────────────────────┐│
│  │  iSeries-OTel-Bridge Pod       ││
│  │  ┌─────────┐  ┌─────────┐      ││
│  │  │ Tailing │→│  Grok   │→     ││
│  │  │ Reader  │  │Process │→      ││
│  │  └─────────┘  └─────────┘      ││
│  │                    ↓            ││
│  │               ┌─────────┐      ││
│  │               │   OTel  │      ││
│  │               │ Writer  │      ││
│  │               └─────────┘      ││
│  └─────────────────────────────────┘│
└─────────────────────────────────────┘
                    │
                    ▼
             ┌─────────────┐
             │   OTel      │
             │  Collector  │
             └─────────────┘
```

### When to Use

- Log volume < 1,000 logs/second
- Simple deployment requirement
- Limited Kubernetes infrastructure
- Cost-sensitive environments

### Prerequisites

| Component | Local | AWS |
|-----------|-------|-----|
| Kubernetes | Docker Desktop, Minikube, K3s | EKS |
| OTel Collector | docker-compose | OTel Operator or DaemonSet |
| OTel Backend | Jaeger all-in-one | X-Ray or self-hosted |
| Storage | hostPath, NFS | EFS, EBS |

### Deployment Steps

**1. Build and push Docker image:**
```bash
# Build
docker build -t <YOUR_REGISTRY>/iseries-otel-bridge:latest .

# Push to ECR (AWS)
aws ecr get-login-password --region <REGION> | docker login --username AWS --password-stdin <YOUR_ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com
docker tag iseries-otel-bridge:latest <YOUR_ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com/iseries-otel-bridge:latest
docker push <YOUR_ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com/iseries-otel-bridge:latest
```

**2. Create namespace and PVC:**
```bash
kubectl create namespace observability

# For AWS EFS
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: iseries-logs-pvc
  namespace: observability
spec:
  accessModes:
    - ReadWriteMany
  storageClassName: efs-sc
  resources:
    requests:
      storage: 10Gi
```

**3. Deploy Single Pod:**
```bash
kubectl apply -f k8s/single-pod-deployment.yaml

# Verify
kubectl get pods -n observability
kubectl logs -n observability deployment/iseries-otel-bridge-single
```

**4. Verify OTel export:**
```bash
# Check logs are being exported
kubectl logs -n observability deployment/iseries-otel-bridge-single | grep "Export"

# Check OTel collector
docker-compose logs otel-collector | grep "iSeries"
```

### Configuration

Edit environment variables in `k8s/single-pod-deployment.yaml`:

| Variable | Default | Description |
|----------|---------|-------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | http://otel-collector:4317 | OTel collector endpoint |
| `OTEL_SERVICE_NAME` | iseries-log-bridge | Service name in OTel |
| `INPUT_FILE_PATH_TAIL` | /var/log/iseries/app.log | Log file path |
| `PARSER_GROK_PATTERN` | (?s)%{TIMESTAMP_ISO8601:timestamp}... | Grok pattern |

### Resource Recommendations

| Setting | Requests | Limits |
|---------|----------|--------|
| Memory | 512Mi | 1Gi |
| CPU | 250m | 500m |

---

## Mode 2: Multiple Pod Deployment (Kafka-Based)

### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Log File (EFS/PVC)                                             │
│                                                                  │
│  ┌──────────────┐                                               │
│  │   Tailer     │──────┐                                         │
│  │   Pod (1x)   │      │     ┌─────────────────────────┐       │
│  └──────────────┘      │     │     Kafka Cluster        │       │
│                       │────▶│  (MSK or self-hosted)     │       │
│                       │     └───────────┬─────────────┘       │
│                       │                 │                       │
│  ┌────────────────────┼─────────────────┼───────────────┐       │
│  │                    ▼                 ▼               │       │
│  │  ┌──────────────┐     ┌──────────────┐  ┌──────────────┐   │
│  │  │  Consumer    │     │  Consumer    │  │  Consumer    │   │
│  │  │  Pod #1      │     │  Pod #2      │  │  Pod #N      │   │
│  │  │  (Grok+OTel)│     │  (Grok+OTel)│  │  (Grok+OTel)│   │
│  │  └──────┬───────┘     └──────┬───────┘  └──────┬───────┘   │
│  │         │                    │                 │           │
│  │         └────────────────────┴─────────────────┘           │
│  │                              │                             │
│  │                              ▼                             │
│  │                     ┌──────────────┐                      │
│  │                     │   OTel       │                      │
│  │                     │  Collector   │                      │
│  │                     └──────────────┘                      │
└──────────────────────────────────────────────────────────────────┘
```

### When to Use

- Log volume > 1,000 logs/second
- Horizontal scaling required
- High availability needed
- Exactly-once processing required

### Prerequisites

| Component | Local | AWS |
|-----------|-------|-----|
| Kubernetes | Docker Desktop, Minikube, K3s | EKS |
| Kafka | docker-compose (single broker) | MSK (recommended) |
| OTel Collector | docker-compose | OTel Operator or DaemonSet |
| OTel Backend | Jaeger all-in-one | X-Ray or self-hosted |
| Storage | hostPath, NFS | EFS |

### Deployment Steps

**1. Deploy Kafka (if not using MSK):**
```bash
# Using Strimzi operator (recommended for production)
kubectl apply -f https://strimzi.io/examples/latest/kafka/kafka-single-node.yaml

# Or using AWS MSK (create via Console or CDK)
```

**2. Create topic:**
```bash
kubectl exec -it <kafka-pod> -- \
  kafka-topics.sh --create \
  --topic iseries-logs \
  --bootstrap-server localhost:9092 \
  --partitions 6 \
  --replication-factor 1
```

**3. Deploy Tailer (single pod):**
```bash
kubectl apply -f k8s/tailer-deployment.yaml

# Verify only 1 tailer pod exists
kubectl get pods -n observability -l role=tailer
```

**4. Deploy Consumers with HPA:**
```bash
kubectl apply -f k8s/deployment.yaml    # Consumer deployment
kubectl apply -f k8s/hpa.yaml          # Horizontal Pod Autoscaler

# Verify
kubectl get pods -n observability -l role=consumer
kubectl get hpa -n observability
```

### Auto-Scaling Configuration

The HPA is configured in `k8s/hpa.yaml`:

```yaml
spec:
  minReplicas: 2      # Start with 2 consumers
  maxReplicas: 10     # Scale up to 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70  # Scale when CPU > 70%
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80  # Scale when Memory > 80%
```

### Monitoring Scaling

```bash
# Watch HPA events
kubectl describe hpa iseries-otel-bridge-consumer-hpa -n observability

# Check current scale
kubectl get hpa -n observability

# View pod distribution
kubectl get pods -n observability -o wide
```

### Kafka Consumer Group

```bash
# Check consumer lag
kubectl exec -it <kafka-pod> -- \
  kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group iseries-log-consumers \
  --describe
```

---

## Unified Deployment

The `k8s/unified-deployment.yaml` supports all modes via `ROLE` env var:

```bash
ROLE=single    # Direct file tailing (no Kafka)
ROLE=tailer    # File tailing → Kafka
ROLE=consumer  # Kafka → Grok → OTel
```

```bash
# Deploy unified manifest
kubectl apply -f k8s/unified-deployment.yaml

# Set mode via patch (for consumer mode)
kubectl patch deployment iseries-otel-bridge \
  -n observability \
  -p '{"spec":{"template":{"spec":{"containers":[{"name":"bridge","env":[{"name":"ROLE","value":"consumer"}]}]}}}}'
```

---

## Quick Reference

| Command | Single Pod | Multiple Pod |
|---------|------------|--------------|
| Deploy | `kubectl apply -f k8s/single-pod-deployment.yaml` | `kubectl apply -f k8s/tailer-deployment.yaml && kubectl apply -f k8s/deployment.yaml` |
| Scale | N/A (fixed at 1) | `kubectl scale deployment iseries-otel-bridge-consumer --replicas=5` | | Auto-scale | N/A | `kubectl apply -f k8s/hpa.yaml` |
| Check status | `kubectl get pods -n observability -l mode=single` | `kubectl get pods -n observability -l role=consumer` |
| View logs | `kubectl logs -n observability deployment/iseries-otel-bridge-single` | `kubectl logs -n observability deployment/iseries-otel-bridge-consumer` |

---

## Troubleshooting

### Single Pod

**Pod not starting:**
```bash
kubectl describe pod -n observability -l mode=single
kubectl logs -n observability -l mode=single
```

**Logs not being tailed:**
```bash
# Check PVC is mounted
kubectl exec -it <pod-name> -n observability -- ls -la /var/log/iseries/
```

### Multiple Pod

**Consumer pods not scaling:**
```bash
kubectl describe hpa iseries-otel-bridge-consumer-hpa -n observability
kubectl top pods -n observability
```

**Kafka connection issues:**
```bash
# Check Kafka is accessible from pod
kubectl exec -it <consumer-pod> -n observability -- \
  kafka-broker-api-versions.sh --bootstrap-server kafka:29092

# Check topic exists
kubectl exec -it <kafka-pod> -- \
  kafka-topics.sh --list --bootstrap-server localhost:9092
```

**Consumer lag increasing:**
```bash
# Check consumer group status
kubectl exec -it <kafka-pod> -- \
  kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group iseries-log-consumers \
  --describe
```
