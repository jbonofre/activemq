package org.apache.activemq.store.bookkeeper;

import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageId;
import org.apache.activemq.command.SubscriptionInfo;
import org.apache.activemq.store.MessageRecoveryListener;
import org.apache.activemq.store.MessageStoreSubscriptionStatistics;
import org.apache.activemq.store.TopicMessageStore;

import java.io.IOException;

public class BookKeeperTopicMessageStore extends BookKeeperMessageStore implements TopicMessageStore {

    public BookKeeperTopicMessageStore(ActiveMQTopic topic, BookKeeperPersistenceAdapter persistenceAdapter) {
        super(topic, persistenceAdapter);
    }

    @Override
    public void acknowledge(ConnectionContext context, String clientId, String subscriptionName, MessageId messageId, MessageAck ack) throws IOException {

    }

    @Override
    public void deleteSubscription(String clientId, String subscriptionName) throws IOException {

    }

    @Override
    public void recoverSubscription(String clientId, String subscriptionName, final MessageRecoveryListener listener) throws Exception {

    }

    @Override
    public void recoverNextMessages(String clientId, String subscriptionName, int maxReturned, MessageRecoveryListener listener) throws Exception {

    }

    @Override
    public void resetBatching(String clientId, String subscriptionName) {

    }

    @Override
    public int getMessageCount(String clientId, String subscriberName) throws IOException {
        return 0;
    }

    @Override
    public long getMessageSize(String clientId, String subscriberName) throws IOException {
        return 0;
    }

    @Override
    public MessageStoreSubscriptionStatistics getMessageStoreSubStatistics() {
        return null;
    }

    @Override
    public SubscriptionInfo lookupSubscription(String clientId, String subscriptionName) throws IOException {
        return null;
    }

    @Override
    public SubscriptionInfo[] getAllSubscriptions() throws IOException {
        return new SubscriptionInfo[0];
    }

    @Override
    public void addSubscription(SubscriptionInfo subscriptionInfo, boolean retroactive) throws IOException {

    }


}
