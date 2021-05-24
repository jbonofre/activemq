package org.apache.activemq.bookkeeper.store;

import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.junit.Test;

public class SimpleBookKeeperTest {

    @Test
    public void simpleStorage() throws Exception {
        try (BookKeeper bookKeeper = new BookKeeper("127.0.0.1:2181")) {

            System.out.println(bookKeeper.getBookieInfo());

            LedgerHandle ledgerHandle = bookKeeper.createLedger(3, 3, 2, BookKeeper.DigestType.MAC, "password".getBytes());

            ledgerHandle.addEntry("my-message".getBytes());

        }
    }

}
