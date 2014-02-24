/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.host.services;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;

import org.midonet.midolman.host.HostIdGenerator;
import org.midonet.midolman.host.HostIdGenerator.PropertiesFileNotWritableException;
import org.midonet.midolman.host.HostInterfaceWatcher;
import org.midonet.midolman.host.commands.executors.HostCommandWatcher;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.scanner.InterfaceScanner;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.services.HostIdProviderService;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.ZkManager;

/**
 * Host internal service.
 * <p/>
 * It starts and stops the host service.
 * TODO: need to try to reattach the zk session so we can recover the host state.
 */
public class HostService extends AbstractService
    implements HostIdProviderService {

    private static final Logger log = LoggerFactory
            .getLogger(HostService.class);

    private UUID hostId;

    private Thread watcherThread;

    @Inject
    HostConfig configuration;

    @Inject
    private HostCommandWatcher cmdWatcher;

    @Inject
    private HostInterfaceWatcher interfaceWatcher;

    @Inject
    private InterfaceScanner scanner;

    @Inject
    private HostZkManager hostZkManager;

    @Inject
    private ZkManager zkManager;

    public static class HostIdAlreadyInUseException extends Exception {
        HostIdAlreadyInUseException(String message) {
            super(message);
        }
    }

    @Override
    protected void doStart() {

        log.info("Starting Midolman host agent.");
        try {
            identifyHostId();
            cmdWatcher.checkCommands(hostId);
            watcherThread = new Thread(interfaceWatcher);
            interfaceWatcher.setHostId(hostId);
            watcherThread.start();
            notifyStarted();
        } catch (Exception e) {
            notifyFailed(e);
            throw new RuntimeException("Could not start Midolman", e);
        }
        log.info("Midolman host agent started.");

    }

    @Override
    protected void doStop() {

        log.info("Stopping Midolman host agent.");
        try {
            scanner.shutDownNow();

            // tell the watcher thread to stop
            interfaceWatcher.stop();

            // wait for the thread to finish running
            if (watcherThread != null ) {
                LockSupport.unpark(watcherThread);
                watcherThread.join();
            }

            // disconnect from zookeeper.
            // this will cause the ephemeral nodes to disappear.
            zkManager.disconnect();

            notifyStopped();
        } catch (InterruptedException e) {
            notifyFailed(e);
        }
        log.info("Midolman host agent stopped.");

    }

    /**
     * Scans the host and identifies the host ID.
     *
     * @return ID identified
     * @throws StateAccessException
     *              If there was a problem reading data from ZK.
     * @throws PropertiesFileNotWritableException
     *                                     If the properties file cannot
     *                                     be written
     * @throws InterruptedException
     */
    private void identifyHostId()
            throws StateAccessException, PropertiesFileNotWritableException,
                   InterruptedException, SerializationException,
                   HostIdAlreadyInUseException {

        log.debug("Identifying host");
        // Try to get the host Id
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();

        // Retrieve the interfaces and store the addresses in the metadata
        ArrayList<InetAddress> listAddresses = new ArrayList<>();
        for (InterfaceDescription info: scanner.scanInterfaces()) {
            listAddresses.addAll(info.getInetAddresses());
        }

        metadata.setAddresses(
                listAddresses.toArray(new InetAddress[listAddresses.size()]));

        try {
            metadata.setName(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            metadata.setName("UNKNOWN");
        }

        hostId = HostIdGenerator.getHostId(configuration);
        int retries = configuration.getRetriesForUniqueHostId();

        while (!create(hostId, metadata, hostZkManager) && --retries >= 0) {
            // The ID is already in use, wait. It could be that the ephemeral
            // node has not been deleted yet (if the host just crashed)
            log.warn("Host ID already in use. Waiting for it to be released.");

            Thread.sleep(configuration.getWaitTimeForUniqueHostId());
        }

        if (retries < 0) {
            log.error("Couldn't take ownership of the in-use host ID");
            throw new HostIdAlreadyInUseException(
                    "Host ID " + hostId + "appears to already be taken");
        }
    }

    private static boolean create(UUID id,
                                  HostDirectory.Metadata metadata,
                                  HostZkManager zkManager)
            throws StateAccessException, SerializationException {

        if (zkManager.exists(id)) {
            if (!metadata.equals(zkManager.get(id))) {
                if (zkManager.isAlive(id))
                    return false;
                zkManager.updateMetadata(id, metadata);
            }
        } else {
            zkManager.createHost(id, metadata);
        }

        // We want to make sure that the ephemeral nodes didn't
        // disappear in the meanwhile
        try {
            zkManager.makeAlive(id);
        } catch (StatePathExistsException ignored) { }

        try {
            zkManager.setHostVersion(id);
        } catch (StatePathExistsException ignored) { }

        return true;
    }

    public UUID getHostId() {
        return hostId;
    }
}
