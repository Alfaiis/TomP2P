/*
 * Copyright 2012 Thomas Bocek
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package net.tomp2p.p2p;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureChannelCreator;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.p2p.builder.PutBuilder;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number640;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.rpc.StorageRPC;
import net.tomp2p.storage.Data;
import net.tomp2p.storage.StorageLayer;
import net.tomp2p.utils.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This implements the default indirect replication.
 * 
 * @author Thomas Bocek
 * @author Maxat Pernebayev
 * 
 */
public class ReplicationExecutor implements ResponsibilityListener, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicationExecutor.class);

    private final StorageLayer storage;

    private final StorageRPC storageRPC;

    private final Peer peer;

    private final Replication replicationStorage;
    // default replication for put and add is 6
    private static final int REPLICATION = 6;

    private final ScheduledExecutorService timer;
    private final Random random;

    private final int delayMillis;

    //
    private final ReplicationFactor replicationFactor;
    
    private final ReplicationSender replicationSender;
    
    private ScheduledFuture<?> scheduledFuture;

    /**
     * Constructor for the default indirect replication.
     * 
     * @param peer
     *            The peer
     */
    public ReplicationExecutor(final Peer peer, final ReplicationFactor replicationFactor,
            ReplicationSender replicationSender, final Random random, final ScheduledExecutorService timer, final int delayMillis) {
        this.peer = peer;
        this.storage = peer.getPeerBean().storage();
        this.storageRPC = peer.getStoreRPC();
        this.replicationStorage = peer.getPeerBean().replicationStorage();
        replicationStorage.addResponsibilityListener(this);
        replicationStorage.setReplicationFactor(REPLICATION);
        this.random = random;
        this.timer = timer;
        this.delayMillis = delayMillis;
        this.replicationFactor = replicationFactor;
        this.replicationSender = replicationSender;
    }

    public void init(int intervalMillis) {
    	scheduledFuture = timer.scheduleAtFixedRate(this, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void otherResponsible(final Number160 locationKey, final PeerAddress other, final boolean delayed) {
        LOG.debug("Other peer {} is responsible for {}. I'm {}", other, locationKey, storageRPC.peerBean()
                .serverPeerAddress());
        if (!delayed) {
            Number640 min = new Number640(locationKey, Number160.ZERO, Number160.ZERO, Number160.ZERO);
            Number640 max = new Number640(locationKey, Number160.MAX_VALUE, Number160.MAX_VALUE,
                    Number160.MAX_VALUE);
            final Map<Number640, Data> dataMap = storage.get(min, max, -1, true);
            replicationSender.sendDirect(other, locationKey, dataMap);
            LOG.debug("transfer from {} to {} for key {}", storageRPC.peerBean().serverPeerAddress(), other,
                    locationKey);
        } else {
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    otherResponsible(locationKey, other, false);
                }
            }, random.nextInt(delayMillis), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void meResponsible(final Number160 locationKey) {
        LOG.debug("I ({}) now responsible for {}", storageRPC.peerBean().serverPeerAddress(), locationKey);
        synchronizeData(locationKey);
    }

    @Override
    public void run() {
        // we get called every x seconds for content we are responsible for. So
        // we need to make sure that there are enough copies. The easy way is to
        // publish it again... The good way is to do a diff
        Collection<Number160> locationKeys = storage.findContentForResponsiblePeerID(peer.getPeerID());

        for (Number160 locationKey : locationKeys) {
            synchronizeData(locationKey);
        }
        // recalculate replication factor
        int replicationFactor = ReplicationExecutor.this.replicationFactor.factor();
        peer.getPeerBean().replicationStorage().setReplicationFactor(replicationFactor);
    }

    /**
     * Get the data that I'm responsible for and make sure that there are enough replicas.
     * 
     * @param locationKey
     *            The location key.
     */
    private void synchronizeData(final Number160 locationKey) {
        Number640 min = new Number640(locationKey, Number160.ZERO, Number160.ZERO, Number160.ZERO);
        Number640 max = new Number640(locationKey, Number160.MAX_VALUE, Number160.MAX_VALUE,
                Number160.MAX_VALUE);
        final Map<Number640, Data> dataMap = storage.get(min, max, -1, true);
        List<PeerAddress> closePeers = send(locationKey, dataMap);
        LOG.debug("[storage refresh] I ({}) restore {} to {}", storageRPC.peerBean().serverPeerAddress(),
                locationKey, closePeers);
    }

    /**
     * If my peer is responsible, I'll issue a put if absent to make sure all replicas are stored.
     * 
     * @param locationKey
     *            The location key
     * @param domainKey
     *            The domain key
     * @param dataMapConverted
     *            The data to store
     * @return The future of the put
     */
    protected List<PeerAddress> send(final Number160 locationKey, final Map<Number640, Data> dataMapConverted) {
        int replicationFactor = replicationStorage.getReplicationFactor() - 1;
        List<PeerAddress> closePeers = new ArrayList<PeerAddress>();
        SortedSet<PeerAddress> sortedSet = peer.getPeerBean().peerMap()
                .closePeers(locationKey, replicationFactor);
        int count = 0;
        for (PeerAddress peerAddress : sortedSet) {
            count++;
            closePeers.add(peerAddress);
            replicationSender.sendDirect(peerAddress, locationKey, dataMapConverted);
            if (count == replicationFactor) {
                break;
            }
        }
        return closePeers;
    }

    public static class DefaultReplicationSender implements ReplicationSender {
        private StorageRPC storageRPC;
        private Peer peer;
 
        @Override
        public void init(Peer peer) {
            this.peer = peer;
            this.storageRPC = peer.getStoreRPC();
        }

        /**
         * If an other peer is responsible, we send this peer our data, so that the other peer can take care of this.
         * 
         * @param other
         *            The other peer
         * @param locationKey
         *            The location key
         * @param domainKey
         *            The domain key
         * @param dataMapConvert
         *            The data to store
         */
        public void sendDirect(final PeerAddress other, final Number160 locationKey,
                final Map<Number640, Data> dataMap) {
            FutureChannelCreator futureChannelCreator = peer.getConnectionBean().reservation().create(0, 1);
            futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
                @Override
                public void operationComplete(final FutureChannelCreator future) throws Exception {
                    if (future.isSuccess()) {
                        PutBuilder putBuilder = new PutBuilder(peer, locationKey);
                        putBuilder.setDataMap(dataMap);
                        FutureResponse futureResponse = storageRPC.put(other, putBuilder,
                                future.getChannelCreator());
                        Utils.addReleaseListener(future.getChannelCreator(), futureResponse);
                        peer.notifyAutomaticFutures(futureResponse);
                    } else {
                        if (LOG.isErrorEnabled()) {
                            LOG.error("otherResponsible failed " + future.getFailedReason());
                        }
                    }
                }
            });
        }   
    }
    
    public void shutdown() {
    	if(scheduledFuture!=null) {
    		scheduledFuture.cancel(false);
    	}
    }
}
