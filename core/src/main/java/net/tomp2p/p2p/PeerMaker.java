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

import io.netty.channel.ChannelHandler;
import io.netty.util.concurrent.EventExecutorGroup;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import net.tomp2p.connection.Bindings;
import net.tomp2p.connection.ChannelClientConfiguration;
import net.tomp2p.connection.ChannelServerConficuration;
import net.tomp2p.connection.ConnectionBean;
import net.tomp2p.connection.DefaultSignatureFactory;
import net.tomp2p.connection.PeerBean;
import net.tomp2p.connection.PeerCreator;
import net.tomp2p.connection.PipelineFilter;
import net.tomp2p.connection.Ports;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerMap;
import net.tomp2p.peers.PeerMapConfiguration;
import net.tomp2p.peers.PeerStatusListener;
import net.tomp2p.rpc.BloomfilterFactory;
import net.tomp2p.rpc.BroadcastRPC;
import net.tomp2p.rpc.DefaultBloomfilterFactory;
import net.tomp2p.rpc.DirectDataRPC;
import net.tomp2p.rpc.NeighborRPC;
import net.tomp2p.rpc.PeerExchangeRPC;
import net.tomp2p.rpc.PingRPC;
import net.tomp2p.rpc.QuitRPC;
import net.tomp2p.rpc.StorageRPC;
//import net.tomp2p.rpc.TaskRPC;
import net.tomp2p.rpc.TrackerRPC;
import net.tomp2p.storage.IdentityManagement;
import net.tomp2p.storage.StorageLayer;
import net.tomp2p.storage.StorageMemory;
import net.tomp2p.storage.TrackerStorage;
import net.tomp2p.utils.Pair;
import net.tomp2p.utils.Utils;

/**
 * The maker / builder of a {@link Peer} class.
 * 
 * @author Thomas Bocek
 * 
 */
public class PeerMaker {
	public static final PublicKey EMPTY_PUBLICKEY = new PublicKey() {
		private static final long serialVersionUID = 4041565007522454573L;

		@Override
		public String getFormat() {
			return null;
		}

		@Override
		public byte[] getEncoded() {
			return null;
		}

		@Override
		public String getAlgorithm() {
			return null;
		}
	};

	private static final KeyPair EMPTY_KEYPAIR = new KeyPair(EMPTY_PUBLICKEY, null);
	// if the permits are chosen too high, then we might run into timeouts as we
	// cant handle that many connections
	// withing the time limit
	private static final int MAX_PERMITS_PERMANENT_TCP = 250;
	private static final int MAX_PERMITS_UDP = 250;
	private static final int MAX_PERMITS_TCP = 250;

	// required
	private final Number160 peerId;

	// optional with reasonable defaults

	private KeyPair keyPair = null;

	private int p2pID = -1;

	private int tcpPort = -1;

	private int udpPort = -1;

	private Bindings interfaceBindings = null;
	private Bindings externalBindings = null;

	private PeerMap peerMap = null;

	private Peer masterPeer = null;

	private ChannelServerConficuration channelServerConfiguration = null;

	private ChannelClientConfiguration channelClientConfiguration = null;

	private PeerStatusListener[] peerStatusListeners = null;

	private StorageMemory storage = null;

	private TrackerStorage trackerStorage = null;

	private Boolean behindFirewall = null;

	// private int workerThreads = Runtime.getRuntime().availableProcessors() +
	// 1;

	// private File fileMessageLogger = null;

	// private ConnectionConfiguration configuration = null;

	// private StorageGeneric storage;

	private BroadcastHandler broadcastHandler;

	private BloomfilterFactory bloomfilterFactory;

	private ScheduledExecutorService scheduledExecutorService = null;

	private MaintenanceTask maintenanceTask = null;

	private ReplicationExecutor replicationExecutor = null;

	private List<AutomaticFuture> automaticFutures = null;

	private Random random = null;
	private int delayMillis = -1;
	private int intervalMillis = -1;
	private int storageIntervalMillis = -1;

	private ReplicationFactor replicationFactor = null;

	private ReplicationSender replicationSender = null;

	private List<PeerInit> toInitialize = new ArrayList<PeerInit>(1);

