package me.binge.canal.consumer.cluster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.commons.lang3.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.otter.canal.common.zookeeper.ZkClientx;
import com.alibaba.otter.canal.common.zookeeper.ZookeeperPathUtils;
import com.google.common.collect.Maps;

public class ClusterManager {

	private static final Logger logger = LoggerFactory.getLogger(ClusterManager.class);

	public static final String CONSUMER_PATH = ZookeeperPathUtils.CANAL_ROOT_NODE + ZookeeperPathUtils.ZOOKEEPER_SEPARATOR + "consumers";
	public static final String CONSUMER_CLUSTER_PATH = CONSUMER_PATH + ZookeeperPathUtils.ZOOKEEPER_SEPARATOR + "cluster";
	public static final String CONSUMER_DESTINATIONS_PATH = CONSUMER_PATH + ZookeeperPathUtils.ZOOKEEPER_SEPARATOR + "destinations";

	private static final ConcurrentMap<String, DestinationConsumer> CONSUMERS = Maps.<String, DestinationConsumer>newConcurrentMap();
	private static ConcurrentMap<String, Object> ALL_DESTINATIONS = Maps.<String, Object>newConcurrentMap();

	private boolean running = false;

	private int                        delayTime    = 5;
	private static final ReentrantLock CONSUMER_ALL_LOCK = new ReentrantLock();

	private Integer batchSize;
	private String cid;
	private ZkClientx zkClientx;
	private String zkHosts;

	public void boot() throws Exception {
		logger.info("will boot consumer {}, zookeeper hosts {}", new Object[]{cid, zkHosts});
		zkClientx.createPersistent(CONSUMER_DESTINATIONS_PATH, true);
		zkClientx.createPersistent(CONSUMER_CLUSTER_PATH, true);
		regist();
		run();
		Runtime.getRuntime().addShutdownHook(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread();
				t.setDaemon(true);
				t.setPriority(Thread.MAX_PRIORITY);
				t.setName("cluster-manager-shutdown-hook");
				return t;
			}
		}.newThread(new Runnable() {

			@Override
			public void run() {
				shutdown();
			}
		}));
		logger.info("consumer {} is started......", cid);
		for (;;) {
		}
	}

	public void shutdown() {
		boolean delLock = false;
		if (running) {
			delLock = true;
		}
		running = false;

		if (delLock) {
			for (String destination : CONSUMERS.keySet()) {
				unlockForConsumer(destination);
			}
			zkClientx.delete(CONSUMER_CLUSTER_PATH + ZookeeperPathUtils.ZOOKEEPER_SEPARATOR + cid);
		}
		zkClientx.close();
	}

	/**
	 * start to work.
	 * @throws Exception
	 */
	private void run() throws Exception {
//		batchSize = Integer.valueOf(propertiesLoader.getProp("canal.consumer.batch.size", (5 * 1024) + ""));
		logger.info("fetch events batch size {}",  batchSize);
		List<String> destinations = zkClientx.getChildren(ZookeeperPathUtils.DESTINATION_ROOT_NODE);
		for (String destination : destinations) {
			ALL_DESTINATIONS.put(destination, 1);
		}

		running = true;
		consumingAllDestinations();
		zkClientx.subscribeChildChanges(CONSUMER_DESTINATIONS_PATH, new IZkChildListener() {

			@Override
			public void handleChildChange(String parentPath, List<String> currentChildren)
					throws Exception {
				if (!running) {
					return;
				}
				consumingAllDestinations();

			}
		});

		this.zkClientx.subscribeChildChanges(ZookeeperPathUtils.DESTINATION_ROOT_NODE, new IZkChildListener() {

			@Override
			public void handleChildChange(String parentPath, List<String> currentChilds)
					throws Exception {
				if (!running) {
					return;
				}
				if (currentChilds == null) {
					currentChilds = new ArrayList<String>();
				}
				Set<String> currentAllDestinations = new HashSet<String>();
				for (String destination : ALL_DESTINATIONS.keySet()) {
					if (!currentAllDestinations.contains(destination)) {
						unlockForConsumer(destination);
					}
				}
				ALL_DESTINATIONS.clear();
				for (String destination : currentAllDestinations) {
					ALL_DESTINATIONS.put(destination, 1);
				}
				consumingAllDestinations();
			}
		});

	}


	private void consumingAllDestinations() throws InterruptedException {
		CONSUMER_ALL_LOCK.lockInterruptibly();
		try {
			for (String destination : ALL_DESTINATIONS.keySet()) {
				if (consumingDestination(destination)) {
					logger.info("consumer destination {} on {}", new Object[]{destination, cid});
					try {
						TimeUnit.SECONDS.sleep((delayTime + RandomUtils.nextInt(0, delayTime)));
					} catch (InterruptedException e) {
					}
				}
			}
		} finally {
			CONSUMER_ALL_LOCK.unlock();
		}

	}

	/**
	 * start consumer destination.
	 * @param batchSize
	 * @param zkHosts
	 * @param destination
	 */
	private boolean consumingDestination(final String destination) {
		boolean r = lockForConsumer(destination);
		if (!r) {
			logger.warn("another consumer is consuming destination {}, skip it.", destination);
			return false;
		}
		try {
			final DestinationConsumer consumer = new DestinationConsumer();
			consumer.setBatchSize(batchSize);
			consumer.setZkHosts(zkHosts);
			consumer.setDestination(destination);
			consumer.setSuccCallback(new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					CONSUMERS.put(destination, consumer);
					return null;
				}
			});
			consumer.setErrCallback(new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					unlockForConsumer(destination);
					return null;
				}
			});
			consumer.start();
			logger.info("destination {} has been consuming on this consumer..........", destination);
			return true;
		} catch(Exception e) {
			logger.error("destination {}'s consumer occour an error, stop it and release resource.", destination);
			logger.error("destination's consumer occour an error.", e);
			unlockForConsumer(destination);
			return false;
		}
	}

	private void unlockForConsumer(String destination) {
		DestinationConsumer consumer = CONSUMERS.remove(destination);
		if (consumer != null) {
			consumer.stop();
		}
		zkClientx.delete(ZookeeperPathUtils.getDestinationClientRunning(destination, new Short("1001")));
		zkClientx.delete(CONSUMER_DESTINATIONS_PATH + ZookeeperPathUtils.ZOOKEEPER_SEPARATOR + destination);
	}

	private boolean lockForConsumer(String destination) {
		if (!running) {
			return false;
		}
		try {
			zkClientx.createEphemeral(CONSUMER_DESTINATIONS_PATH + ZookeeperPathUtils.ZOOKEEPER_SEPARATOR + destination, cid);
		} catch (ZkNodeExistsException zknee) {
			return false;
		} catch (Exception e) {
			logger.error("try to get consumer lock for destination {} error.", destination);
			logger.error("try to get consumer lock for destinationerror.", e);
			return false;
		}
		return running;
	}

	/**
	 * regist current node to zookeeper.
	 */
	private void regist() {
		zkClientx.createEphemeral(CONSUMER_CLUSTER_PATH + ZookeeperPathUtils.ZOOKEEPER_SEPARATOR + cid, "");
		logger.info("registed consumer {}", cid);
	}

	public void setBatchSize(Integer batchSize) {
		this.batchSize = batchSize;
	}

	public void setCid(String cid) {
		this.cid = cid;
	}

	public void setZkClientx(ZkClientx zkClientx) {
		this.zkClientx = zkClientx;
	}

	public void setZkHosts(String zkHosts) {
		this.zkHosts = zkHosts;
	}

}
