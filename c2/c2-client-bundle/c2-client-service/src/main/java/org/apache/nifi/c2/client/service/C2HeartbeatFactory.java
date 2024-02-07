/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.c2.client.service;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.nifi.c2.client.C2ClientConfig;
import org.apache.nifi.c2.client.PersistentUuidGenerator;
import org.apache.nifi.c2.client.service.model.RuntimeInfoWrapper;
import org.apache.nifi.c2.protocol.api.AgentInfo;
import org.apache.nifi.c2.protocol.api.AgentManifest;
import org.apache.nifi.c2.protocol.api.AgentRepositories;
import org.apache.nifi.c2.protocol.api.AgentResourceConsumption;
import org.apache.nifi.c2.protocol.api.AgentStatus;
import org.apache.nifi.c2.protocol.api.C2Heartbeat;
import org.apache.nifi.c2.protocol.api.DeviceInfo;
import org.apache.nifi.c2.protocol.api.FlowInfo;
import org.apache.nifi.c2.protocol.api.FlowQueueStatus;
import org.apache.nifi.c2.protocol.api.NetworkInfo;
import org.apache.nifi.c2.protocol.api.SupportedOperation;
import org.apache.nifi.c2.protocol.api.SystemInfo;
import org.apache.nifi.c2.protocol.component.api.RuntimeManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class C2HeartbeatFactory {

    private static final Logger logger = LoggerFactory.getLogger(C2HeartbeatFactory.class);

    private static final String AGENT_IDENTIFIER_FILENAME = "agent-identifier";
    private static final String DEVICE_IDENTIFIER_FILENAME = "device-identifier";

    private final C2ClientConfig clientConfig;
    private final FlowIdHolder flowIdHolder;
    private final ManifestHashProvider manifestHashProvider;

    private String agentId;
    private String deviceId;
    private File confDirectory;

    public C2HeartbeatFactory(C2ClientConfig clientConfig, FlowIdHolder flowIdHolder, ManifestHashProvider manifestHashProvider) {
        this.clientConfig = clientConfig;
        this.flowIdHolder = flowIdHolder;
        this.manifestHashProvider = manifestHashProvider;
    }

    public C2Heartbeat create(RuntimeInfoWrapper runtimeInfoWrapper) {
        C2Heartbeat heartbeat = new C2Heartbeat();

        heartbeat.setAgentInfo(getAgentInfo(runtimeInfoWrapper.getAgentRepositories(), runtimeInfoWrapper.getManifest()));
        heartbeat.setDeviceInfo(generateDeviceInfo());
        heartbeat.setFlowInfo(getFlowInfo(runtimeInfoWrapper.getQueueStatus()));
        heartbeat.setCreated(System.currentTimeMillis());

        return heartbeat;
    }

    private FlowInfo getFlowInfo(Map<String, FlowQueueStatus> queueStatus) {
        FlowInfo flowInfo = new FlowInfo();
        flowInfo.setQueues(queueStatus);
        Optional.ofNullable(flowIdHolder.getFlowId()).ifPresent(flowInfo::setFlowId);
        return flowInfo;
    }

    private AgentInfo getAgentInfo(AgentRepositories repos, RuntimeManifest manifest) {
        AgentInfo agentInfo = new AgentInfo();
        agentInfo.setAgentClass(clientConfig.getAgentClass());
        agentInfo.setIdentifier(getAgentId());

        AgentStatus agentStatus = new AgentStatus();
        agentStatus.setUptime(ManagementFactory.getRuntimeMXBean().getUptime());
        agentStatus.setRepositories(repos);
        AgentResourceConsumption agentResourceConsumption = new AgentResourceConsumption();
        agentResourceConsumption.setMemoryUsage(Runtime.getRuntime().maxMemory() - Runtime.getRuntime().freeMemory());
        agentStatus.setResourceConsumption(agentResourceConsumption);
        agentInfo.setStatus(agentStatus);
        agentInfo.setAgentManifestHash(manifestHashProvider.calculateManifestHash(manifest.getBundles(), getSupportedOperations(manifest)));

        if (clientConfig.isFullHeartbeat()) {
            agentInfo.setAgentManifest(manifest);
        }

        return agentInfo;
    }

    private Set<SupportedOperation> getSupportedOperations(RuntimeManifest manifest) {
        Set<SupportedOperation> supportedOperations;
        // supported operations has value only in case of minifi, therefore we return empty collection if
        if (manifest instanceof AgentManifest) {
            supportedOperations = ((AgentManifest) manifest).getSupportedOperations();
        } else {
            supportedOperations = Collections.emptySet();
        }
        return supportedOperations;
    }

    private String getAgentId() {
        if (agentId == null) {
            String rawAgentId = clientConfig.getAgentIdentifier();
            if (isNotBlank(rawAgentId)) {
                agentId = rawAgentId.trim();
            } else {
                File idFile = new File(getConfDirectory(), AGENT_IDENTIFIER_FILENAME);
                agentId = new PersistentUuidGenerator(idFile).generate();
            }
        }

        return agentId;
    }

    private DeviceInfo generateDeviceInfo() {
        // Populate DeviceInfo
        final DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setNetworkInfo(generateNetworkInfo());
        deviceInfo.setIdentifier(getDeviceIdentifier(deviceInfo.getNetworkInfo()));
        deviceInfo.setSystemInfo(generateSystemInfo());
        return deviceInfo;
    }

    private NetworkInfo generateNetworkInfo() {
        NetworkInfo networkInfo = new NetworkInfo();
        try {
            // Determine all interfaces
            final Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();

            final Set<NetworkInterface> operationIfaces = new HashSet<>();

            // Determine eligible interfaces
            while (networkInterfaces.hasMoreElements()) {
                final NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (!networkInterface.isLoopback() && networkInterface.isUp()) {
                    operationIfaces.add(networkInterface);
                }
            }
            logger.trace("Have {} interfaces with names {}", operationIfaces.size(),
                operationIfaces.stream()
                    .map(NetworkInterface::getName)
                    .collect(Collectors.toSet())
            );

            if (!operationIfaces.isEmpty()) {
                if (operationIfaces.size() > 1) {
                    logger.debug("Instance has multiple interfaces.  Generated information may be non-deterministic.");
                }

                boolean networkInfoUnset = true;
                for (NetworkInterface networkInterface : operationIfaces) {
                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddress = inetAddresses.nextElement();
                        // IPv4 address is preferred over IPv6 as it provides more readable information for the user
                        if (inetAddress instanceof Inet4Address) {
                            updateNetworkInfo(networkInfo, networkInterface, inetAddress);
                            return networkInfo;
                        }
                        if (networkInfoUnset) {
                            updateNetworkInfo(networkInfo, networkInterface, inetAddress);
                            networkInfoUnset = false;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Network Interface processing failed", e);
        }
        return networkInfo;
    }

    private void updateNetworkInfo(NetworkInfo networkInfo, NetworkInterface networkInterface, InetAddress inetAddress) {
        networkInfo.setDeviceId(networkInterface.getName());
        networkInfo.setHostname(inetAddress.getHostName());
        networkInfo.setIpAddress(inetAddress.getHostAddress());
    }

    private String getDeviceIdentifier(NetworkInfo networkInfo) {
        if (deviceId == null) {
            if (networkInfo.getDeviceId() != null) {
                try {
                    final NetworkInterface netInterface = NetworkInterface.getByName(networkInfo.getDeviceId());
                    byte[] hardwareAddress = netInterface.getHardwareAddress();
                    final StringBuilder macBuilder = new StringBuilder();
                    if (hardwareAddress != null) {
                        for (byte address : hardwareAddress) {
                            macBuilder.append(String.format("%02X", address));
                        }
                    }
                    deviceId = macBuilder.toString();
                } catch (Exception e) {
                    logger.warn("Could not determine device identifier.  Generating a unique ID", e);
                    deviceId = getConfiguredDeviceId();
                }
            } else {
                deviceId = getConfiguredDeviceId();
            }
        }

        return deviceId;
    }

    private String getConfiguredDeviceId() {
        File idFile = new File(getConfDirectory(), DEVICE_IDENTIFIER_FILENAME);
        return new PersistentUuidGenerator(idFile).generate();
    }

    private SystemInfo generateSystemInfo() {
        SystemInfo systemInfo = new SystemInfo();

        OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
        systemInfo.setMachineArch(osMXBean.getArch());
        systemInfo.setOperatingSystem(osMXBean.getName());

        double systemLoadAverage = osMXBean.getSystemLoadAverage();
        systemInfo.setvCores(osMXBean.getAvailableProcessors());

        // getSystemLoadAverage is not available in Windows, so we need to prevent to send invalid data
        if (systemLoadAverage >= 0) {
            systemInfo.setCpuLoadAverage(systemLoadAverage);
        }

        return systemInfo;
    }

    private File getConfDirectory() {
        if (confDirectory == null) {
            String configDirectoryName = clientConfig.getConfDirectory();
            File configDirectory = new File(configDirectoryName);
            if (!configDirectory.exists() || !configDirectory.isDirectory()) {
                throw new IllegalStateException("Specified conf directory " + configDirectoryName + " does not exist or is not a directory.");
            }

            confDirectory = configDirectory;
        }

        return confDirectory;
    }

}
