/*******************************************************************************
 * Copyright (c) 2020 Bosch.IO GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Bosch IO.GmbH - initial creation
 ******************************************************************************/
package org.eclipse.californium.scandium;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.californium.elements.util.ClockUtil;
import org.eclipse.californium.elements.util.WipAPI;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.SessionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DTLS cluster connector.
 * 
 * Forwards foreign cid records to other connectors.
 * 
 * @since 2.5
 */
@WipAPI
public class DtlsDynClusterConnector extends DtlsClusterConnector {

	public static final long DISCOVER_INTERVAL_MILLIS = 30000;
	public static final long REFRESH_INTERVAL_MILLIS = 10000;
	public static final long EXPIRES_MILLIS = 20000;
	public static final long TIMER_INTERVAL = 2000;

	private static final Logger LOGGER = LoggerFactory.getLogger(DtlsDynClusterConnector.class);

	private static final byte MAGIC_ID_PING = (byte) 61;

	private static final byte MAGIC_ID_PONG = (byte) 60;

	private final NodesDiscoverer nodesDiscoverer;

	private volatile ScheduledFuture<?> schedule;

	public DtlsDynClusterConnector(DtlsConnectorConfig configuration, ClusterNodesDiscover nodes) {
		super(configuration, nodes.getDiscoverInterface(), null);
		this.nodesDiscoverer = new NodesDiscoverer(nodes);
		this.nodesProvider = this.nodesDiscoverer;
	}

	public DtlsDynClusterConnector(DtlsConnectorConfig configuration, ClusterNodesDiscover nodes,
			SessionCache sessionCache) {
		super(configuration, nodes.getDiscoverInterface(), sessionCache);
		this.nodesDiscoverer = new NodesDiscoverer(nodes);
		this.nodesProvider = this.nodesDiscoverer;
	}

	/**
	 * {@inheritDoc}
	 * 
	 * Creates socket and threads for cluster internal communication.
	 */
	@Override
	protected void init(InetSocketAddress bindAddress, DatagramSocket socket, Integer mtu) throws IOException {
		super.init(bindAddress, socket, mtu);
		schedule = timer.scheduleWithFixedDelay(new Runnable() {

			@Override
			public void run() {
				try {
					nodesDiscoverer.handlerTimer(clusterSocket);
				} catch (Throwable t) {
					LOGGER.error("cluster manager:", t);
				}
			}
		}, TIMER_INTERVAL, TIMER_INTERVAL, TimeUnit.MILLISECONDS);
		startReceiver();
	}

	@Override
	public void stop() {
		if (schedule != null) {
			schedule.cancel(false);
			schedule = null;
		}
		super.stop();
	}

	/**
	 * Receive next cluster internal message.
	 * 
	 * @param clusterPacket cluster internal message
	 * @throws IOException if an io-error occurred.
	 */
	protected boolean processDatagramFromClusterNetwork(DatagramPacket clusterPacket) throws IOException {
		if (!super.processDatagramFromClusterNetwork(clusterPacket)) {
			final byte type = clusterPacket.getData()[clusterPacket.getOffset()];
			InetSocketAddress router = (InetSocketAddress) clusterPacket.getSocketAddress();
			if (type == MAGIC_ID_PING) {
				int foreignNodeId = decodePingPong(clusterPacket);
				nodesDiscoverer.update(router, foreignNodeId);
				LOGGER.warn("cluster-node {}: >update node {} to {}", nodeId, foreignNodeId, router);
				// reset packet size
				clusterPacket.setData(clusterPacket.getData());
				encodePingPong(clusterPacket, MAGIC_ID_PONG, nodeId);
				clusterSocket.send(clusterPacket);
				if (clusterHealth != null) {
					clusterHealth.receivingClusterManagementMessage();
					clusterHealth.sendingClusterManagementMessage();
				}
			} else if (type == MAGIC_ID_PONG) {
				int foreignNodeId = decodePingPong(clusterPacket);
				nodesDiscoverer.update(router, foreignNodeId);
				LOGGER.warn("cluster-node {}: <update node {} to {}", nodeId, foreignNodeId, router);
				if (clusterHealth != null) {
					clusterHealth.receivingClusterManagementMessage();
				}
			}
		}
		return true;
	}

	private static int decodePingPong(DatagramPacket packet) {
		byte[] data = packet.getData();
		int offset = packet.getOffset();
		int nodeId = data[offset + 1] & 0xff;
		nodeId |= (data[offset + 2] & 0xff) << 8;
		nodeId |= (data[offset + 3] & 0xff) << 16;
		nodeId |= (data[offset + 4] & 0xff) << 24;
		return nodeId;
	}

	private static void encodePingPong(DatagramPacket packet, byte type, int nodeId) {
		byte[] data = packet.getData();
		int offset = packet.getOffset();
		data[offset] = type;
		data[offset + 1] = (byte) (nodeId);
		data[offset + 2] = (byte) (nodeId >> 8);
		data[offset + 3] = (byte) (nodeId >> 16);
		data[offset + 4] = (byte) (nodeId >> 24);
		packet.setLength(5);
	}

	public static interface ClusterNodesDiscover {

		InetSocketAddress getDiscoverInterface();

		List<InetSocketAddress> getClusterNodesDiscoverScope();

	}

	private class NodesDiscoverer implements ClusterNodesProvider {

