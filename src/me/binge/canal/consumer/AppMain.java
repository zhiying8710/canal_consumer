package me.binge.canal.consumer;

import me.binge.canal.consumer.cluster.ClusterManager;

import org.springframework.context.support.ClassPathXmlApplicationContext;

public class AppMain {

	public static void main(String[] args) {
		ClusterManager clusterManager = null;
		ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("classpath:spring-context.xml");
		try {
			clusterManager = (ClusterManager) context.getBean("clusterManager", ClusterManager.class);
			clusterManager.boot();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (clusterManager != null) {
				clusterManager.shutdown();
			}
			context.close();
		}
	}

}