	// private ReplicationExecutor replicationExecutor;

	// max, message size to transmit
	// private int maxMessageSize = 2 * 1024 * 1024;

	// PeerMap
	// private int bagSize = 2;

	// private int cacheTimeoutMillis = 60 * 1000;

	// private int maxNrBeforeExclude = 2;

	// private int[] waitingTimeBetweenNodeMaintenenceSeconds = { 5, 10, 20, 40,
	// 80, 160 };

	// private int cacheSize = 100;

	// enable / disable
	private boolean enableHandShakeRPC = true;
	private boolean enableStorageRPC = true;
	private boolean enableNeighborRPC = true;
	private boolean enableQuitRPC = true;
	private boolean enablePeerExchangeRPC = true;
	private boolean enableDirectDataRPC = true;
	private boolean enableTrackerRPC = true;
	private boolean enableTaskRPC = true;
	private boolean enableSynchronizationRPC = true;

	// P2P
	private boolean enableRouting = true;
	private boolean enableDHT = true;
	private boolean enableTracker = true;
	private boolean enableTask = true;
	private boolean enableMaintenance = true;
	private boolean enableIndirectReplication = false;
	private boolean enableBroadcast = true;

	// private Random rnd;

	/**
	 * Creates a peermaker with the peer ID and an empty key pair.
	 * 
	 * @param peerId
	 *            The peer Id
	 */
	public PeerMaker(final Number160 peerId) {
		this.peerId = peerId;
	}

	/**
	 * Creates a peermaker with the key pair and generates out of this key pair
	 * the peer ID.
	 * 
	 * @param keyPair
	 *            The public private key
	 */
	public PeerMaker(final KeyPair keyPair) {
		this.peerId = Utils.makeSHAHash(keyPair.getPublic().getEncoded());
		this.keyPair = keyPair;
	}