		private final byte[] discoverBuffer = new byte[128];
		private final DatagramPacket discoverPacket = new DatagramPacket(discoverBuffer, discoverBuffer.length);
		private final ClusterNodesDiscover discoverScope;
		private final ConcurrentMap<Integer, Node> nodesById = new ConcurrentHashMap<>();
		private final ConcurrentMap<InetSocketAddress, Node> nodesByAddress = new ConcurrentHashMap<>();
		private final Random rand = new Random(ClockUtil.nanoRealtime());
		private volatile long nextDiscover;

		private NodesDiscoverer(ClusterNodesDiscover discoverScope) {
			this.discoverScope = discoverScope;
		}

		@Override
		public InetSocketAddress getClusterNode(int nodeId) {
			Node node = nodesById.get(nodeId);
			if (node != null) {
				return node.address;
			} else {
				return null;
			}
		}

		@Override
		public boolean available(InetSocketAddress destinationConnector) {
			return nodesByAddress.containsKey(destinationConnector);
		}

		public synchronized void update(InetSocketAddress address, int nodeId) {
			Node iNode = nodesById.get(nodeId);
			if (iNode == null) {
				iNode = new Node(nodeId, address);
				nodesById.put(nodeId, iNode);
			} else {
				iNode.update(address);
			}
			Node aNode = nodesByAddress.put(address, iNode);
			if (aNode != null && aNode != iNode) {
				nodesById.remove(nodeId, aNode);
			}
		}

		public synchronized void remove(Node node) {
			nodesById.remove(node.nodeId, node);
			nodesByAddress.remove(node.address, node);
		}

		public void handlerTimer(DatagramSocket clusterSocket) {
			synchronized (rand) {
				if (clusterSocket != null && !clusterSocket.isClosed()) {
					long now = ClockUtil.nanoRealtime();
					refresh(now, clusterSocket);
					if (!clusterSocket.isClosed() && (nodesById.size() < 2 || nextDiscover - now <= 0)) {
						discover(clusterSocket);
						nextDiscover = ClockUtil.nanoRealtime()
								+ TimeUnit.MILLISECONDS.toNanos(DISCOVER_INTERVAL_MILLIS);
					}
				}
			}
		}

		private void refresh(long now, DatagramSocket clusterSocket) {
			encodePingPong(discoverPacket, MAGIC_ID_PING, nodeId);
			long expireTimeNanos = now - TimeUnit.MILLISECONDS.toNanos(EXPIRES_MILLIS);
			long freshTimeNanos = now - TimeUnit.MILLISECONDS.toNanos(REFRESH_INTERVAL_MILLIS / 2);
			List<Node> nodes = new ArrayList<>();
			for (Node node : nodesById.values()) {
				if (node.nodeId == nodeId) {
					// self
				} else if (node.isBefore(expireTimeNanos)) {
					remove(node);
				} else if (node.isBefore(freshTimeNanos)) {
					nodes.add(node);
				} else {
					LOGGER.debug("cluster-node {}: keep node {} at {}", nodeId, node.nodeId, node.address);
				}
			}
			while (!nodes.isEmpty()) {
				int pos = rand.nextInt(nodes.size());
				Node node = nodes.remove(pos);
				if (!clusterSocket.isClosed()) {
					discoverPacket.setSocketAddress(node.address);
					try {
						clusterSocket.send(discoverPacket);
						LOGGER.info("cluster-node {}: refresh node {} at {}", nodeId, node.nodeId, node.address);
						if (clusterHealth != null) {
							clusterHealth.sendingClusterManagementMessage();
						}
					} catch (IOException e) {
						LOGGER.debug("sending cluster ping failed!", e);
					}
				}
			}
		}

		private void discover(DatagramSocket clusterSocket) {
			encodePingPong(discoverPacket, MAGIC_ID_PING, nodeId);
			InetSocketAddress own = discoverScope.getDiscoverInterface();
			List<InetSocketAddress> scope = discoverScope.getClusterNodesDiscoverScope();
			List<InetSocketAddress> nodes = new ArrayList<>();
			for (InetSocketAddress node : scope) {
				LOGGER.debug("cluster-node {}: discover scope {}", nodeId, node);
				if (!own.equals(node) && !nodesByAddress.containsKey(node)) {
					nodes.add(node);
				}
			}
			while (!nodes.isEmpty()) {
				int pos = rand.nextInt(nodes.size());
				InetSocketAddress node = nodes.remove(pos);
				if (!clusterSocket.isClosed()) {
					discoverPacket.setSocketAddress(node);
					try {
						clusterSocket.send(discoverPacket);
						LOGGER.info("cluster-node {}: discover {}", nodeId, node);
						if (clusterHealth != null) {
							clusterHealth.sendingClusterManagementMessage();
						}
					} catch (IOException e) {
						LOGGER.debug("sending cluster ping failed!", e);
					}
				}
			}
		}
	}

	private static class Node {

		private final int nodeId;
		private InetSocketAddress address;
		private long time;

		private Node(int nodeId, InetSocketAddress address) {
			this.nodeId = nodeId;
			update(address);
		}

		private synchronized void update(InetSocketAddress address) {
			this.address = address;
			this.time = ClockUtil.nanoRealtime();
		}

		private synchronized boolean isBefore(long timeNanos) {
			return timeNanos - time > 0;
		}
	}
}
