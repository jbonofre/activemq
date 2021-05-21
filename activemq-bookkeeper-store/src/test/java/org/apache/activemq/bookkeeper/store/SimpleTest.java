package org.apache.activemq.bookkeeper.store;

import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.junit.Test;

public class SimpleTest {

    @Test
    public void simpleStorage() throws Exception {
        // ClientConfiguration clientConfiguration = new ClientConfiguration();
        // clientConfiguration.setMetadataServiceUri("127.0.0.1:2181");
        // clientConfiguration.setAddEntryTimeout(2000);
        BookKeeper bookKeeper = new BookKeeper("127.0.0.1:2181");

        byte[] messages = "messages".getBytes();
        LedgerHandle ledger = bookKeeper.createLedger(BookKeeper.DigestType.MAC, messages);

        long entryId = ledger.addEntry("My First Message".getBytes());

        System.out.println("Ledger size: " + ledger.getLength());
    }

}
