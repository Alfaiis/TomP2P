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

package net.tomp2p.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.tomp2p.peers.Number160;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StorageMemoryReplicationNRoot implements ReplicationStorage {

	private static final Logger LOG = LoggerFactory.getLogger(StorageMemoryReplicationNRoot.class);

	// maps content (locationKey) to (peer id)
	final private Map<Number160, Set<Number160>> responsibilityMap = new ConcurrentHashMap<Number160, Set<Number160>>();

	// maps (peer id) to content (locationKey)
	final private Map<Number160, Set<Number160>> responsibilityMapRev = new ConcurrentHashMap<Number160, Set<Number160>>();

	final private KeyLock<Number160> responsibilityLock = new KeyLock<Number160>();

	@Override
	public Collection<Number160> findPeerIDsForResponsibleContent(Number160 locationKey) {
		Set<Number160> set = responsibilityMap.get(locationKey);
		if (set == null || set.isEmpty()) {
			return Collections.<Number160> emptyList();
		} else {
			return new HashSet<Number160>(set);
		}
	}

	@Override
	public Collection<Number160> findContentForResponsiblePeerID(Number160 peerID) {
		Collection<Number160> contentIDs = responsibilityMapRev.get(peerID);
		if (contentIDs == null) {
			return Collections.<Number160> emptyList();
		} else {
			KeyLock<Number160>.RefCounterLock lock = responsibilityLock.lock(peerID);
			try {
				return new ArrayList<Number160>(contentIDs);
			} finally {
				responsibilityLock.unlock(lock);
			}
		}
	}

	@Override
	public boolean updateResponsibilities(Number160 locationKey, Number160 peerId) {
		Set<Number160> peerIDs = putIfAbsent(locationKey, new HashSet<Number160>());
		boolean isNew = peerIDs.add(peerId);

		if (isNew && LOG.isDebugEnabled()) {
			LOG.debug("Added new replication responsibility of {} for {}", peerId, locationKey);
		}

		// add to the reverse map
		KeyLock<Number160>.RefCounterLock lock1 = responsibilityLock.lock(peerId);
		try {
			Set<Number160> contentIDs = putIfAbsentRev(peerId, new HashSet<Number160>());
			contentIDs.add(locationKey);
		} finally {
			responsibilityLock.unlock(lock1);
		}

		return isNew;
	}

	private Set<Number160> putIfAbsent(Number160 peerId, Set<Number160> hashSet) {
		Set<Number160> contentIDs = ((ConcurrentMap<Number160, Set<Number160>>) responsibilityMap)
				.putIfAbsent(peerId, hashSet);
		return contentIDs == null ? hashSet : contentIDs;
	}

	private Set<Number160> putIfAbsentRev(Number160 peerId, Set<Number160> hashSet) {
		Set<Number160> contentIDs = ((ConcurrentMap<Number160, Set<Number160>>) responsibilityMapRev)
				.putIfAbsent(peerId, hashSet);
		return contentIDs == null ? hashSet : contentIDs;
	}

	@Override
	public void removeResponsibility(Number160 locationKey) {
		Set<Number160> peerIds = responsibilityMap.remove(locationKey);
		if (peerIds == null) {
			return;
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("Removed replication responsiblities for {}.", locationKey);
		}
		for (Number160 peerId : peerIds) {
			KeyLock<Number160>.RefCounterLock lock = responsibilityLock.lock(peerId);
			try {
				removeRevResponsibility(peerId, locationKey);
			} finally {
				responsibilityLock.unlock(lock);
			}
		}
	}

	private void removeRevResponsibility(Number160 peerId, Number160 locationKey) {
		if (peerId == null || locationKey == null) {
			throw new IllegalArgumentException("both keys must not be null");
		}
		Set<Number160> contentIDs = responsibilityMapRev.get(peerId);
		if (contentIDs != null) {
			contentIDs.remove(locationKey);
			if (contentIDs.isEmpty()) {
				responsibilityMapRev.remove(peerId);
			}
		}
	}

	@Override
	public void removeResponsibility(Number160 locationKey, Number160 peerId) {
		Set<Number160> peerIds = responsibilityMap.get(locationKey);
		if (peerIds == null || !peerIds.remove(peerId)) {
			return;
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("Removed replication responsibility of {} for {}", peerId, locationKey);
		}
		KeyLock<Number160>.RefCounterLock lock = responsibilityLock.lock(peerId);
		try {
			removeRevResponsibility(peerId, locationKey);
		} finally {
			responsibilityLock.unlock(lock);
		}
	}

}
