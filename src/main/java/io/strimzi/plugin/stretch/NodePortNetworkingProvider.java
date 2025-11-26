/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.plugin.stretch;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.stretch.RemoteResourceOperatorSupplier;
import io.strimzi.operator.cluster.stretch.spi.StretchNetworkingProvider;
import io.strimzi.operator.common.Reconciliation;
import io.vertx.core.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * NodePort-based networking provider for stretch clusters.
 * 
 * Creates NodePort services for cross-cluster communication.
 * Uses node IPs for addressing.
 */
public class NodePortNetworkingProvider implements StretchNetworkingProvider {
    private static final Logger LOGGER = LogManager.getLogger(NodePortNetworkingProvider.class);

    private ResourceOperatorSupplier centralSupplier;
    private RemoteResourceOperatorSupplier remoteResourceOperatorSupplier;
    
    // Cache of stable node IPs per cluster
    // NodePort exposes on ALL nodes, so any worker node IP works - we use a stable one per cluster
    private final Map<String, String> clusterNodeIPs = new HashMap<>();
    
    @Override
    public Future<Void> init(Map<String, String> config, 
                             ResourceOperatorSupplier centralSupplier, 
                             RemoteResourceOperatorSupplier remoteResourceOperatorSupplier) {
        this.centralSupplier = centralSupplier;
        this.remoteResourceOperatorSupplier = remoteResourceOperatorSupplier;
        
        List<Future<Void>> futures = new ArrayList<>();
        
        // Discover and cache one stable node IP for the central cluster
        // We use "central" as a special key for the central cluster
        futures.add(discoverStableNodeIP(centralSupplier, "central")
            .compose(ip -> {
                clusterNodeIPs.put("central", ip);
                return Future.succeededFuture();
            }));
        
        // Discover and cache one stable node IP for each remote cluster
        for (Map.Entry<String, ResourceOperatorSupplier> entry : 
                remoteResourceOperatorSupplier.remoteResourceOperators.entrySet()) {
            String clusterId = entry.getKey();
            ResourceOperatorSupplier supplier = entry.getValue();
            futures.add(discoverStableNodeIP(supplier, clusterId)
                .compose(ip -> {
                    clusterNodeIPs.put(clusterId, ip);
                    return Future.succeededFuture();
                }));
        }
        
        return Future.join(futures)
            .map(v -> {
                LOGGER.info("Initialized NodePort networking provider with stable node IPs: {}", 
                    clusterNodeIPs);
                return null;
            });
    }
    
    /**
     * Discover a stable node IP for a cluster.
     * Prefers ExternalIP over InternalIP.
     * Selects the first worker node found (non-master node).
     * 
     * @param supplier Resource operator supplier for the target cluster
     * @param clusterId Cluster identifier for logging
     * @return Future with the selected node IP
     */
    private Future<String> discoverStableNodeIP(ResourceOperatorSupplier supplier, String clusterId) {
        return supplier.nodeOperator.listAsync(io.strimzi.operator.common.model.Labels.EMPTY)
            .map(nodes -> {
                if (nodes == null || nodes.isEmpty()) {
                    throw new RuntimeException("No nodes found in cluster " + clusterId);
                }
                
                // Find first worker node (skip master/control-plane nodes)
                for (Node node : nodes) {
                    Map<String, String> labels = node.getMetadata().getLabels();
                    if (labels != null) {
                        // Skip master/control-plane nodes
                        if (labels.containsKey("node-role.kubernetes.io/master") ||
                            labels.containsKey("node-role.kubernetes.io/control-plane")) {
                            continue;
                        }
                    }
                    
                    String ip = getNodeAddress(node);
                    if (ip != null) {
                        LOGGER.info("Selected stable node IP {} from node {} in cluster {}", 
                            ip, node.getMetadata().getName(), clusterId);
                        return ip;
                    }
                }
                
                throw new RuntimeException("No suitable worker node with IP found in cluster " + clusterId);
            });
    }

