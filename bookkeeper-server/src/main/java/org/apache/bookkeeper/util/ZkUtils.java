/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.bookkeeper.util;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.zookeeper.ZooKeeperWatcherBase;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provided utilites for zookeeper access, etc.
 */
public class ZkUtils {
    private static final Logger LOG = LoggerFactory.getLogger(ZkUtils.class);
    /**
     * Create zookeeper path recursively
     *
     * @param zk
     *          Zookeeper client
     * @param originalPath
     *          Zookeeper full path
     * @param data
     *          Zookeeper data
     * @param acl
     *          Acl of the zk path
     * @param createMode
     *          Create mode of zk path
     * @param callback
     *          Callback
     * @param ctx
     *          Context object
     */
    public static void createFullPathOptimistic(
        final ZooKeeper zk, final String originalPath, final byte[] data,
        final List<ACL> acl, final CreateMode createMode,
        final AsyncCallback.StringCallback callback, final Object ctx) {

        zk.create(originalPath, data, acl, createMode, new StringCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx, String name) {

                if (rc != Code.NONODE.intValue()) {
                    callback.processResult(rc, path, ctx, name);
                    return;
                }

                // Since I got a nonode, it means that my parents don't exist
                // create mode is persistent since ephemeral nodes can't be
                // parents
                createFullPathOptimistic(zk, new File(originalPath).getParent().replace("\\", "/"), new byte[0], acl,
                        CreateMode.PERSISTENT, new StringCallback() {

                            @Override
                            public void processResult(int rc, String path, Object ctx, String name) {
                                if (rc == Code.OK.intValue() || rc == Code.NODEEXISTS.intValue()) {
                                    // succeeded in creating the parent, now
                                    // create the original path
                                    createFullPathOptimistic(zk, originalPath, data, acl, createMode, callback,
                                            ctx);
                                } else {
                                    callback.processResult(rc, path, ctx, name);
                                }
                            }
                        }, ctx);
            }
        }, ctx);

    }

    private static class GetChildrenCtx {
        int rc;
        boolean done = false;
        List<String> children = null;
    }

    /**
     * Sync get all children under single zk node
     *
     * @param zk
     *          zookeeper client
     * @param node
     *          node path
     * @return direct children
     * @throws InterruptedException
     * @throws IOException
     */
    public static List<String> getChildrenInSingleNode(final ZooKeeper zk, final String node)
            throws InterruptedException, IOException {
        final GetChildrenCtx ctx = new GetChildrenCtx();
        getChildrenInSingleNode(zk, node, new GenericCallback<List<String>>() {
            @Override
            public void operationComplete(int rc, List<String> ledgers) {
                synchronized (ctx) {
                    if (Code.OK.intValue() == rc) {
                        ctx.children = ledgers;
                    }
                    ctx.rc = rc;
                    ctx.done = true;
                    ctx.notifyAll();
                }
            }
        });

        synchronized (ctx) {
            while (ctx.done == false) {
                ctx.wait();
            }
        }
        if (Code.OK.intValue() != ctx.rc) {
            throw new IOException("Error on getting children from node " + node);
        }
        return ctx.children;

    }

    /**
     * Async get direct children under single node
     *
     * @param zk
     *          zookeeper client
     * @param node
     *          node path
     * @param cb
     *          callback function
     */
    public static void getChildrenInSingleNode(final ZooKeeper zk, final String node,
            final GenericCallback<List<String>> cb) {
        zk.sync(node, new AsyncCallback.VoidCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx) {
                if (rc != Code.OK.intValue()) {
                    LOG.error("ZK error syncing nodes when getting children: ", KeeperException
                            .create(KeeperException.Code.get(rc), path));
                    cb.operationComplete(rc, null);
                    return;
                }
                zk.getChildren(node, false, new AsyncCallback.ChildrenCallback() {
                    @Override
                    public void processResult(int rc, String path, Object ctx, List<String> nodes) {
                        if (rc != Code.OK.intValue()) {
                            LOG.error("Error polling ZK for the available nodes: ", KeeperException
                                    .create(KeeperException.Code.get(rc), path));
                            cb.operationComplete(rc, null);
                            return;
                        }

                        cb.operationComplete(rc, nodes);

                    }
                }, null);
            }
        }, null);
    }

    /**
     * Utility to create the complete znode path synchronously
     * 
     * @param zkc
     *            - ZK instance
     * @param path
     *            - znode path
     * @param data
     *            - znode data
     * @param acl
     *            - Acl of the zk path
     * @param createMode
     *            - Create mode of zk path
     * @throws KeeperException
     *             if the server returns a non-zero error code, or invalid ACL
     * @throws InterruptedException
     *             if the transaction is interrupted
     */
    public static void createFullPathOptimistic(ZooKeeper zkc, String path,
            byte[] data, final List<ACL> acl, final CreateMode createMode)
            throws KeeperException, InterruptedException {
        try {
            zkc.create(path, data, acl, createMode);
        } catch (KeeperException.NoNodeException nne) {
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash <= 0) {
                throw nne;
            }
            String parent = path.substring(0, lastSlash);
            createFullPathOptimistic(zkc, parent, new byte[0], acl, createMode);
            zkc.create(path, data, acl, createMode);
        }
    }
}
