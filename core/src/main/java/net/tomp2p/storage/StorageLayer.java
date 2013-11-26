/*
 * Copyright 2009 Thomas Bocek
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

import java.security.PublicKey;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;

import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number320;
import net.tomp2p.peers.Number480;
import net.tomp2p.peers.Number640;
import net.tomp2p.rpc.DigestInfo;
import net.tomp2p.rpc.SimpleBloomFilter;
import net.tomp2p.utils.Timings;
import net.tomp2p.utils.Utils;

public class StorageLayer {
    public enum ProtectionEnable {
        ALL, NONE
    };

    public enum ProtectionMode {
        NO_MASTER, MASTER_PUBLIC_KEY
    };

    // The number of PutStatus should never exceed 255.
    public enum PutStatus {
        OK, FAILED_NOT_ABSENT, FAILED_SECURITY, FAILED, VERSION_CONFLICT
    };

    // Hash of public key is always preferred
    private ProtectionMode protectionDomainMode = ProtectionMode.MASTER_PUBLIC_KEY;

    // Domains can generallay be protected
    private ProtectionEnable protectionDomainEnable = ProtectionEnable.ALL;

    // Hash of public key is always preferred
    private ProtectionMode protectionEntryMode = ProtectionMode.MASTER_PUBLIC_KEY;

    // Entries can generallay be protected
    private ProtectionEnable protectionEntryEnable = ProtectionEnable.ALL;

    // stores the domains that cannot be reserved and items can be added by
    // anyone
    final private Collection<Number160> removedDomains = new HashSet<Number160>();

    final private KeyLock<Storage> dataLock = new KeyLock<Storage>();

    final private KeyLock<Number160> dataLock160 = new KeyLock<Number160>();

    final private KeyLock<Number320> dataLock320 = new KeyLock<Number320>();

    final private KeyLock<Number480> dataLock480 = new KeyLock<Number480>();

    final private KeyLock<Number640> dataLock640 = new KeyLock<Number640>();

    final private Storage backend;

    public StorageLayer(Storage backend) {
        this.backend = backend;
    }

    public void setProtection(ProtectionEnable protectionDomainEnable, ProtectionMode protectionDomainMode,
            ProtectionEnable protectionEntryEnable, ProtectionMode protectionEntryMode) {
        setProtectionDomainEnable(protectionDomainEnable);
        setProtectionDomainMode(protectionDomainMode);
        setProtectionEntryEnable(protectionEntryEnable);
        setProtectionEntryMode(protectionEntryMode);
    }

    public void setProtectionDomainMode(ProtectionMode protectionDomainMode) {
        this.protectionDomainMode = protectionDomainMode;
    }

    public ProtectionMode getProtectionDomainMode() {
        return protectionDomainMode;
    }

    public void setProtectionDomainEnable(ProtectionEnable protectionDomainEnable) {
        this.protectionDomainEnable = protectionDomainEnable;
    }

    public ProtectionEnable getProtectionDomainEnable() {
        return protectionDomainEnable;
    }

    public void setProtectionEntryMode(ProtectionMode protectionEntryMode) {
        this.protectionEntryMode = protectionEntryMode;
    }

    public ProtectionMode getProtectionEntryMode() {
        return protectionEntryMode;
    }

    public void setProtectionEntryEnable(ProtectionEnable protectionEntryEnable) {
        this.protectionEntryEnable = protectionEntryEnable;
    }

    public ProtectionEnable getProtectionEntryEnable() {
        return protectionEntryEnable;
    }

    public void removeDomainProtection(Number160 removeDomain) {
        removedDomains.add(removeDomain);
    }

    boolean isDomainRemoved(Number160 domain) {
        return removedDomains.contains(domain);
    }

    public Enum<?> put(final Number640 key, Data newData, PublicKey publicKey, boolean putIfAbsent,
            boolean domainProtection) {
        boolean retVal = false;
        KeyLock<Number640>.RefCounterLock lock = dataLock640.lock(key);
        try {
            if (!securityDomainCheck(key.locationAndDomainKey(), publicKey, domainProtection)) {
                return PutStatus.FAILED;
            }
            boolean contains = backend.contains(key);
            if (putIfAbsent && contains) {
                return PutStatus.FAILED_NOT_ABSENT;
            }
            if (contains) {
                Data oldData = get(key);
                boolean protectEntry = newData.protectedEntry();
                if (!canUpdateEntry(key.getContentKey(), oldData, newData, protectEntry)) {
                    return PutStatus.FAILED_SECURITY;
                }
            }
            retVal = backend.put(key, newData);
            if (retVal) {
                long expiration = newData.expirationMillis();
                // handle timeout
                backend.addTimeout(key, expiration);
            }
        } finally {
            dataLock640.unlock(lock);
        }
        return retVal ? PutStatus.OK : PutStatus.FAILED;
    }

    public Data remove(Number640 key, PublicKey publicKey) {
        // Number480 lockKey = new Number480(locationKey, domainKey, contentKey);
        KeyLock<Number640>.RefCounterLock lock = dataLock640.lock(key);
        try {
            if (!canClaimDomain(key.locationAndDomainKey(), publicKey)) {
                return null;
            }
            Data data = get(key);
            if (data == null) {
                return null;
            }
            if (data.publicKey() == null || data.publicKey().equals(publicKey)) {
                backend.removeTimeout(key);
                backend.removeResponsibility(key.getLocationKey());
                return backend.remove(key);
            }
        } finally {
            dataLock640.unlock(lock);
        }
        return null;
    }

    public Data get(Number640 key) {
        KeyLock<Number640>.RefCounterLock lock = dataLock640.lock(key);
        try {
            return backend.get(key);
        } finally {
            dataLock640.unlock(lock);
        }
    }

    public SortedMap<Number640, Data> get(Number640 from, Number640 to) {
        KeyLock<?>.RefCounterLock lock = findAndLock(from, to);
        try {
            return backend.subMap(from, to);
        } finally {
            lock.unlock();
        }
    }
    
    public NavigableMap<Number640, Data> get() {
        KeyLock<Storage>.RefCounterLock lock = dataLock.lock(backend);
        try {
            return backend.map();
        } finally {
            lock.unlock();
        }
    }
    
    public boolean contains(Number640 key) {
        KeyLock<Number640>.RefCounterLock lock = dataLock640.lock(key);
        try {
            return backend.contains(key);
        } finally {
            dataLock640.unlock(lock);
        }
    }

    public Map<Number640, Data> get(Number640 from, Number640 to,
            SimpleBloomFilter<Number160> keyBloomFilter, SimpleBloomFilter<Number160> contentBloomFilter) {
        KeyLock<?>.RefCounterLock lock = findAndLock(from, to);
        try {
            NavigableMap<Number640, Data> tmp = backend.subMap(from, to);
            Iterator<Map.Entry<Number640, Data>> iterator = tmp.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Number640, Data> entry = iterator.next();
                if (keyBloomFilter != null && !keyBloomFilter.contains(entry.getKey().getContentKey())) {
                    iterator.remove();
                    continue;
                }
                if (contentBloomFilter != null && !contentBloomFilter.contains(entry.getValue().hash())) {
                    iterator.remove();
                }
            }
            return tmp;
        } finally {
            lock.unlock();
        }
    }

    private KeyLock<?>.RefCounterLock findAndLock(Number640 from, Number640 to) {
        if (!from.getLocationKey().equals(to.getLocationKey())) {
            // everything is different, return a 640 lock
            KeyLock<Storage>.RefCounterLock lock = dataLock.lock(backend);
            return lock;
        } else if (!from.getDomainKey().equals(to.getDomainKey())) {
            // location key is the same, rest is different
            KeyLock<Number160>.RefCounterLock lock = dataLock160.lock(from.getLocationKey());
            return lock;
        } else if (!from.getContentKey().equals(to.getContentKey())) {
            // location and domain key are same, rest is different
            KeyLock<Number320>.RefCounterLock lock = dataLock320.lock(from.locationAndDomainKey());
            return lock;
        } else if (!from.getVersionKey().equals(to.getVersionKey())) {
            // location, domain, and content key are the same, rest is different
            KeyLock<Number480>.RefCounterLock lock = dataLock480.lock(from.locationDomainAndContentKey());
            return lock;
        } else {
            KeyLock<Number640>.RefCounterLock lock = dataLock640.lock(from);
            return lock;
        }
    }

    public SortedMap<Number640, Data> remove(Number640 from, Number640 to, PublicKey publicKey) {
        KeyLock<?>.RefCounterLock lock = findAndLock(from, to);
        try {
            Map<Number640, Data> tmp = backend.subMap(from, to);
            Collection<Number320> locationAndDomains = new HashSet<Number320>();
            for (Number640 key : tmp.keySet()) {
                locationAndDomains.add(key.locationAndDomainKey());
            }
            for (Number320 locationAndDomain : locationAndDomains) {
                // fail fast, as soon as we want to remove 1 domain that we cannot, abort
                if (!canClaimDomain(locationAndDomain, publicKey)) {
                    return null;
                }
            }
            SortedMap<Number640, Data> result = backend.remove(from, to);
            for (Map.Entry<Number640, Data> entry : result.entrySet()) {
                Data data = entry.getValue();
                if (data.publicKey() == null || data.publicKey().equals(publicKey)) {
                    backend.removeTimeout(entry.getKey());
                    backend.removeResponsibility((entry.getKey().getLocationKey()));
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void checkTimeout() {
        long time = Timings.currentTimeMillis();
        Collection<Number640> toRemove = backend.subMapTimeout(time);
        if (toRemove.size() > 0) {
            for (Number640 key : toRemove) {
                backend.remove(key);
            }
        }
    }

    public DigestInfo digest(Number640 from, Number640 to) {
        DigestInfo digestInfo = new DigestInfo();
        KeyLock<?>.RefCounterLock lock = findAndLock(from, to);
        try {
            Map<Number640, Data> tmp = backend.subMap(from, to);
            for (Map.Entry<Number640, Data> entry : tmp.entrySet()) {
                digestInfo.put(entry.getKey(), entry.getValue().hash());
            }
            return digestInfo;
        } finally {
            lock.unlock();
        }
    }

    public DigestInfo digest(Number320 locationAndDomainKey, SimpleBloomFilter<Number160> keyBloomFilter,
            SimpleBloomFilter<Number160> contentBloomFilter) {
        DigestInfo digestInfo = new DigestInfo();
        KeyLock<Number320>.RefCounterLock lock = dataLock320.lock(locationAndDomainKey);
        try {
            Number640 from = new Number640(locationAndDomainKey, Number160.ZERO, Number160.ZERO);
            Number640 to = new Number640(locationAndDomainKey, Number160.MAX_VALUE, Number160.MAX_VALUE);
            Map<Number640, Data> tmp = backend.subMap(from, to);
            for (Map.Entry<Number640, Data> entry : tmp.entrySet()) {
                if (keyBloomFilter == null || keyBloomFilter.contains(entry.getKey().getContentKey())) {
                    if (contentBloomFilter == null || contentBloomFilter.contains(entry.getValue().hash())) {
                        digestInfo.put(entry.getKey(), entry.getValue().hash());
                    }
                }
            }
            return digestInfo;
        } finally {
            lock.unlock();
        }
    }

    public DigestInfo digest(Collection<Number640> number640s) {
        DigestInfo digestInfo = new DigestInfo();
        for (Number640 number640 : number640s) {
            KeyLock<Number640>.RefCounterLock lock = dataLock640.lock(number640);
            try {
                if (backend.contains(number640)) {
                    Data data = get(number640);
                    digestInfo.put(number640, data.hash());
                }
            } finally {
                lock.unlock();
            }
        }
        return digestInfo;
    }

    public DigestInfo digest(Number640 key) {
        DigestInfo digestInfo = new DigestInfo();
        KeyLock<Number640>.RefCounterLock lock = dataLock640.lock(key);
        try {
            if (backend.contains(key)) {
                Data data = get(key);
                digestInfo.put(key, data.hash());
            }
        } finally {
            lock.unlock();
        }

        return digestInfo;
    }

    private boolean canClaimDomain(Number320 key, PublicKey publicKey) {
        boolean domainProtectedByOthers = backend.isDomainProtectedByOthers(key, publicKey);
        boolean domainOverridableByMe = foreceOverrideDomain(key.getDomainKey(), publicKey);
        return !domainProtectedByOthers || domainOverridableByMe;
    }

    private boolean canProtectDomain(Number160 domainKey, PublicKey publicKey) {
        if (isDomainRemoved(domainKey)) {
            return false;
        }
        if (getProtectionDomainEnable() == ProtectionEnable.ALL) {
            return true;
        } else if (getProtectionDomainEnable() == ProtectionEnable.NONE) {
            // only if we have the master key
            return foreceOverrideDomain(domainKey, publicKey);
        }
        return false;
    }

    private boolean securityDomainCheck(Number320 key, PublicKey publicKey, boolean domainProtection) {
        boolean domainProtectedByOthers = backend.isDomainProtectedByOthers(key, publicKey);
        // I dont want to claim the domain
        if (!domainProtection) {
            // returns true if the domain is not protceted by others, otherwise
            // false if the domain is protected
            return !domainProtectedByOthers;
        } else {
            if (canClaimDomain(key, publicKey)) {
                if (canProtectDomain(key.getDomainKey(), publicKey)) {
                    return backend.protectDomain(key, publicKey);
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean foreceOverrideDomain(Number160 domainKey, PublicKey publicKey) {
        // we are in public key mode
        if (getProtectionDomainMode() == ProtectionMode.MASTER_PUBLIC_KEY && publicKey != null) {
            // if the hash of the public key is the same as the domain, we can
            // overwrite
            return isMine(domainKey, publicKey);
        }
        return false;
    }

    private boolean foreceOverrideEntry(Number160 entryKey, PublicKey publicKey) {
        // we are in public key mode
        if (getProtectionEntryMode() == ProtectionMode.MASTER_PUBLIC_KEY && publicKey != null) {
            // if the hash of the public key is the same as the domain, we can
            // overwrite
            return isMine(entryKey, publicKey);
        }
        return false;
    }

    private boolean canUpdateEntry(Number160 contentKey, Data oldData, Data newData, boolean protectEntry) {
        if (protectEntry) {
            return canProtectEntry(contentKey, oldData, newData);
        }
        return true;
    }

    private boolean canProtectEntry(Number160 contentKey, Data oldData, Data newData) {
        if (getProtectionEntryEnable() == ProtectionEnable.ALL) {
            if (oldData == null)
                return true;
            else if (oldData.publicKey() == null)
                return true;
            else {
                if (oldData.publicKey().equals(newData.publicKey()))
                    return true;
            }
        }
        // we cannot protect, but maybe we have the rigth public key
        return foreceOverrideEntry(contentKey, newData.publicKey());
    }

    private static boolean isMine(Number160 key, PublicKey publicKey) {
        return key.equals(Utils.makeSHAHash(publicKey.getEncoded()));
    }

    public KeyLock<Storage> getLockStorage() {
        return dataLock;
    }

    public KeyLock<Number160> getLockNumber160() {
        return dataLock160;
    }

    public KeyLock<Number320> getLockNumber320() {
        return dataLock320;
    }

    public KeyLock<Number480> getLockNumber480() {
        return dataLock480;
    }

    public Collection<Number160> findContentForResponsiblePeerID(Number160 peerID) {
        return backend.findContentForResponsiblePeerID(peerID);
    }

    
}