# AWS EKS Deployment Instructions

This guide outlines how to deploy the `iSeries-Otel-Bridge` application to AWS EKS.

## Prerequisites
1. **AWS CLI** configured (`aws configure`)
2. **Docker** installed
3. **kubectl** configured for your EKS cluster
4. **ECR Repository** created

## Step 1: Build & Push Docker Image

```bash
# Set variables
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=123456789012
REPO_NAME=iseries-otel-bridge

# 1. Login to ECR
aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

# 2. Build Image
docker build -t $REPO_NAME .

# 3. Tag Image
docker tag $REPO_NAME:latest $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$REPO_NAME:latest

# 4. Push Image
docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$REPO_NAME:latest
```

## Step 2: Create Kubernetes Namespace (Optional)
```bash
kubectl create namespace observability
```

## Step 3: Apply Deployment

1. **Edit `k8s/deployment.yaml`:**
   - Replace `<YOUR_AWS_ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/iseries-otel-bridge:latest` with your actual ECR URI.
   - Update `OTEL_EXPORTER_OTLP_ENDPOINT` to point to your existing OTel Collector service (e.g., `http://otel-collector.observability.svc.cluster.local:4317`).
   - Ensure the `persistentVolumeClaim` named `iseries-logs-pvc` exists and contains the logs you want to process.

2. **Deploy:**
```bash
kubectl apply -f k8s/deployment.yaml
```

## Step 4: Verify Deployment
```bash
# Check Pod Status
kubectl get pods -n observability -l app=iseries-otel-bridge

# View Logs
kubectl logs -f -l app=iseries-otel-bridge -n observability
```

## Note on Log Access
This deployment assumes the logs are available on a Persistent Volume (PVC) named `iseries-logs-pvc`. This is typical if:
- Another pod writes logs to this PVC.
- The PVC is backed by EFS (Elastic File System) shared across pods.

If your logs are on the **Node filesystem** (daemonset style), change the volume type in `deployment.yaml` to `hostPath`:
```yaml
volumes:
- name: log-volume
  hostPath:
    path: /var/log/iseries
    type: Directory
```
