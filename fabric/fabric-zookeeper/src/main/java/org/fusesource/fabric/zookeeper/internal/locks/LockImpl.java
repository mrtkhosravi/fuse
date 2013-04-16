package org.fusesource.fabric.zookeeper.internal.locks;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.fusesource.fabric.zookeeper.IZKClient;
import org.fusesource.fabric.zookeeper.Lock;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class LockImpl implements Lock {

    private final String path;
    private final IZKClient zooKeeper;
    private String node;

    private final Watcher watcher = new Watcher() {
        @Override
        public void process(WatchedEvent event) {
            doNotifyAll();
        }
    };

    /**
     * Constructor
     *
     * @param zooKeeper
     * @param path
     */
    protected LockImpl(IZKClient zooKeeper, String path) {
        this.path = path;
        this.zooKeeper = zooKeeper;
    }

    @Override
    public synchronized boolean tryLock(long time, TimeUnit unit) {
        long waitFor = unit.toMillis(time);
        long start = System.currentTimeMillis();

        try {
            ZooKeeperUtils.createDefault(zooKeeper, path, "");

            if (node == null) {
                node = zooKeeper.create(ZkPath.LOCK.getPath(path), CreateMode.EPHEMERAL_SEQUENTIAL);
            }
            while (start + waitFor >= System.currentTimeMillis()) {
                List<String> children = zooKeeper.getChildren(path);
                String id = stripPath(node);
                if (hasLock(id, children)) {
                    return true;
                } else {
                    doWaitForPath(path, start + waitFor - System.currentTimeMillis());
                }
            }
        } catch (Exception ex) {
            return false;
        }
        return false;
    }

    @Override
    public synchronized void unlock() {
        try {
            zooKeeper.delete(node);
            node = null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String stripPath(String node) {
        if (node.contains("/")) {
            return node.substring(node.lastIndexOf("/") + 1);
        } else {
            return node;
        }
    }


    /**
     * Returns true if the specified id is the lowest from all candidates.
     *
     * @param id
     * @param allCandidates
     * @return
     */
    private static boolean hasLock(String id, List<String> allCandidates) {
        if (allCandidates == null || allCandidates.size() == 1) {
            return true;
        }
        long our = Long.parseLong(id);
        for (String child : allCandidates) {
            long their = Long.parseLong(child);
            if (their < our) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sets a {@link Watcher} to the path and waits for the specified duration.
     *
     * @param path
     * @param time
     */
    private synchronized void doWaitForPath(String path, long time) {
        try {
            if (zooKeeper.exists(path, watcher) != null) {
                wait(time);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Notifies all.
     */
    private synchronized void doNotifyAll() {
        notifyAll();
    }
}