	/**
	 * Create a peer and start to listen for incoming connections.
	 * 
	 * @return The peer that can operate in the P2P network.
	 * @throws IOException .
	 */
	public Peer makeAndListen() throws IOException {

		if (behindFirewall == null) {
			behindFirewall = false;
		}

		if (channelServerConfiguration == null) {
			channelServerConfiguration = createDefaultChannelServerConfiguration();
		}
		if (channelClientConfiguration == null) {
			channelClientConfiguration = createDefaultChannelClientConfiguration();
		}
		if (keyPair == null) {
			keyPair = EMPTY_KEYPAIR;
		}
		if (p2pID == -1) {
			p2pID = 1;
		}
		if (tcpPort == -1) {
			tcpPort = Ports.DEFAULT_PORT;
		}
		if (udpPort == -1) {
			udpPort = Ports.DEFAULT_PORT;
		}
		channelServerConfiguration.ports(new Ports(tcpPort, udpPort));
		if (interfaceBindings == null) {
			interfaceBindings = new Bindings();
		}
		channelServerConfiguration.interfaceBindings(interfaceBindings);
		if (externalBindings == null) {
			externalBindings = new Bindings();
		}
		channelClientConfiguration.externalBindings(externalBindings);
		if (peerMap == null) {
			peerMap = new PeerMap(new PeerMapConfiguration(peerId));
		}

		if (storage == null) {
			storage = new StorageMemory();
		}

		if (storageIntervalMillis == -1) {
			storageIntervalMillis = 60 * 1000;
		}

		if (peerStatusListeners == null) {
			peerStatusListeners = new PeerStatusListener[] { peerMap };
		}

		if (masterPeer == null && scheduledExecutorService == null) {
			scheduledExecutorService = Executors.newScheduledThreadPool(1);
		}

		final PeerCreator peerCreator;
		if (masterPeer != null) {
			peerCreator = new PeerCreator(masterPeer.peerCreator(), peerId, keyPair);
		} else {
			peerCreator = new PeerCreator(p2pID, peerId, keyPair, channelServerConfiguration,
			        channelClientConfiguration, peerStatusListeners, scheduledExecutorService);
		}

		final Peer peer = new Peer(p2pID, peerId, peerCreator);

		PeerBean peerBean = peerCreator.peerBean();
		ConnectionBean connectionBean = peerCreator.connectionBean();

		peerBean.peerMap(peerMap);
		peerBean.keyPair(keyPair);
		StorageLayer sl = new StorageLayer(storage);
		peerBean.storage(sl);
		sl.init(connectionBean.timer(), storageIntervalMillis);

		if (trackerStorage == null) {
			trackerStorage = new TrackerStorage(new IdentityManagement(peerBean.serverPeerAddress()), 300,
			        peerBean.getReplicationTracker(), new Maintenance());
		}

		peerBean.trackerStorage(trackerStorage);

		if (bloomfilterFactory == null) {
			peerBean.bloomfilterFactory(new DefaultBloomfilterFactory());
		}

		if (broadcastHandler == null) {
			broadcastHandler = new DefaultBroadcastHandler(peer, new Random());
		}

		// peerBean.setStorage(getStorage());
		Replication replicationStorage = new Replication(storage, peerBean.serverPeerAddress(), peerMap, 5);
		peerBean.replicationStorage(replicationStorage);

		// TrackerStorage storageTracker = new
		// TrackerStorage(identityManagement,
		// configuration.getTrackerTimoutSeconds(), peerBean, maintenance);
		// peerBean.setTrackerStorage(storageTracker);
		// Replication replicationTracker = new Replication(storageTracker,
		// selfAddress, peerMap, 5);
		// peerBean.setReplicationTracker(replicationTracker);

		// peerMap.addPeerOfflineListener(storageTracker);

		// TaskManager taskManager = new TaskManager(connectionBean,
		// workerThreads);
		// peerBean.setTaskManager(taskManager);

		// IdentityManagement identityManagement = new
		// IdentityManagement(selfAddress);
		// Maintenance maintenance = new Maintenance();

		initRPC(peer, connectionBean, peerBean);
		initP2P(peer, connectionBean, peerBean);

		if (maintenanceTask == null && isEnableMaintenance()) {
			maintenanceTask = new MaintenanceTask();
		}

		if (maintenanceTask != null) {
			maintenanceTask.init(peer, connectionBean.timer());
			maintenanceTask.addMaintainable(peerMap);
		}
		peerBean.maintenanceTask(maintenanceTask);

		if (random == null) {
			random = new Random();
		}
		if (intervalMillis == -1) {
			intervalMillis = 60 * 1000;
		}
		if (delayMillis == -1) {
			delayMillis = 30 * 1000;
		}

		if (replicationFactor == null) {
			replicationFactor = new ReplicationFactor() {
				@Override
				public int factor() {
					// Default is 6 as in the builders
					return 6;
				}

				@Override
				public void init(Peer peer) {
				}
			};
		}
		replicationFactor.init(peer);

		if (replicationSender == null) {
			replicationSender = new ReplicationExecutor.DefaultReplicationSender();
		}
		replicationSender.init(peer);

		// indirect replication
		if (replicationExecutor == null && isEnableIndirectReplication() && isEnableStorageRPC()) {
			replicationExecutor = new ReplicationExecutor(peer, replicationFactor, replicationSender, random,
			        connectionBean.timer(), delayMillis);
		}
		if (replicationExecutor != null) {
			replicationExecutor.init(intervalMillis);
		}
		peerBean.replicationExecutor(replicationExecutor);

		if (automaticFutures != null) {
			peer.setAutomaticFutures(automaticFutures);
		}

		// set the ping builder for the heart beat
		connectionBean.sender().pingBuilder(peer.ping());
		for (PeerInit peerInit : toInitialize) {
			peerInit.init(peer);
		}
		return peer;
	}

	public static ChannelServerConficuration createDefaultChannelServerConfiguration() {
		ChannelServerConficuration channelServerConfiguration = new ChannelServerConficuration();
		channelServerConfiguration.interfaceBindings(new Bindings());
		channelServerConfiguration.ports(new Ports(Ports.DEFAULT_PORT, Ports.DEFAULT_PORT));
		channelServerConfiguration.setBehindFirewall(false);
		channelServerConfiguration.pipelineFilter(new DefaultPipelineFilter());
		channelServerConfiguration.signatureFactory(new DefaultSignatureFactory());
		return channelServerConfiguration;
	}

