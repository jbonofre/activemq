package org.apache.activemq.bookkeeper.store;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.junit.Test;

public class ZooKeeperSimpleTest {

    @Test
    public void simpleTest() throws Exception {
        CuratorFramework client = CuratorFrameworkFactory.newClient("127.0.0.1:2181", new ExponentialBackoffRetry(1000, 3));
        client.start();
        client.create().forPath("/activemq/destinations/my-queue", "13456789".getBytes());

        String data =  client.getData().watched().forPath("/activemq/destinations/my-queue").toString();
        System.out.println(data);
    }

}
