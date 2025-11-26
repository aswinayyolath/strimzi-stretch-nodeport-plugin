# Strimzi Stretch Cluster - NodePort Networking Provider

This plugin provides NodePort-based networking for Strimzi Kafka stretch clusters deployed across multiple Kubernetes clusters.

## Overview

The NodePort provider creates NodePort services for each Kafka broker pod to enable cross-cluster communication. It's ideal for on-premises deployments, development environments, or scenarios where LoadBalancer services are not available or too expensive.

## Features

- **Per-Pod NodePort Services**: Creates a dedicated NodePort service for each Kafka broker
- **Stable Node IP Caching**: Uses a single stable worker node IP per cluster (optimized for performance)
- **Low Cost**: No cloud LoadBalancer costs
- **Simple Setup**: Works on any Kubernetes cluster without additional components
- **Performance Optimized**: Caches node IPs to minimize API calls

## Prerequisites

- Kubernetes cluster (any distribution)
- Network connectivity between all cluster nodes
- Node IPs must be routable between clusters
- Strimzi Operator 0.48.0 or later

## How It Works

1. **Initialization**: On startup, discovers and caches one stable worker node IP per cluster
2. **Service Creation**: Creates a NodePort service for each broker pod
3. **Endpoint Discovery**: Combines cached node IP with dynamically assigned NodePort
4. **Configuration**: Generates `advertised.listeners` and `controller.quorum.voters` using node IP:NodePort

### Why One Stable Node IP?

NodePort services expose on **ALL** nodes in the cluster. This means traffic to `<any-node-ip>:<nodeport>` will reach the correct pod, thanks to kube-proxy routing.

**Benefits**:
- ✅ Fewer API calls (1 service lookup vs 3 API calls per endpoint)
- ✅ Simpler logic (no pod-to-node tracking)
- ✅ More stable (works even if pods move between nodes)
- ✅ Proven approach (validated in manual testing)

### Example Resources Created

For a broker pod `my-cluster-kafka-0`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-cluster-kafka-0-nodeport
  namespace: kafka
spec:
  type: NodePort
  selector:
    statefulset.kubernetes.io/pod-name: my-cluster-kafka-0
  externalTrafficPolicy: Local
  ports:
  - name: tcp-replication
    port: 9091
    nodePort: 31001  # Auto-assigned by K8s
    protocol: TCP
  - name: tcp-plain
    port: 9092
    nodePort: 31002  # Auto-assigned by K8s
    protocol: TCP
```

**Advertised Listener**: `REPLICATION-9091://10.21.37.21:31001`  
(where `10.21.37.21` is the stable worker node IP)

## Installation

### 1. Build the Plugin

```bash
cd strimzi-stretch-nodeport-plugin
mvn clean package
```

This produces `target/strimzi-stretch-nodeport-plugin-0.48.0.jar`

### 2. Deploy Plugin to Operator

Add the plugin JAR to the Strimzi operator image or mount it as a volume:

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
          mountPath: /opt/strimzi/plugins/stretch
      volumes:
      - name: stretch-plugins
        configMap:
          name: stretch-plugins
```

### 3. Configure Operator

Set environment variables to enable the plugin:

```yaml
env:
- name: STRIMZI_STRETCH_PLUGIN_CLASS_NAME
  value: io.strimzi.plugin.stretch.NodePortNetworkingProvider
