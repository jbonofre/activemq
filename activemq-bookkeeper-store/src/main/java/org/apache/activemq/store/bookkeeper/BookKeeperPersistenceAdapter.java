package org.apache.activemq.store.bookkeeper;

import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.scheduler.JobSchedulerStore;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.ProducerId;
import org.apache.activemq.store.MessageStore;
import org.apache.activemq.store.PersistenceAdapter;
import org.apache.activemq.store.TopicMessageStore;
import org.apache.activemq.store.TransactionStore;
import org.apache.activemq.usage.SystemUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Set;

public class BookKeeperPersistenceAdapter implements PersistenceAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(BookKeeperPersistenceAdapter.class);

    @Override
    public Set<ActiveMQDestination> getDestinations() {
        return null;
    }

    @Override
    public MessageStore createQueueMessageStore(ActiveMQQueue destination) throws IOException {
        return null;
    }

    @Override
    public TopicMessageStore createTopicMessageStore(ActiveMQTopic destination) throws IOException {
        return null;
    }

    @Override
    public JobSchedulerStore createJobSchedulerStore() throws IOException, UnsupportedOperationException {
        return null;
    }

    @Override
    public void removeQueueMessageStore(ActiveMQQueue destination) {

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

    @Override
    public void start() throws Exception {

    }

    @Override
    public void stop() throws Exception {

    }
}
