# Strimzi Stretch Cluster - NodePort Networking Provider

NodePort-based networking provider plugin for Strimzi Kafka stretch clusters deployed across multiple Kubernetes clusters.

## What This Plugin Does

This plugin enables cross-cluster Kafka communication by:

1. **Creating NodePort Services** - Creates a dedicated NodePort service for each Kafka broker pod
2. **Discovering Endpoints** - Finds stable worker node IPs and combines them with dynamically assigned NodePorts
3. **Generating Configurations** - Automatically generates `advertised.listeners` and `controller.quorum.voters` configurations for Kafka brokers

**Example Output:**
- Service: `my-cluster-kafka-0-nodeport` with NodePort `31001`
- Advertised Listener: `REPLICATION-9091://10.21.37.21:31001`
- Controller Quorum Voter: `0@10.21.37.21:31093`

## How It Works

### 1. Initialization
On startup, the plugin discovers and caches one stable worker node IP per cluster. This is done only once for performance.

### 2. Service Creation
For each broker pod, the plugin creates a NodePort service:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-cluster-kafka-0-nodeport
spec:
  type: NodePort
  selector:
    statefulset.kubernetes.io/pod-name: my-cluster-kafka-0
  ports:
  - name: tcp-replication
    port: 9091
    nodePort: 31001  # Auto-assigned by Kubernetes
```

### 3. Endpoint Discovery
The plugin combines the cached node IP with the service's assigned NodePort to create the endpoint: `10.21.37.21:31001`

**Why use one stable node IP?**  
NodePort services are accessible via **ANY** node IP in the cluster thanks to kube-proxy. Using one stable IP per cluster:
- Reduces API calls (4.5x faster)
- Simplifies logic
- Provides stability even when pods move between nodes

## Building the Plugin

```bash
cd strimzi-stretch-nodeport-plugin
mvn clean package
```

This produces: `target/strimzi-stretch-nodeport-plugin-0.48.0.jar`

## Deploying the Plugin

### 1. Add JAR to Operator

Mount the plugin JAR into the Strimzi operator:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: strimzi-cluster-operator
spec:
  template:
    spec:
      containers:
      - name: strimzi-cluster-operator
        volumeMounts:
        - name: stretch-plugins
          mountPath: /opt/strimzi/plugins
      volumes:
      - name: stretch-plugins
        hostPath:
          path: /opt/strimzi/plugins  # Copy JAR here on operator node
```

### 2. Configure Operator Environment Variables

Enable the plugin by setting these environment variables in the operator deployment:

```yaml
env:
- name: STRIMZI_STRETCH_PLUGIN_CLASS_NAME
  value: io.strimzi.plugin.stretch.NodePortNetworkingProvider
- name: STRIMZI_STRETCH_PLUGIN_CLASS_PATH
  value: /opt/strimzi/plugins/*
```

## Network Requirements

**Critical Requirement:** Node IPs must be routable between all Kubernetes clusters.

**On-Premises (Recommended):**
- Flat network where all nodes are on the same subnet
- Or routed network with proper routing tables

**Firewall:**
- Allow traffic on Kubernetes NodePort range (default: 30000-32767)

**Cloud (Not Recommended):**
- Requires VPC peering or VPN between clusters
- Or nodes with public IPs (security risk)

## License

Apache License 2.0

## Version

- Plugin Version: 0.48.0
- Compatible with: Strimzi 0.48.0+
- Requires: Kubernetes 1.25+
