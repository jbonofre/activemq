package org.apache.activemq.store.bookkeeper;

import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.scheduler.JobSchedulerStore;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.ProducerId;
import org.apache.activemq.openwire.OpenWireFormat;
import org.apache.activemq.store.MessageStore;
import org.apache.activemq.store.PersistenceAdapter;
import org.apache.activemq.store.TopicMessageStore;
import org.apache.activemq.store.TransactionStore;
import org.apache.activemq.store.memory.MemoryTransactionStore;
import org.apache.activemq.usage.SystemUsage;
import org.apache.activemq.util.ServiceStopper;
import org.apache.activemq.util.ServiceSupport;
import org.apache.activemq.wireformat.WireFormat;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 *
 * @org.apache.xbean.XBean element="bookkeeperPersistenceAdapter"
 */
public class BookKeeperPersistenceAdapter extends ServiceSupport implements PersistenceAdapter {

    private String zookeeperUris;
    private WireFormat wireFormat = new OpenWireFormat();
    private MemoryTransactionStore transactionStore = new MemoryTransactionStore(this);
    private File directory;

    private BookKeeper bookKeeper;

    private static final Logger LOG = LoggerFactory.getLogger(BookKeeperPersistenceAdapter.class);

    public BookKeeperPersistenceAdapter() {
    }

    public BookKeeperPersistenceAdapter(WireFormat wireFormat) {
        this.wireFormat = wireFormat;
    }

    @Override
    public void doStart() throws Exception {
        if (zookeeperUris == null) {
            zookeeperUris = "127.0.0.1:2181";
        }
        bookKeeper = new BookKeeper(zookeeperUris);
    }

    @Override
    protected void doStop(ServiceStopper stopper) throws Exception {
        if (bookKeeper != null) {
            bookKeeper.close();
        }
    }

    @Override
    public Set<ActiveMQDestination> getDestinations() {
        for (Long ledgerId : getLedgerIds()) {
            
        }
    }

    protected Set<Long> getLedgerIds() {
        Set<Long> ledgers = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch processDone = new CountDownLatch(1);
        bookKeeper.getLedgerManager().asyncProcessLedgers(
                (ledgerId, cb) -> {
                    ledgers.add(ledgerId);
                    cb.processResult(BKException.Code.OK, null, null);
                },
                (rc, s, obj) -> {
                    processDone.countDown();
                },
                null,
                BKException.Code.OK,
                BKException.Code.ReadException
        );
        try {
            processDone.await(1, TimeUnit.MINUTES);
            return ledgers;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public MessageStore createQueueMessageStore(ActiveMQQueue destination) throws IOException {
        return new BookKeeperMessageStore(destination, this);
    }

    @Override
    public TopicMessageStore createTopicMessageStore(ActiveMQTopic destination) throws IOException {
        return new BookKeeperTopicMessageStore(destination, this);
    }

    @Override
    public JobSchedulerStore createJobSchedulerStore() throws IOException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeQueueMessageStore(ActiveMQQueue destination) {
        // TODO deal with virtual destinations and remove consumer

        bookKeeper.deleteLedger(ledgerId);
    }

    @Override
    public void removeTopicMessageStore(ActiveMQTopic destination) {

    }

    @Override
    public TransactionStore createTransactionStore() throws IOException {
        return null;
    }

    @Override
    public void beginTransaction(ConnectionContext context) throws IOException {

    }

    @Override
    public void commitTransaction(ConnectionContext context) throws IOException {

    }

    @Override
    public void rollbackTransaction(ConnectionContext context) throws IOException {

    }

    @Override
    public long getLastMessageBrokerSequenceId() throws IOException {
        return 0;
    }

    @Override
    public void deleteAllMessages() throws IOException {

    }

    @Override
    public void setUsageManager(SystemUsage usageManager) {

    }

    @Override
    public void setBrokerName(String brokerName) {

    }

    @Override
    public void setDirectory(File dir) {

    }

    @Override
    public File getDirectory() {
        return null;
    }

    @Override
    public void checkpoint(boolean cleanup) throws IOException {

    }

    @Override
    public long size() {
        return 0;
    }

    @Override
    public long getLastProducerSequenceId(ProducerId id) throws IOException {
        return 0;
    }

    @Override
    public void allowIOResumption() {

    }
}