	public static ChannelClientConfiguration createDefaultChannelClientConfiguration() {
		ChannelClientConfiguration channelClientConfiguration = new ChannelClientConfiguration();
		channelClientConfiguration.externalBindings(new Bindings());
		channelClientConfiguration.maxPermitsPermanentTCP(MAX_PERMITS_PERMANENT_TCP);
		channelClientConfiguration.maxPermitsTCP(MAX_PERMITS_TCP);
		channelClientConfiguration.maxPermitsUDP(MAX_PERMITS_UDP);
		channelClientConfiguration.pipelineFilter(new DefaultPipelineFilter());
		channelClientConfiguration.signatureFactory(new DefaultSignatureFactory());
		return channelClientConfiguration;
	}

	/**
	 * Initialize the RPC communications.
	 * 
	 * @param peer
	 *            The peer where the RPC reference is stored
	 * @param connectionBean
	 *            The connection bean
	 * @param peerBean
	 *            The peer bean
	 */
	private void initRPC(final Peer peer, final ConnectionBean connectionBean, final PeerBean peerBean) {
		// RPC communication
		if (isEnableHandShakeRPC()) {
			PingRPC handshakeRCP = new PingRPC(peerBean, connectionBean);
			peer.setHandshakeRPC(handshakeRCP);
		}

		if (isEnableStorageRPC()) {
			StorageRPC storageRPC = new StorageRPC(peerBean, connectionBean);
			peer.setStorageRPC(storageRPC);
		}

		if (isEnableNeighborRPC()) {
			NeighborRPC neighborRPC = new NeighborRPC(peerBean, connectionBean);
			peer.setNeighborRPC(neighborRPC);
		}

		if (isEnableDirectDataRPC()) {
			DirectDataRPC directDataRPC = new DirectDataRPC(peerBean, connectionBean);
			peer.setDirectDataRPC(directDataRPC);
		}

		if (isEnableQuitRPC()) {
			QuitRPC quitRCP = new QuitRPC(peerBean, connectionBean);
			quitRCP.addPeerStatusListener(peerMap);
			peer.setQuitRPC(quitRCP);
		}

		if (isEnablePeerExchangeRPC()) {
			PeerExchangeRPC peerExchangeRPC = new PeerExchangeRPC(peerBean, connectionBean);
			peer.setPeerExchangeRPC(peerExchangeRPC);
		}

		if (isEnableTrackerRPC()) {
			TrackerRPC trackerRPC = new TrackerRPC(peerBean, connectionBean);
			peer.setTrackerRPC(trackerRPC);
		}

		if (isEnableBroadcast()) {
			BroadcastRPC broadcastRPC = new BroadcastRPC(peerBean, connectionBean, broadcastHandler);
			peer.setBroadcastRPC(broadcastRPC);
		}

	}

	private void initP2P(final Peer peer, final ConnectionBean connectionBean, final PeerBean peerBean) {
		// distributed communication

		if (isEnableRouting() && isEnableNeighborRPC()) {
			DistributedRouting routing = new DistributedRouting(peerBean, peer.getNeighborRPC());
			peer.setDistributedRouting(routing);
		}

		if (isEnableRouting() && isEnableStorageRPC() && isEnableDirectDataRPC()) {
			DistributedHashTable dht = new DistributedHashTable(peer.getDistributedRouting(), peer.getStoreRPC(),
			        peer.getDirectDataRPC(), peer.getQuitRPC());
			peer.setDistributedHashMap(dht);
		}
		/*
		 * if (isEnableRouting() && isEnableTrackerRPC() &&
		 * isEnablePeerExchangeRPC()) { DistributedTracker tracker = new
		 * DistributedTracker(peerBean, peer.getDistributedRouting(),
		 * peer.getTrackerRPC(), peer.getPeerExchangeRPC());
		 * peer.setDistributedTracker(tracker); } if (isEnableTaskRPC() &&
		 * isEnableTask() && isEnableRouting()) { // the task manager needs to
		 * use the rpc to send the result back. //TODO: enable again
		 * //peerBean.getTaskManager().init(peer.getTaskRPC()); //AsyncTask
		 * asyncTask = new AsyncTask(peer.getTaskRPC(),
		 * connectionBean.getScheduler(), peerBean);
		 * //peer.setAsyncTask(asyncTask);
		 * //peerBean.getTaskManager().addListener(asyncTask);
		 * //connectionBean.getScheduler().startTracking(peer.getTaskRPC(), //
		 * connectionBean.getConnectionReservation()); //DistributedTask
		 * distributedTask = new DistributedTask(peer.getDistributedRouting(),
		 * peer.getAsyncTask()); //peer.setDistributedTask(distributedTask); }
		 * // maintenance if (isEnableMaintenance()) { //TODO: enable again
		 * //connectionHandler // .getConnectionBean() // .getScheduler() //
		 * .startMaintainance(peerBean.getPeerMap(), peer.getHandshakeRPC(), //
		 * connectionBean.getConnectionReservation(), 5); }
		 */
	}

