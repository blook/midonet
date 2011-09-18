/*
 * @(#)ChainZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.rules.Rule;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class ChainZkManager extends ZkManager {

    public static class ChainConfig implements Serializable {

        private static final long serialVersionUID = 1L;
        public UUID routerId = null;
        public String name = null;

        public ChainConfig() {
        }

        public ChainConfig(String name, UUID routerId) {
            this.name = name;
            this.routerId = routerId;
        }
    }

    /**
     * Constructor to set ZooKeeper and base path.
     * 
     * @param zk
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public ChainZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public ChainZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
    }

    public List<Op> prepareChainCreate(ZkNodeEntry<UUID, ChainConfig> chainEntry)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getChainPath(chainEntry.key),
                    serialize(chainEntry.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize ChainConfig", e, ChainConfig.class);
        }
        ops.add(Op.create(pathManager.getChainRulesPath(chainEntry.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterChainPath(
                chainEntry.value.routerId, chainEntry.key), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        return ops;
    }

    public UUID create(ChainConfig chain) throws InterruptedException,
            KeeperException, ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, ChainConfig> chainNode = new ZkNodeEntry<UUID, ChainConfig>(
                id, chain);
        zk.multi(prepareChainCreate(chainNode));
        return id;
    }

    public ZkNodeEntry<UUID, ChainConfig> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        byte[] data = zk.get(pathManager.getChainPath(id), null);
        ChainConfig config = null;
        try {
            config = deserialize(data, ChainConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize chain " + id + " to ChainConfig", e,
                    ChainConfig.class);
        }
        return new ZkNodeEntry<UUID, ChainConfig>(id, config);
    }

    public List<ZkNodeEntry<UUID, ChainConfig>> list(UUID routerId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        return list(routerId, null);
    }

    public List<ZkNodeEntry<UUID, ChainConfig>> list(UUID routerId,
            Runnable watcher) throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, ChainConfig>> result = new ArrayList<ZkNodeEntry<UUID, ChainConfig>>();
        Set<String> chains = zk.getChildren(pathManager
                .getRouterChainsPath(routerId), watcher);
        for (String chainId : chains) {
            // For now, get each one.
            result.add(get(UUID.fromString(chainId)));
        }
        return result;
    }

    public void update(ZkNodeEntry<UUID, ChainConfig> entry)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        try {
            zk.update(pathManager.getChainPath(entry.key),
                    serialize(entry.value));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize chain " + entry.key
                            + " to ChainConfig", e, ChainConfig.class);
        }
    }

    public List<Op> getDeleteOps(UUID id, UUID routerId)
            throws KeeperException, InterruptedException,
            ClassNotFoundException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        RuleZkManager ruleZk = new RuleZkManager(zk, basePath);
        List<ZkNodeEntry<UUID, Rule>> entries = ruleZk.list(id);
        for (ZkNodeEntry<UUID, Rule> entry : entries) {
            ops.addAll(ruleZk.getDeleteOps(entry.key, id));
        }
        ops.add(Op.delete(pathManager.getRouterChainPath(routerId, id), -1));
        ops.add(Op.delete(pathManager.getChainPath(id), -1));
        return ops;
    }

    public void delete(UUID id) throws KeeperException, InterruptedException,
            ClassNotFoundException, ZkStateSerializationException, IOException {
        ZkNodeEntry<UUID, ChainConfig> chain = get(id);
        delete(id, chain.value.routerId);
    }

    public void delete(UUID id, UUID routerId) throws InterruptedException,
            KeeperException, ClassNotFoundException,
            ZkStateSerializationException {
        this.zk.multi(getDeleteOps(id, routerId));
    }
}