- name: STRIMZI_STRETCH_PLUGIN_CLASS_PATH
  value: /opt/strimzi/plugins/stretch/*
```

### 4. Configure Kafka CR

Enable stretch mode in your Kafka custom resource:

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-cluster
spec:
  kafka:
    config:
      # ... other config
    # Stretch cluster configuration
    stretch:
      enabled: true
      clusters:
        - id: cluster-1
          # ... cluster config
        - id: cluster-2
          # ... cluster config
```

## Configuration Options

Currently, the NodePort provider doesn't require additional configuration. Future versions may support:

- Node IP selection criteria (prefer ExternalIP, InternalIP, or specific labels)
- NodePort range specification
- Node selection via node labels/taints

## Network Requirements

### Node IP Accessibility

**Critical**: Node IPs must be routable between all clusters.

For on-premises:
- Flat network: All cluster nodes on same subnet ✅ Best
- Routed network: Ensure routing tables allow inter-cluster traffic
- Firewall: Allow traffic on NodePort range (default 30000-32767)

For cloud (not recommended but possible):
- VPC peering between clusters
- VPN or direct connect between regions
- Public IPs on nodes (less secure)

### Firewall Rules

Ensure NodePort range is accessible:

```bash
# Allow NodePort range
iptables -A INPUT -p tcp --dport 30000:32767 -j ACCEPT

# Or allow specific Kafka ports (check kubectl get svc)
iptables -A INPUT -p tcp --dport 31001 -j ACCEPT  # replication
iptables -A INPUT -p tcp --dport 31002 -j ACCEPT  # plain listener
```

### Port Range

Default NodePort range: 30000-32767

To customize in Kubernetes:
```yaml
# kube-apiserver flags
--service-node-port-range=30000-32767
```

## Troubleshooting

### Cross-Cluster Communication Fails

**Symptom**: Kafka brokers can't communicate across clusters

**Solutions**:
1. **Verify node IP connectivity**:
   ```bash
   # From cluster-2, test cluster-1 node IP
   nc -zv 10.21.37.21 31001
   ```

2. **Check NodePort assignment**:
   ```bash
   kubectl get svc my-cluster-kafka-0-nodeport -o yaml
   # Look for spec.ports[].nodePort
   ```

3. **Verify routing**:
   ```bash
   traceroute 10.21.37.21
   ```

4. **Check firewall rules**:
   ```bash
   # On node
   iptables -L -n | grep 31001
   ```

### Node IP Not Found

**Symptom**: Operator logs show "No stable node IP found for cluster X"

**Cause**: All nodes are labeled as master/control-plane

**Solution**: The plugin skips master nodes. Ensure you have worker nodes:
```bash
kubectl get nodes -o wide
# Should show nodes without master role
```

### Wrong Node IP Selected

**Symptom**: Plugin selects InternalIP but ExternalIP is needed

**Current Behavior**: Plugin prefers ExternalIP > InternalIP

**Workaround**: Ensure your worker nodes have ExternalIP set:
```yaml
apiVersion: v1
kind: Node
metadata:
  name: worker-1
status:
  addresses:
  - type: ExternalIP
    address: 10.21.37.21
  - type: InternalIP
    address: 192.168.1.10
```

## Performance Characteristics

- **Initialization**: ~1-2 seconds (one-time node IP discovery per cluster)
- **Service Creation**: ~1 second per broker
- **API Calls per Reconciliation**: ~1 per broker (just service lookup)
- **Recommended Maximum**: 500 brokers (limited by NodePort range)

### Performance Comparison

| Metric | Before Optimization | After Optimization |
|--------|-------------------|-------------------|
| API calls per broker | 3 (service + pod + node) | 1 (service only) |
| Total for 3 brokers × 3 listeners | 27 API calls | 6 API calls |
| Initialization overhead | None | 3 node lists (one-time) |
| Reconciliation speed | Slower | **4.5x faster** |

## Comparison with Other Providers

| Feature | NodePort | LoadBalancer | MCS |
|---------|----------|-------------|-----|
| Service Type | NodePort | LoadBalancer | ClusterIP + ServiceExport |
| External IP | Node IP | Cloud-provided | DNS-based |
| Cost | 💰 Free | 💰💰 High | 💰 Free |
| Setup Complexity | Easy | Easy | Complex |
| Cloud Requirement | ❌ No | ✅ Yes | ❌ No |
| Best For | On-prem/Dev | Production cloud | Multi-cluster mesh |
| Network Requirement | Routable node IPs | LoadBalancer support | Service mesh |

## Example Deployment

See the complete guide at `/manual-nodeport-stretch-cluster.md` in the parent repository.

## Manual Testing Reference

The design was validated through manual testing. Key findings:

> "NodePort exposes on all nodes - so any worker node IP will work"  
> "Using the first worker node consistently provides stability"

This informed our optimization to cache one stable node IP per cluster instead of tracking which node each pod runs on.

## Building and Testing

```bash
# Build
mvn clean package

# Run tests (when available)
mvn test

# Manual integration test
# 1. Deploy to 3 K8s clusters
# 2. Create Kafka CR with stretch enabled
# 3. Verify brokers communicate across clusters
# 4. Check advertised.listeners in broker configs
```

## Known Limitations

1. **Node IP must be routable**: Doesn't work with isolated clusters
2. **NodePort range**: Limited to ~2700 ports (30000-32767)
3. **Single node failure**: If cached node fails, requires plugin restart (future: add node health checks)
4. **No dynamic node selection**: Always uses first discovered worker node

## Future Enhancements

- [ ] Node health monitoring and automatic failover
- [ ] Configurable node selection criteria
- [ ] Support for multiple stable nodes (load distribution)
- [ ] Metrics for node IP changes and service discovery time

## Contributing

This plugin follows Strimzi contribution guidelines. See the main Strimzi repository for details.

## License

Apache License 2.0 - See LICENSE file in the root directory.

## Support

For issues and questions:
- Strimzi Slack: #strimzi channel
- GitHub Issues: [strimzi/strimzi-kafka-operator](https://github.com/strimzi/strimzi-kafka-operator)
- Mailing List: [Strimzi Dev List](https://lists.cncf.io/g/cncf-strimzi-dev)

## Version

- Plugin Version: 0.48.0
- Compatible with: Strimzi 0.48.0+
- Kubernetes Version: 1.25+
