package org.apache.activemq.bookkeeper.store;

import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.junit.Test;

import java.util.Enumeration;

public class SimpleTest {

    @Test
    public void simpleStorage() throws Exception {
        ClientConfiguration config = new ClientConfiguration();
        config.setMetadataServiceUri("zk+null://localhost:2181");
        config.setAddEntryTimeout(2000);

        try (BookKeeper bookKeeper = new BookKeeper(config)) {

            LedgerHandle ledger = bookKeeper.createLedger(BookKeeper.DigestType.MAC, "activemq".getBytes());
            long ledgerId = ledger.getId();

            ledger.append("my-message".getBytes());
            
            ledger.close();
        }
    }

}
