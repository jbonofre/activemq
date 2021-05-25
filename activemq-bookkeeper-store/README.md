# Apache ActiveMQ rdStore

ActiveMQ rdStore (for Replicated and Distributed message Store).

It's powered by Apache ZooKeeper and Apache BookKeeper.

Basically, the overall architecture is:

* metadata is store in ZooKeeper. By metadata, it means the mapping destination name / destination type / ledger id.
* the actual storage is in BookKeeper, basically, one BookKeeper ledger per destination.