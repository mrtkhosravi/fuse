/*
 * Copyright 2010 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package org.fusesource.fabric.partition;

import com.google.common.base.Throwables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.fusesource.fabric.api.jcip.GuardedBy;
import org.fusesource.fabric.api.jcip.ThreadSafe;
import org.fusesource.fabric.api.scr.AbstractComponent;
import org.fusesource.fabric.api.scr.ValidatingReference;
import org.fusesource.fabric.partition.internal.DefaultTaskManager;
import org.fusesource.fabric.partition.internal.WorkManagerWithBalancingPolicy;
import org.fusesource.fabric.partition.internal.WorkManagerWithListener;
import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.collect.Iterables.filter;

/**
 * A {@link ManagedServiceFactory} for creating {@link org.fusesource.fabric.partition.internal.DefaultTaskManager} instances.
 */
@ThreadSafe
@Component(name = "org.fusesource.fabric.partition", description = "Work Manager Factory", configurationFactory = true, policy = ConfigurationPolicy.REQUIRE)
public final class TaskManagerFactory extends AbstractComponent {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskManagerFactory.class);

    private static final String TASK_ID_PROPERTY_NAME = "id";
    private static final String TASK_DEFINITION_PROPERTY_NAME = "task.definition";
    private static final String PARTITIONS_PATH_PROPERTY_NAME = "partitions.path";
    private static final String WORK_BALANCING_POLICY = "balancing.policy";
    private static final String WORKER_TYPE = "worker.type";

    @Reference(referenceInterface = BalancingPolicy.class, cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    private final ConcurrentMap<String, BalancingPolicy> balancingPolicies = new ConcurrentHashMap<String, BalancingPolicy>();
    @Reference(referenceInterface = PartitionListener.class, cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    private final ConcurrentMap<String, PartitionListener> partitionListeners = new ConcurrentHashMap<String, PartitionListener>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();

    @GuardedBy("this") private final ConcurrentMap<String, TaskManager> taskManagers = new ConcurrentHashMap<String, TaskManager>();
    @GuardedBy("this") private final ConcurrentMap<String, Map<String,?>> pendingPids = new ConcurrentHashMap<String, Map<String,?>>();
    @GuardedBy("this") private final Multimap<String, String> waitingOnBalancing = LinkedHashMultimap.create();
    @GuardedBy("this") private final Multimap<String, String> waitingOnListener = LinkedHashMultimap.create();

    @Activate
    void activate(ComponentContext context, Map<String,?> properties) throws ConfigurationException {
        activateInternal(properties);
        activateComponent();
    }

    @Deactivate
    void deactivate(Map<String,?> properties) {
        deactivateComponent();
        deactivateInternal(properties);
    }

    private synchronized void activateInternal(Map<String, ?> properties) throws ConfigurationException {
        validate(properties);
        String s = readString(properties, Constants.SERVICE_PID);
        String taskId = readString(properties, TASK_ID_PROPERTY_NAME);
        String taskDefinition = readString(properties, TASK_DEFINITION_PROPERTY_NAME);
        String partitionsPath = readString(properties, PARTITIONS_PATH_PROPERTY_NAME);
        String policyType = readString(properties, WORK_BALANCING_POLICY);
        String workerType = readString(properties, WORKER_TYPE);

        if (!balancingPolicies.containsKey(policyType)) {
            waitingOnBalancing.put(policyType, s);
            pendingPids.put(s, properties);
            LOGGER.warn("Policy type {} not found. Will resume: {} when policy is made available.", policyType, s);
        } else if (!partitionListeners.containsKey(workerType)) {
            waitingOnListener.put(workerType, s);
            pendingPids.put(s, properties);
            LOGGER.warn("Worker type {} not found. Will resume: {} when worker type is made available.", workerType, s);
        } else {
            BalancingPolicy balancingPolicy = balancingPolicies.get(policyType);
            PartitionListener partitionListener = partitionListeners.get(workerType);

            TaskManager taskManager = new DefaultTaskManager(curator.get(), taskId, taskDefinition, partitionsPath, partitionListener, balancingPolicy);
            TaskManager oldTaskManager = taskManagers.put(s, taskManager);
            if (oldTaskManager != null) {
                oldTaskManager.stop();
            }
            taskManager.start();
        }
    }

    private synchronized void deactivateInternal(Map<String, ?> properties) {
        String s = readString(properties, Constants.SERVICE_PID);
        TaskManager taskManager = taskManagers.remove(s);
        taskManager.stop();
    }

    /**
     * Validates configuration.
     */
    private void validate(Map<String,?> properties) throws ConfigurationException {
        if (properties == null) {
            throw new IllegalArgumentException("Configuration is null");
        } else if (properties.get(TASK_ID_PROPERTY_NAME) == null) {
            throw new ConfigurationException(TASK_ID_PROPERTY_NAME, "Property is required.");
        } else if (properties.get(TASK_DEFINITION_PROPERTY_NAME) == null) {
            throw new ConfigurationException(TASK_DEFINITION_PROPERTY_NAME, "Property is required.");
        } else if (properties.get(PARTITIONS_PATH_PROPERTY_NAME) == null) {
            throw new ConfigurationException(PARTITIONS_PATH_PROPERTY_NAME, "Property is required.");
        } else if (properties.get(WORK_BALANCING_POLICY) == null) {
            throw new ConfigurationException(WORK_BALANCING_POLICY, "Property is required.");
        } else if (properties.get(WORKER_TYPE) == null) {
            throw new ConfigurationException(WORKER_TYPE, "Property is required.");
        }
    }

    /**
     * Reads the specified key as a String from configuration.
     */
    private String readString(Map<String,?> properties, String key) {
        Object obj = properties.get(key);
        if (obj instanceof String) {
            return (String) obj;
        } else {
            return String.valueOf(obj);
        }
    }

    private void stopWorkManagerWithBalancingPolicy(Iterable<TaskManager> workManagers, String balancingPolicyType) {
        for (TaskManager taskManager : filter(workManagers, new WorkManagerWithBalancingPolicy(balancingPolicyType))) {
            taskManager.stop();
        }
    }

    private void stopWorkManagerWithListener(Iterable<TaskManager> workManagers, String listenerType) {
        for (TaskManager taskManager : filter(workManagers, new WorkManagerWithListener(listenerType))) {
            taskManager.stop();
        }
    }

    void bindBalancingPolicy(BalancingPolicy balancingPolicy) {
        balancingPolicies.put(balancingPolicy.getType(), balancingPolicy);
        for (String pid : waitingOnBalancing.get(balancingPolicy.getType())) {
            try {
                activateInternal(pendingPids.remove(pid));
            } catch (ConfigurationException e) {
                Throwables.propagate(e);
            }
        }
    }

    void unbindBalancingPolicy(BalancingPolicy balancingPolicy) {
        balancingPolicies.remove(balancingPolicy.getType());
        stopWorkManagerWithBalancingPolicy(taskManagers.values(), balancingPolicy.getType());
    }

    void bindPartitionListener(PartitionListener partitionListener) {
        partitionListeners.put(partitionListener.getType(), partitionListener);
        for (String pid : waitingOnListener.get(partitionListener.getType())) {
            try {
                activateInternal(pendingPids.remove(pid));
            } catch (ConfigurationException e) {
                Throwables.propagate(e);
            }
        }
    }

    void unbindPartitionListener(PartitionListener partitionListener) {
        partitionListeners.remove(partitionListener.getType());
        stopWorkManagerWithListener(taskManagers.values(), partitionListener.getType());
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.bind(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.unbind(curator);
    }
}