	public Number160 peerId() {
		return peerId;
	}

	public KeyPair keyPair() {
		return keyPair;
	}

	public PeerMaker keyPair(KeyPair keyPair) {
		this.keyPair = keyPair;
		return this;
	}

	public int p2pId() {
		return p2pID;
	}

	public PeerMaker p2pId(int p2pID) {
		this.p2pID = p2pID;
		return this;
	}

	public int tcpPort() {
		return tcpPort;
	}

	public PeerMaker tcpPort(int tcpPort) {
		this.tcpPort = tcpPort;
		return this;
	}

	public int udpPort() {
		return udpPort;
	}

	public PeerMaker udpPort(int udpPort) {
		this.udpPort = udpPort;
		return this;
	}

	public PeerMaker ports(int port) {
		this.udpPort = port;
		this.tcpPort = port;
		return this;
	}

	public PeerMaker bindings(Bindings bindings) {
		this.interfaceBindings = bindings;
		this.externalBindings = bindings;
		return this;
	}

	public Bindings interfaceBindings() {
		return interfaceBindings;
	}

	public PeerMaker interfaceBindings(Bindings interfaceBindings) {
		this.interfaceBindings = interfaceBindings;
		return this;
	}

	public Bindings externalBindings() {
		return externalBindings;
	}

	public PeerMaker externalBindings(Bindings externalBindings) {
		this.externalBindings = externalBindings;
		return this;
	}

	public PeerMap peerMap() {
		return peerMap;
	}

	public PeerMaker peerMap(PeerMap peerMap) {
		this.peerMap = peerMap;
		return this;
	}

	public Peer masterPeer() {
		return masterPeer;
	}

	public PeerMaker masterPeer(Peer masterPeer) {
		this.masterPeer = masterPeer;
		return this;
	}

	public ChannelServerConficuration channelServerConfiguration() {
		return channelServerConfiguration;
	}

	public PeerMaker channelServerConfiguration(ChannelServerConficuration channelServerConfiguration) {
		this.channelServerConfiguration = channelServerConfiguration;
		return this;
	}

	public ChannelClientConfiguration channelClientConfiguration() {
		return channelClientConfiguration;
	}

	public PeerMaker channelClientConfiguration(ChannelClientConfiguration channelClientConfiguration) {
		this.channelClientConfiguration = channelClientConfiguration;
		return this;
	}

	public PeerStatusListener[] peerStatusListeners() {
		return peerStatusListeners;
	}

	public PeerMaker peerStatusListeners(PeerStatusListener[] peerStatusListeners) {
		this.peerStatusListeners = peerStatusListeners;
		return this;
	}

	public BroadcastHandler broadcastHandler() {
		return broadcastHandler;
	}

	public PeerMaker broadcastHandler(BroadcastHandler broadcastHandler) {
		this.broadcastHandler = broadcastHandler;
		return this;
	}

	public BloomfilterFactory bloomfilterFactory() {
		return bloomfilterFactory;
	}

	public PeerMaker bloomfilterFactory(BloomfilterFactory bloomfilterFactory) {
		this.bloomfilterFactory = bloomfilterFactory;
		return this;
	}

	public MaintenanceTask maintenanceTask() {
		return maintenanceTask;
	}

	public PeerMaker maintenanceTask(MaintenanceTask maintenanceTask) {
		this.maintenanceTask = maintenanceTask;
		return this;
	}