    @Override
    public Future<List<HasMetadata>> createNetworkingResources(Reconciliation reconciliation, 
                                                               String namespace, 
                                                               String podName, 
                                                               String clusterId, 
                                                               Map<String, Integer> ports) {
        
        LOGGER.debug("{}: Creating NodePort resources for pod {} in cluster {}", 
                reconciliation, podName, clusterId);

        // Get the supplier for the target cluster
        ResourceOperatorSupplier supplier = getSupplier(clusterId);
        if (supplier == null) {
            return Future.failedFuture("No supplier found for cluster " + clusterId);
        }

        // Create per-pod NodePort service
        String serviceName = podName + "-nodeport";
        
        List<ServicePort> servicePorts = ports.entrySet().stream()
            .map(entry -> new ServicePortBuilder()
                .withName(entry.getKey())
                .withPort(entry.getValue())
                .withTargetPort(new io.fabric8.kubernetes.api.model.IntOrString(entry.getValue()))
                .withProtocol("TCP")
                .build())
            .collect(Collectors.toList());

        // Selector matches the specific pod
        Map<String, String> selector = new HashMap<>();
        selector.put("statefulset.kubernetes.io/pod-name", podName);

        Service service = new ServiceBuilder()
            .withNewMetadata()
                .withName(serviceName)
                .withNamespace(namespace)
                .addToLabels("app", "strimzi")
                .addToLabels("strimzi.io/cluster", reconciliation.name())
                .addToLabels("strimzi.io/kind", "Kafka")
                .addToLabels("strimzi.io/name", reconciliation.name() + "-kafka")
                .addToAnnotations("strimzi.io/stretch-cluster-id", clusterId)
            .endMetadata()
            .withNewSpec()
                .withType("NodePort")
                .withPorts(servicePorts)
                .withSelector(selector)
                .withExternalTrafficPolicy("Local") // Important for preserving client IP if needed, but mainly for efficiency
            .endSpec()
            .build();

        return supplier.serviceOperations
            .reconcile(reconciliation, namespace, serviceName, service)
            .map(s -> Collections.singletonList((HasMetadata) s));
    }

    @Override
    public Future<String> discoverPodEndpoint(Reconciliation reconciliation, 
                                              String namespace, 
                                              String podName, 
                                              String clusterId, 
                                              String portName) {
        
        String serviceName = podName + "-nodeport";
        ResourceOperatorSupplier supplier = getSupplier(clusterId);
        
        if (supplier == null) {
            return Future.failedFuture("No supplier found for cluster " + clusterId);
        }
        
        // Get cached node IP for this cluster
        // NodePort exposes on ALL nodes, so we can use any worker node's IP
        String nodeIp = clusterNodeIPs.get(clusterId);
        if (nodeIp == null) {
            // Fallback: try "central" if clusterId not found (shouldn't happen after init)
            nodeIp = clusterNodeIPs.get("central");
            if (nodeIp == null) {
                return Future.failedFuture("No stable node IP found for cluster " + clusterId + 
                    ". Plugin may not be properly initialized.");
            }
        }

        final String finalNodeIp = nodeIp;
        return supplier.serviceOperations.getAsync(namespace, serviceName)
            .map(service -> {
                if (service == null) {
                    throw new RuntimeException("Service " + serviceName + " not found in cluster " + clusterId);
                }

                // Find the NodePort assigned to the service
                Integer nodePort = service.getSpec().getPorts().stream()
                    .filter(p -> p.getName().equals(portName))
                    .map(ServicePort::getNodePort)
                    .findFirst()
                    .orElse(null);

                if (nodePort == null) {
                    throw new RuntimeException("NodePort not found for port " + portName + 
                        " in service " + serviceName);
                }

                LOGGER.debug("{}: Discovered NodePort endpoint for pod {} in cluster {}: {}:{}", 
                    reconciliation, podName, clusterId, finalNodeIp, nodePort);
                return finalNodeIp + ":" + nodePort;
            });
    }

