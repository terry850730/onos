/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.store.intent.impl;

import com.google.common.collect.ImmutableList;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.util.KryoNamespace;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.ControllerNode;
import org.onosproject.cluster.NodeId;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentData;
import org.onosproject.net.intent.IntentEvent;
import org.onosproject.net.intent.IntentState;
import org.onosproject.net.intent.IntentStore;
import org.onosproject.net.intent.IntentStoreDelegate;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.PartitionService;
import org.onosproject.store.AbstractStore;
import org.onosproject.store.cluster.messaging.ClusterCommunicationService;
import org.onosproject.store.ecmap.EventuallyConsistentMap;
import org.onosproject.store.ecmap.EventuallyConsistentMapEvent;
import org.onosproject.store.ecmap.EventuallyConsistentMapImpl;
import org.onosproject.store.ecmap.EventuallyConsistentMapListener;
import org.onosproject.store.impl.MultiValuedTimestamp;
import org.onosproject.store.impl.WallClockTimestamp;
import org.onosproject.store.serializers.KryoNamespaces;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.onosproject.net.intent.IntentState.*;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Manages inventory of Intents in a distributed data store that uses optimistic
 * replication and gossip based techniques.
 */
//FIXME we should listen for leadership changes. if the local instance has just
// ...  become a leader, scan the pending map and process those
@Component(immediate = false, enabled = true)
@Service
public class GossipIntentStore
        extends AbstractStore<IntentEvent, IntentStoreDelegate>
        implements IntentStore {

    private final Logger log = getLogger(getClass());

    // Map of intent key => current intent state
    private EventuallyConsistentMap<Key, IntentData> currentMap;

    // Map of intent key => pending intent operation
    private EventuallyConsistentMap<Key, IntentData> pendingMap;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterCommunicationService clusterCommunicator;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PartitionService partitionService;

    @Activate
    public void activate() {
        KryoNamespace.Builder intentSerializer = KryoNamespace.newBuilder()
                .register(KryoNamespaces.API)
                .register(IntentData.class)
                .register(MultiValuedTimestamp.class)
                .register(WallClockTimestamp.class);

        currentMap = new EventuallyConsistentMapImpl<>("intent-current",
                                                       clusterService,
                                                       clusterCommunicator,
                                                       intentSerializer,
                                                       new IntentDataLogicalClockManager<>(),
                                                       (key, intentData) -> getPeerNodes(key, intentData));

        pendingMap = new EventuallyConsistentMapImpl<>("intent-pending",
                                                       clusterService,
                                                       clusterCommunicator,
                                                       intentSerializer, // TODO
                                                       new IntentDataClockManager<>(),
                                                       (key, intentData) -> getPeerNodes(key, intentData));

        currentMap.addListener(new InternalCurrentListener());
        pendingMap.addListener(new InternalPendingListener());

        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        currentMap.destroy();
        pendingMap.destroy();

        log.info("Stopped");
    }

    @Override
    public long getIntentCount() {
        return currentMap.size();
    }

    @Override
    public Iterable<Intent> getIntents() {
        return currentMap.values().stream()
                .map(IntentData::intent)
                .collect(Collectors.toList());
    }

    @Override
    public IntentState getIntentState(Key intentKey) {
        IntentData data = currentMap.get(intentKey);
        if (data != null) {
            return data.state();
        }
        return null;
    }

    @Override
    public List<Intent> getInstallableIntents(Key intentKey) {
        IntentData data = currentMap.get(intentKey);
        if (data != null) {
            return data.installables();
        }
        return null;
    }

    private IntentData copyData(IntentData original) {
        if (original == null) {
            return null;
        }
        IntentData result =
                new IntentData(original.intent(), original.state(), original.version());

        if (original.installables() != null) {
            result.setInstallables(original.installables());
        }
        result.setOrigin(original.origin());
        return result;
    }

    /**
     * Determines whether an intent data update is allowed. The update must
     * either have a higher version than the current data, or the state
     * transition between two updates of the same version must be sane.
     *
     * @param currentData existing intent data in the store
     * @param newData new intent data update proposal
     * @return true if we can apply the update, otherwise false
     */
    private boolean isUpdateAcceptable(IntentData currentData, IntentData newData) {

        if (currentData == null) {
            return true;
        } else if (currentData.version().compareTo(newData.version()) < 0) {
            return true;
        } else if (currentData.version().compareTo(newData.version()) > 0) {
            return false;
        }

        // current and new data versions are the same
        IntentState currentState = currentData.state();
        IntentState newState = newData.state();

        switch (newState) {
        case INSTALLING:
            if (currentState == INSTALLING) {
                return false;
            }
            // FALLTHROUGH
        case INSTALLED:
            if (currentState == INSTALLED) {
                return false;
            } else if (currentState == WITHDRAWING || currentState == WITHDRAWN) {
                log.warn("Invalid state transition from {} to {} for intent {}",
                         currentState, newState, newData.key());
                return false;
            }
            return true;

        case WITHDRAWING:
            if (currentState == WITHDRAWING) {
                return false;
            }
            // FALLTHROUGH
        case WITHDRAWN:
            if (currentState == WITHDRAWN) {
                return false;
            } else if (currentState == INSTALLING || currentState == INSTALLED) {
                log.warn("Invalid state transition from {} to {} for intent {}",
                         currentState, newState, newData.key());
                return false;
            }
            return true;


        case FAILED:
            if (currentState == FAILED) {
                return false;
            }
            return true;


        case COMPILING:
        case RECOMPILING:
        case INSTALL_REQ:
        case WITHDRAW_REQ:
        default:
            log.warn("Invalid state {} for intent {}", newState, newData.key());
            return false;
        }
    }

    @Override
    public void write(IntentData newData) {
        IntentData currentData = currentMap.get(newData.key());
        if (isUpdateAcceptable(currentData, newData)) {
            // Only the master is modifying the current state. Therefore assume
            // this always succeeds
            currentMap.put(newData.key(), copyData(newData));

            // if current.put succeeded
            pendingMap.remove(newData.key(), newData);
        } else {
            log.debug("not writing update: current {}, new {}", currentData, newData);
        }
    }

    private Collection<NodeId> getPeerNodes(Key key, IntentData data) {
        NodeId master = partitionService.getLeader(key);
        NodeId origin = (data != null) ? data.origin() : null;
        NodeId me = clusterService.getLocalNode().id();
        boolean isMaster = Objects.equals(master, me);
        boolean isOrigin = Objects.equals(origin, me);
        if (isMaster && isOrigin) {
            return ImmutableList.of(getRandomNode());
        } else if (isMaster) {
            return origin != null ? ImmutableList.of(origin) : ImmutableList.of(getRandomNode());
        } else if (isOrigin) {
            return ImmutableList.of(master);
        } else {
            // FIXME: why are we here? log error?
            return ImmutableList.of(master);
        }
    }

    private NodeId getRandomNode() {
        List<NodeId> nodes = clusterService.getNodes().stream()
                                .map(ControllerNode::id)
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(nodes);
        // FIXME check if self
        // FIXME verify nodes.size() > 0
        return nodes.get(0);
    }

    @Override
    public void batchWrite(Iterable<IntentData> updates) {
        updates.forEach(this::write);
    }

    @Override
    public Intent getIntent(Key key) {
        IntentData data = currentMap.get(key);
        if (data != null) {
            return data.intent();
        }
        return null;
    }

    @Override
    public IntentData getIntentData(Key key) {
        return copyData(currentMap.get(key));
    }

    @Override
    public void addPending(IntentData data) {
        if (data.version() == null) {
            data.setVersion(new WallClockTimestamp());
        }
        data.setOrigin(clusterService.getLocalNode().id());
        pendingMap.put(data.key(), copyData(data));
    }

    @Override
    public boolean isMaster(Key intentKey) {
        return partitionService.isMine(intentKey);
    }

    @Override
    public Iterable<Intent> getPending() {
        return pendingMap.values().stream()
                .map(IntentData::intent)
                .collect(Collectors.toList());
    }

    @Override
    public void purge(Key key) {
        IntentData data = currentMap.get(key);
        if (data.state() == WITHDRAWN || data.state() == FAILED) {
            currentMap.remove(key, data);
        }
    }

    private void notifyDelegateIfNotNull(IntentEvent event) {
        if (event != null) {
            notifyDelegate(event);
        }
    }

    private final class InternalCurrentListener implements
            EventuallyConsistentMapListener<Key, IntentData> {
        @Override
        public void event(
                EventuallyConsistentMapEvent<Key, IntentData> event) {
            if (event.type() == EventuallyConsistentMapEvent.Type.PUT) {
                IntentData intentData = event.value();

                notifyDelegateIfNotNull(IntentEvent.getEvent(intentData));
            }
        }
    }

    private final class InternalPendingListener implements
            EventuallyConsistentMapListener<Key, IntentData> {
        @Override
        public void event(
                EventuallyConsistentMapEvent<Key, IntentData> event) {
            if (event.type() == EventuallyConsistentMapEvent.Type.PUT) {
                // The pending intents map has been updated. If we are master for
                // this intent's partition, notify the Manager that it should do
                // some work.
                if (isMaster(event.value().intent().key())) {
                    if (delegate != null) {
                        log.debug("processing {}", event.key());
                        delegate.process(copyData(event.value()));
                    }
                }

                notifyDelegateIfNotNull(IntentEvent.getEvent(event.value()));
            }
        }
    }

}