	public ReplicationExecutor replicationExecutor() {
		return replicationExecutor;
	}

	public PeerMaker replicationExecutor(ReplicationExecutor replicationExecutor) {
		this.replicationExecutor = replicationExecutor;
		return this;
	}

	public Random random() {
		return random;
	}

	public PeerMaker random(Random random) {
		this.random = random;
		return this;
	}

	public int delayMillis() {
		return delayMillis;
	}

	public PeerMaker delayMillis(int delayMillis) {
		this.delayMillis = delayMillis;
		return this;
	}

	public int intervalMillis() {
		return intervalMillis;
	}

	public PeerMaker intervalMillis(int intervalMillis) {
		this.intervalMillis = intervalMillis;
		return this;
	}

	public int storageIntervalMillis() {
		return storageIntervalMillis;
	}

	public PeerMaker storageIntervalMillis(int storageIntervalMillis) {
		this.storageIntervalMillis = storageIntervalMillis;
		return this;
	}

	public ReplicationFactor replicationFactor() {
		return replicationFactor;
	}

	public PeerMaker replicationFactor(ReplicationFactor replicationFactor) {
		this.replicationFactor = replicationFactor;
		return this;
	}

	public ReplicationSender replicationSender() {
		return replicationSender;
	}

	public PeerMaker replicationSender(ReplicationSender replicationSender) {
		this.replicationSender = replicationSender;
		return this;
	}

	public PeerMaker init(PeerInit init) {
		toInitialize.add(init);
		return this;
	}

	public PeerMaker init(PeerInit... inits) {
		for (PeerInit init : inits) {
			toInitialize.add(init);
		}
		return this;
	}

	public ScheduledExecutorService timer() {
		return scheduledExecutorService;
	}

	public PeerMaker timer(ScheduledExecutorService scheduledExecutorService) {
		this.scheduledExecutorService = scheduledExecutorService;
		return this;
	}

	// isEnabled methods

	public boolean isEnableHandShakeRPC() {
		return enableHandShakeRPC;
	}

	public PeerMaker setEnableHandShakeRPC(boolean enableHandShakeRPC) {
		this.enableHandShakeRPC = enableHandShakeRPC;
		return this;
	}

	public boolean isEnableStorageRPC() {
		return enableStorageRPC;
	}

	public PeerMaker setEnableStorageRPC(boolean enableStorageRPC) {
		this.enableStorageRPC = enableStorageRPC;
		return this;
	}

	public boolean isEnableNeighborRPC() {
		return enableNeighborRPC;
	}

	public PeerMaker setEnableNeighborRPC(boolean enableNeighborRPC) {
		this.enableNeighborRPC = enableNeighborRPC;
		return this;
	}

	public boolean isEnableQuitRPC() {
		return enableQuitRPC;
	}

	public PeerMaker setEnableQuitRPC(boolean enableQuitRPC) {
		this.enableQuitRPC = enableQuitRPC;
		return this;
	}

	public boolean isEnablePeerExchangeRPC() {
		return enablePeerExchangeRPC;
	}

	public PeerMaker setEnablePeerExchangeRPC(boolean enablePeerExchangeRPC) {
		this.enablePeerExchangeRPC = enablePeerExchangeRPC;
		return this;
	}

	public boolean isEnableDirectDataRPC() {
		return enableDirectDataRPC;
	}

	public PeerMaker setEnableDirectDataRPC(boolean enableDirectDataRPC) {
		this.enableDirectDataRPC = enableDirectDataRPC;
		return this;
	}

	public boolean isEnableTrackerRPC() {
		return enableTrackerRPC;
	}

	public PeerMaker setEnableTrackerRPC(boolean enableTrackerRPC) {
		this.enableTrackerRPC = enableTrackerRPC;
		return this;
	}

	public boolean isEnableTaskRPC() {
		return enableTaskRPC;
	}

	public PeerMaker setEnableTaskRPC(boolean enableTaskRPC) {
		this.enableTaskRPC = enableTaskRPC;
		return this;
	}

	public boolean isEnableSynchronizationRPC() {
		return enableSynchronizationRPC;
	}

	public PeerMaker setEnableSynchronizationRPC(boolean enableQuitRPC) {
		this.enableQuitRPC = enableQuitRPC;
		return this;
	}

