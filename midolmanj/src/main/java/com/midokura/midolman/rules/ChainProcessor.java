/*
 * Copyright 2012 Midokura Inc.
 */

package com.midokura.midolman.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.util.Callback;

public class ChainProcessor {

    private final static Logger log =
        LoggerFactory.getLogger(ChainProcessor.class);

    private RuleZkManager zkRuleMgr;
    private NatMapping natMap;
    private Set<Callback<UUID>> watchers;
    private Map<UUID, Chain> chainByUuid;
    private Map<String, UUID> uuidByName;
    private ChainZkManager zkChainMgr;
    private ChainWatcher chainWatcher;
    private Directory zkDir;
    private String basePath; 
    private UUID owner;

    public ChainProcessor(Directory dir, String zkBasePath, NatMapping natMap_,
                          UUID ownerID) throws StateAccessException {
        zkDir = dir;
        basePath = zkBasePath;
        owner = ownerID;
        zkRuleMgr = new RuleZkManager(zkDir, zkBasePath);
        natMap = natMap_;
        watchers = new HashSet<Callback<UUID>>();
        zkChainMgr = new ChainZkManager(zkDir, zkBasePath);
        chainWatcher = new ChainWatcher(ownerID);
        chainByUuid = new HashMap<UUID, Chain>();
        uuidByName = new HashMap<String, UUID>();
        updateChains(ownerID);
    }

    public void addWatcher(Callback<UUID> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Callback<UUID> watcher) {
        watchers.remove(watcher);
    }

    private void notifyWatchers() {
        for (Callback<UUID> watcher : watchers) {
            // TODO(pino): schedule for later instead of calling them here.
            watcher.call(owner);
        }
    }

    public void freeFlowResources(OFMatch match) {
        log.debug("freeFlowResources: match {}", match);

        natMap.freeFlowResources(match);
    }

    /* XXX
    private void updateResources() {
        // Tell the NatMapping about all the current NatTargets for SNAT.
        // TODO(pino): the NatMapping should clean up any old targets that
        // are no longer used and remember the current targets.
        Set<NatTarget> targets = new HashSet<NatTarget>();
        for (List<Rule> chain : ruleChains.values()) {
            for (Rule r : chain) {
                if (r instanceof ForwardNatRule) {
                    ForwardNatRule fR = (ForwardNatRule) r;
                    if (!fR.dnat) {
                        targets.addAll(fR.getNatTargets());
                    }
                }
            }
        }
        natMap.updateSnatTargets(targets);
    }
    */

    private void addChain(UUID id, String name) {
        Chain chain = new Chain(id, name, zkDir, basePath);
        chainByUuid.put(id, chain);
        uuidByName.put(name, id);
    }

    private void removeChain(UUID id) {
        String name = chainByUuid.get(id).getChainName();
        chainByUuid.remove(id);
        uuidByName.remove(name);
    }
        

    /**
     * Called when a change in the state (ZooKeeper) has been notified
     * @throws StateAccessException
     * @throws ZkStateSerializationException
     */
    public void updateChains(UUID ownerID) throws StateAccessException,
            ZkStateSerializationException {
        Collection<ZkNodeEntry<UUID, ChainZkManager.ChainConfig>>
                entryList = zkChainMgr.list(ownerID, chainWatcher);

        HashSet<UUID> updatedChains = new HashSet<UUID>();
        boolean hasUpdates = false;
       
        // Add new entries, store traversed entries for helping later removal
        for (ZkNodeEntry<UUID, ChainZkManager.ChainConfig> entry : entryList) {
            updatedChains.add(entry.key);
            if (!chainByUuid.containsKey(entry.key)) {
                addChain(entry.key, entry.value.name);
                hasUpdates = true;
            }
        }

        // Remove old entries
        for (UUID oldChain : chainByUuid.keySet()) {
            if (!updatedChains.contains(oldChain)) {
                removeChain(oldChain);
                hasUpdates = true;
            }
        }

        if (hasUpdates) {
            //updateResources(); //XXX
            notifyWatchers();
        }
    }

    private class ChainWatcher implements Runnable {
        private UUID ownerID;

        public ChainWatcher(UUID id) { ownerID = id; }

        @Override
        public void run() {
            try {
                updateChains(ownerID);
            } catch (Exception e) {
                log.warn("ChainWatcher.run", e);
            }
        }
    }


    /**
     *
     * @param chainName
     * @param flowMatch
     *            matches the packet that originally entered the datapath. It
     *            will NOT be modified by the rule chain.
     * @param pktMatch
     *            matches the packet that would be seen in this router/chain. It
     *            will NOT be modified by the rule chain.
     * @param inPortId
     * @param outPortId
     * @return
     */
    public RuleResult applyChain(String chainName, MidoMatch flowMatch,
            MidoMatch pktMatch, UUID inPortId, UUID outPortId) {

        if (!(uuidByName.containsKey(chainName))) {
             log.debug("applyChain: No chain {} -- returning ACCEPT", chainName);
             return new RuleResult(RuleResult.Action.ACCEPT, null, pktMatch, false);
        }

        // XXX
        return new RuleResult(RuleResult.Action.ACCEPT, null, pktMatch, false);
    }
}
