/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.nacossync.extension.impl;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacossync.cache.SkyWalkerCacheServices;
import com.alibaba.nacossync.constant.ClusterTypeEnum;
import com.alibaba.nacossync.constant.MetricsStatisticsType;
import com.alibaba.nacossync.constant.SkyWalkerConstants;
import com.alibaba.nacossync.extension.SyncService;
import com.alibaba.nacossync.extension.annotation.NacosSyncService;
import com.alibaba.nacossync.extension.event.SpecialSyncEventBus;
import com.alibaba.nacossync.extension.holder.EurekaServerHolder;
import com.alibaba.nacossync.extension.holder.NacosServerHolder;
import com.alibaba.nacossync.monitor.MetricsManager;
import com.alibaba.nacossync.pojo.model.TaskDO;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * eureka
 * 
 * @author paderlol
 * @date: 2018-12-31 16:25
 */
@Slf4j
@NacosSyncService(sourceCluster = ClusterTypeEnum.EUREKA, destinationCluster = ClusterTypeEnum.NACOS)
public class EurekaSyncToNacosServiceImpl implements SyncService {

    @Autowired
    private MetricsManager metricsManager;

    private final EurekaServerHolder eurekaServerHolder;
    private final SkyWalkerCacheServices skyWalkerCacheServices;

    private final NacosServerHolder nacosServerHolder;

    private final SpecialSyncEventBus specialSyncEventBus;

    private final LinkedBlockingQueue<Map<String, List<InstanceInfo>>> registeredInstance = new LinkedBlockingQueue<>();

    @Autowired
    public EurekaSyncToNacosServiceImpl(EurekaServerHolder eurekaServerHolder, SkyWalkerCacheServices skyWalkerCacheServices, NacosServerHolder nacosServerHolder, SpecialSyncEventBus specialSyncEventBus) {
        this.eurekaServerHolder = eurekaServerHolder;
        this.skyWalkerCacheServices = skyWalkerCacheServices;
        this.nacosServerHolder = nacosServerHolder;
        this.specialSyncEventBus = specialSyncEventBus;
    }

    @Override
    public boolean delete(TaskDO taskDO) {

        try {
            specialSyncEventBus.unsubscribe(taskDO);
            NamingService destNamingService = nacosServerHolder.get(taskDO.getDestClusterId(), taskDO.getNameSpace());
            List<Instance> allInstances = destNamingService.getAllInstances(taskDO.getServiceName());
            for (Instance instance : allInstances) {
                if (needDelete(instance.getMetadata(), taskDO)) {
                    destNamingService.deregisterInstance(taskDO.getServiceName(), instance.getIp(), instance.getPort());
                }
            }

        } catch (Exception e) {
            log.error("delete task from eureka to nacos was failed, taskId:{}", taskDO.getTaskId(), e);
            metricsManager.recordError(MetricsStatisticsType.DELETE_ERROR);
            return false;
        }
        return true;
    }

    @Override
    public boolean sync(TaskDO taskDO) {
        NamingService destNamingService = null;
        try {
            EurekaHttpClient eurekaHttpClient = eurekaServerHolder.get(taskDO.getSourceClusterId(), taskDO.getGroupName());
            destNamingService = nacosServerHolder.get(taskDO.getDestClusterId(), taskDO.getGroupName());
            EurekaHttpResponse<Application> eurekaHttpResponse =
                    eurekaHttpClient.getApplication(taskDO.getServiceName());
            if (Objects.requireNonNull(HttpStatus.resolve(eurekaHttpResponse.getStatusCode())).is2xxSuccessful()) {
                Application application = eurekaHttpResponse.getEntity();
                ArrayList<InstanceInfo> objects = new ArrayList<>();
                for (InstanceInfo instanceInfo : application.getInstances()) {
                    if (needSync(instanceInfo.getMetadata())) {
                        if (InstanceInfo.InstanceStatus.UP.equals(instanceInfo.getStatus())) {
                            destNamingService.registerInstance(taskDO.getServiceName(),
                                    buildSyncInstance(instanceInfo, taskDO));
                            objects.add(instanceInfo);
                        } else {
                            destNamingService.deregisterInstance(instanceInfo.getAppName(), instanceInfo.getIPAddr(),
                                    instanceInfo.getPort());
                        }
                    }
                }
                Map<String, List<InstanceInfo>> peek = registeredInstance.peek();
                if (peek == null || peek.get(taskDO.getServiceName()) == null) {
                    HashMap<String, List<InstanceInfo>> objectObjectHashMap = new HashMap<>();
                    objectObjectHashMap.put(taskDO.getServiceName(), objects);
                    registeredInstance.add(objectObjectHashMap);
                }
            } else {
                metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
                throw new RuntimeException("trying to connect to the server failed" + taskDO);
            }
            specialSyncEventBus.subscribe(taskDO, this::sync);
        } catch (Exception e) {
            Map<String, List<InstanceInfo>> poll = registeredInstance.poll();
            if (poll != null && destNamingService != null) {
                List<InstanceInfo> instanceInfos = poll.get(taskDO.getServiceName());
                for (InstanceInfo instanceInfo : instanceInfos) {
                    try {
                        destNamingService.deregisterInstance(taskDO.getServiceName(), instanceInfo.getIPAddr(),
                                instanceInfo.getPort());
                    } catch (Exception e1) {
                        return false;
                    }
                    return true;
                }
            }
            log.error("sync task from eureka to nacos was failed, taskId:{}", taskDO.getTaskId(), e);
            metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
            return false;
        }
        return true;
    }

    private Instance buildSyncInstance(InstanceInfo instance, TaskDO taskDO) {
        Instance temp = new Instance();
        temp.setIp(instance.getIPAddr());
        temp.setPort(instance.getPort());
        temp.setServiceName(instance.getAppName().toLowerCase());
        temp.setHealthy(true);

        Map<String, String> metaData = new HashMap<>();
        metaData.putAll(instance.getMetadata());
        metaData.put(SkyWalkerConstants.DEST_CLUSTERID_KEY, taskDO.getDestClusterId());
        metaData.put(SkyWalkerConstants.SYNC_SOURCE_KEY,
            skyWalkerCacheServices.getClusterType(taskDO.getSourceClusterId()).getCode());
        metaData.put(SkyWalkerConstants.SOURCE_CLUSTERID_KEY, taskDO.getSourceClusterId());
        temp.setMetadata(metaData);
        return temp;
    }

}
