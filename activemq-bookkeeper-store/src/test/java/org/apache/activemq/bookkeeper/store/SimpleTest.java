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
            
            byte[] messages = "messages".getBytes();
            LedgerHandle ledger = bookKeeper.createLedger(BookKeeper.DigestType.MAC, messages);
            long ledgerId = ledger.getId();

            ledger.addEntry("My First Message".getBytes());

            ledger.close();

            ledger = bookKeeper.openLedger(ledgerId, BookKeeper.DigestType.MAC, messages);

            Enumeration<LedgerEntry> entries = ledger.readEntries(0, ledger.getLastAddConfirmed());
            while (entries.hasMoreElements()) {
                LedgerEntry entry = entries.nextElement();
                System.out.println("Entry: " + entry.getEntryId());
            }

            System.out.println("Ledger size: " + ledger.getLength());

            ledger.close();
            bookKeeper.close();
        }
    }

}