    @Override
    public String generateServiceDnsName(String namespace, String serviceName, String clusterId) {
        // NodePort doesn't really use DNS names for addressing across clusters usually,
        // but we can return the local service DNS name as a fallback or placeholder
        return serviceName + "." + namespace + ".svc";
    }

    @Override
    public String generatePodDnsName(String namespace, String serviceName, String podName, String clusterId) {
        // Similar to service DNS, return local pod DNS
        return podName + "." + serviceName + "." + namespace + ".svc";
    }

    @Override
    public Future<String> generateAdvertisedListeners(Reconciliation reconciliation, 
                                                      String namespace, 
                                                      String podName, 
                                                      String clusterId, 
                                                      Map<String, String> listeners) {
        
        List<Future<String>> futures = new ArrayList<>();
        List<String> listenerKeys = new ArrayList<>();

        for (Map.Entry<String, String> entry : listeners.entrySet()) {
            String listenerName = entry.getKey();
            String portName = entry.getValue();
            listenerKeys.add(listenerName);
            
            futures.add(discoverPodEndpoint(reconciliation, namespace, podName, clusterId, portName));
        }

        return Future.join(futures)
            .map(result -> {
                List<String> advertisedListeners = new ArrayList<>();
                for (int i = 0; i < result.size(); i++) {
                    String endpoint = result.resultAt(i);
                    String listenerName = listenerKeys.get(i);
                    advertisedListeners.add(listenerName + "://" + endpoint);
                }
                return String.join(",", advertisedListeners);
            });
    }

    @Override
    public Future<String> generateQuorumVoters(Reconciliation reconciliation, 
                                               String namespace, 
                                               List<ControllerPodInfo> controllerPods, 
                                               String replicationPortName) {
        
        List<Future<String>> futures = new ArrayList<>();
        List<Integer> nodeIds = new ArrayList<>();

        for (ControllerPodInfo info : controllerPods) {
            nodeIds.add(info.nodeId());
            futures.add(discoverPodEndpoint(reconciliation, namespace, info.podName(), info.clusterId(), replicationPortName));
        }

        return Future.join(futures)
            .map(result -> {
                List<String> voters = new ArrayList<>();
                for (int i = 0; i < result.size(); i++) {
                    String endpoint = result.resultAt(i);
                    int nodeId = nodeIds.get(i);
                    voters.add(nodeId + "@" + endpoint);
                }
                return String.join(",", voters);
            });
    }

    @Override
    public Future<Void> deleteNetworkingResources(Reconciliation reconciliation, 
                                                  String namespace, 
                                                  String podName, 
                                                  String clusterId) {
        String serviceName = podName + "-nodeport";
        ResourceOperatorSupplier supplier = getSupplier(clusterId);
        
        if (supplier == null) {
            return Future.succeededFuture(); // Nothing to do if supplier missing
        }

        return supplier.serviceOperations
            .reconcile(reconciliation, namespace, serviceName, null)
            .mapEmpty();
    }

    @Override
    public String getProviderName() {
        return "nodeport";
    }

    private ResourceOperatorSupplier getSupplier(String clusterId) {
        if (remoteResourceOperatorSupplier.remoteResourceOperators.containsKey(clusterId)) {
            return remoteResourceOperatorSupplier.get(clusterId);
        }
        return centralSupplier;
    }

    private String getNodeAddress(Node node) {
        // Prefer ExternalIP, then InternalIP
        String ip = null;
        for (var addr : node.getStatus().getAddresses()) {
            if ("ExternalIP".equals(addr.getType())) {
                return addr.getAddress();
            } else if ("InternalIP".equals(addr.getType()) && ip == null) {
                ip = addr.getAddress();
            }
        }
        return ip;
    }
}
