/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.engine.server.resourcemanager;

import org.apache.seatunnel.engine.common.Constant;
import org.apache.seatunnel.engine.common.runtime.ExecutionMode;
import org.apache.seatunnel.engine.server.resourcemanager.opeartion.ReleaseSlotOperation;
import org.apache.seatunnel.engine.server.resourcemanager.opeartion.ResetResourceOperation;
import org.apache.seatunnel.engine.server.resourcemanager.resource.ResourceProfile;
import org.apache.seatunnel.engine.server.resourcemanager.resource.SlotProfile;
import org.apache.seatunnel.engine.server.resourcemanager.worker.WorkerProfile;
import org.apache.seatunnel.engine.server.utils.NodeEngineUtil;

import com.hazelcast.cluster.Address;
import com.hazelcast.cluster.Member;
import com.hazelcast.internal.services.MembershipServiceEvent;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.spi.impl.operationservice.Operation;
import com.hazelcast.spi.impl.operationservice.impl.InvocationFuture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public abstract class AbstractResourceManager implements ResourceManager {

    private static final long DEFAULT_WORKER_CHECK_INTERVAL = 500;
    private static final ILogger LOGGER = Logger.getLogger(AbstractResourceManager.class);

    protected final ConcurrentMap<Address, WorkerProfile> registerWorker;

    private final NodeEngine nodeEngine;

    private final ExecutionMode mode = ExecutionMode.LOCAL;

    private volatile boolean isRunning = true;

    public AbstractResourceManager(NodeEngine nodeEngine) {
        this.registerWorker = nodeEngine.getHazelcastInstance().getMap(Constant.IMAP_RESOURCE_MANAGER_REGISTER_WORKER);
        this.nodeEngine = nodeEngine;
    }

    @Override
    public void init() {
        checkRegisterWorkerStillAlive();
    }

    private void checkRegisterWorkerStillAlive() {
        if (!registerWorker.isEmpty()) {
            List<Address> aliveWorker = nodeEngine.getClusterService().getMembers().stream().map(Member::getAddress)
                .collect(Collectors.toList());
            List<Address> dead =
                registerWorker.keySet().stream().filter(r -> !aliveWorker.contains(r)).collect(Collectors.toList());
            dead.forEach(registerWorker::remove);
        }
    }

    @Override
    public CompletableFuture<SlotProfile> applyResource(long jobId, ResourceProfile resourceProfile)
        throws NoEnoughResourceException {
        CompletableFuture<SlotProfile> completableFuture = new CompletableFuture<>();
        applyResources(jobId, Collections.singletonList(resourceProfile)).whenComplete((profile, error) -> {
            if (error != null) {
                completableFuture.completeExceptionally(error);
            } else {
                completableFuture.complete(profile.get(0));
            }
        });
        return completableFuture;
    }

    private void waitingWorkerRegister() {
        if (ExecutionMode.LOCAL.equals(mode)) {
            // Local mode, should wait worker(master node) register.
            try {
                while (registerWorker.isEmpty() && isRunning) {
                    LOGGER.info("waiting current worker register to resource manager...");
                    Thread.sleep(DEFAULT_WORKER_CHECK_INTERVAL);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void memberRemoved(MembershipServiceEvent event) {
        LOGGER.severe("Node heartbeat timeout, disconnected for resource manager. " +
            "Node Address: " + event.getMember().getAddress());
        registerWorker.remove(event.getMember().getAddress());
    }

    @Override
    public CompletableFuture<List<SlotProfile>> applyResources(long jobId,
                                                               List<ResourceProfile> resourceProfile)
        throws NoEnoughResourceException {
        waitingWorkerRegister();
        return new ResourceRequestHandler(jobId, resourceProfile, registerWorker, this).request();
    }

    protected boolean supportDynamicWorker() {
        return false;
    }

    /**
     * find new worker in third party resource manager, it returned after worker register successes.
     *
     * @param resourceProfiles the worker should have resource profile list
     */
    protected void findNewWorker(List<ResourceProfile> resourceProfiles) {
        throw new UnsupportedOperationException(
            "Unsupported operation to find new worker in " + this.getClass().getName());
    }

    @Override
    public void close() {
        isRunning = false;
    }

    protected <E> InvocationFuture<E> sendToMember(Operation operation, Address address) {
        return NodeEngineUtil.sendOperationToMemberNode(nodeEngine, operation, address);
    }

    @Override
    public CompletableFuture<Void> releaseResources(long jobId, List<SlotProfile> profiles) {
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (SlotProfile profile : profiles) {
            futures.add(releaseResource(jobId, profile));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((r, e) -> {
            if (e != null) {
                completableFuture.completeExceptionally(e);
            } else {
                completableFuture.complete(null);
            }
        });
        return completableFuture;
    }

    @Override
    public CompletableFuture<Void> releaseResource(long jobId, SlotProfile profile) {
        if (nodeEngine.getClusterService().getMember(profile.getWorker()) != null) {
            return sendToMember(new ReleaseSlotOperation(jobId, profile), profile.getWorker());
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public boolean slotActiveCheck(SlotProfile profile) {
        return registerWorker.values().stream()
            .flatMap(workerProfile -> Arrays.stream(workerProfile.getAssignedSlots()))
            .anyMatch(s -> s.getSlotID() == profile.getSlotID());
    }

    @Override
    public void heartbeat(WorkerProfile workerProfile) {
        if (!registerWorker.containsKey(workerProfile.getAddress())) {
            LOGGER.info("received new worker register: " + workerProfile.getAddress());
            sendToMember(new ResetResourceOperation(), workerProfile.getAddress()).join();
        } else {
            LOGGER.fine("received worker heartbeat from: " + workerProfile.getAddress());
        }
        registerWorker.put(workerProfile.getAddress(), workerProfile);
    }
}
