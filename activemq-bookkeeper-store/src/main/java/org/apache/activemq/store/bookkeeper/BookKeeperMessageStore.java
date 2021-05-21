package org.apache.activemq.store.bookkeeper;

import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageAck;
import org.apache.activemq.command.MessageId;
import org.apache.activemq.store.AbstractMessageStore;
import org.apache.activemq.store.MessageRecoveryListener;

import java.io.IOException;

public class BookKeeperMessageStore extends AbstractMessageStore {

    protected final BookKeeperPersistenceAdapter bookKeeperPersistenceAdapter;

    public BookKeeperMessageStore(ActiveMQDestination destination, BookKeeperPersistenceAdapter bookKeeperPersistenceAdapter) {
        super(destination);
        this.bookKeeperPersistenceAdapter = bookKeeperPersistenceAdapter;
    }

    @Override
    public void addMessage(ConnectionContext context, Message message) throws IOException {

    }

    @Override
    public Message getMessage(MessageId identity) throws IOException {
        return null;
    }

    @Override
    public void removeMessage(ConnectionContext context, MessageAck ack) throws IOException {

    }

    @Override
    public void removeAllMessages(ConnectionContext context) throws IOException {

    }

    @Override
    public void recover(MessageRecoveryListener container) throws Exception {

    }

    @Override
    public void resetBatching() {

    }

    @Override
    public void recoverNextMessages(int maxReturned, MessageRecoveryListener listener) throws Exception {

    }
}