	public boolean isEnableRouting() {
		return enableRouting;
	}

	public PeerMaker setEnableRouting(boolean enableRouting) {
		this.enableRouting = enableRouting;
		return this;
	}

	public boolean isEnableDHT() {
		return enableDHT;
	}

	public PeerMaker setEnableDHT(boolean enableDHT) {
		this.enableDHT = enableDHT;
		return this;
	}

	public boolean isEnableTracker() {
		return enableTracker;
	}

	public PeerMaker setEnableTracker(boolean enableTracker) {
		this.enableTracker = enableTracker;
		return this;
	}

	public boolean isEnableTask() {
		return enableTask;
	}

	public PeerMaker setEnableTask(boolean enableTask) {
		this.enableTask = enableTask;
		return this;
	}

	public boolean isEnableMaintenance() {
		return enableMaintenance;
	}

	public PeerMaker setEnableMaintenance(boolean enableMaintenance) {
		this.enableMaintenance = enableMaintenance;
		return this;
	}

	public boolean isEnableIndirectReplication() {
		return enableIndirectReplication;
	}

	public PeerMaker setEnableIndirectReplication(boolean enableIndirectReplication) {
		this.enableIndirectReplication = enableIndirectReplication;
		return this;
	}

	public boolean isEnableBroadcast() {
		return enableBroadcast;
	}

	public PeerMaker setEnableBroadcast(boolean enableBroadcast) {
		this.enableBroadcast = enableBroadcast;
		return this;
	}

	/**
	 * @return True if this peer is behind a firewall and cannot be accessed
	 *         directly
	 */
	public boolean isBehindFirewall() {
		return behindFirewall == null ? false : behindFirewall;
	}

	/**
	 * @param behindFirewall
	 *            Set to true if this peer is behind a firewall and cannot be
	 *            accessed directly
	 * @return This class
	 */
	public PeerMaker setBehindFirewall(final boolean behindFirewall) {
		this.behindFirewall = behindFirewall;
		return this;
	}

	/**
	 * Set peer to be behind a firewall and cannot be accessed directly.
	 * 
	 * @return This class
	 */
	public PeerMaker setBehindFirewall() {
		this.behindFirewall = true;
		return this;
	}

	public PeerMaker addAutomaticFuture(AutomaticFuture automaticFuture) {
		if (automaticFutures == null) {
			automaticFutures = new ArrayList<>(1);
		}
		automaticFutures.add(automaticFuture);
		return this;
	}

	/**
	 * The default filter is no filter, just return the same array.
	 * 
	 * @author Thomas Bocek
	 * 
	 */
	public static class DefaultPipelineFilter implements PipelineFilter {
		@Override
		public Map<String,Pair<EventExecutorGroup,ChannelHandler>> filter(final Map<String, Pair<EventExecutorGroup, ChannelHandler>> channelHandlers, boolean tcp,
		        boolean client) {
			return channelHandlers;
		}
	}

	/**
	 * A pipeline filter that executes handlers in a thread. If you plan to
	 * block within listeners, then use this pipeline.
	 * 
	 * @author Thomas Bocek
	 * 
	 */
	public static class EventExecutorGroupFilter implements PipelineFilter {

		private final EventExecutorGroup eventExecutorGroup;

		public EventExecutorGroupFilter(EventExecutorGroup eventExecutorGroup) {
			this.eventExecutorGroup = eventExecutorGroup;
		}

		@Override
		public Map<String,Pair<EventExecutorGroup,ChannelHandler>> filter(final Map<String, Pair<EventExecutorGroup, ChannelHandler>> channelHandlers, boolean tcp,
		        boolean client) {
			setExecutor("handler", channelHandlers);
			setExecutor("dispatcher", channelHandlers);
			return channelHandlers;
		}

		private void setExecutor(String handlerName,
		        final Map<String, Pair<EventExecutorGroup, ChannelHandler>> channelHandlers) {
			Pair<EventExecutorGroup, ChannelHandler> pair = channelHandlers.get(handlerName);
			if (pair != null) {
				channelHandlers.put(handlerName, pair.element0(eventExecutorGroup));
			}
		}
	}
}
