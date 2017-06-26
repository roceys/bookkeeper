---
title: Example application
javadoc_button: true
---

This tutorial walks you through building an example application that uses BookKeeper as the replicated log.

## Setup

Before you start, you will need to have a BookKeeper cluster running locally on your machine. For installation instructions, see [Installation](../../getting-started/installation).

To start up a cluster consisting of six bookies locally:

```shell
$ bookkeeper-server/bin/bookkeeper localbookie 6
```

## The base application

The application in this tutorial is a dice application. The `Dice` class below generates a random number between 1 and 6 every second, prints the value of the dice roll, and runs indefinitely.

```java
public class Dice {
    Random r = new Random();

    void playDice() throws InterruptedException {
        while (true) {
            Thread.sleep(1000);
            System.out.println("Value = " + (r.nextInt(6) + 1));
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Dice d = new Dice();
        d.playDice();
    }
}
```

Our goal is to have multiple instances of this application, possibly running on different machine, which each display the exact same sequence of numbers. If one the the instances crashes or becomes unable to communicate with the others in any way, it should still not diverge from the sequence of numbers. This tutorial will show you how to achieve this.

To start, download the base application, compile and run it.

```shell
$ git clone https://github.com/ivankelly/bookkeeper-tutorial.git
$ mvn package
$ mvn exec:java -Dexec.mainClass=org.apache.bookkeeper.Dice
```

That should yield output that looks something like this:

```
[INFO] Scanning for projects...
[INFO]                                                                         
[INFO] ------------------------------------------------------------------------
[INFO] Building tutorial 1.0-SNAPSHOT
[INFO] ------------------------------------------------------------------------
[INFO]
[INFO] --- exec-maven-plugin:1.3.2:java (default-cli) @ tutorial ---
[WARNING] Warning: killAfter is now deprecated. Do you need it ? Please comment on MEXEC-6.
Value = 4
Value = 5
Value = 3
```

## Leaders and followers (and a bit of background)

To achieve this common view in multiple instances of the program, we need each instance to agree on what the next number in the sequence will be. For example, the instances must agree that 4 is the first number and 2 is the second number and 5 is the third number and so on. This is a difficult problem, especially in the case that any instance may go away at any time, and messages between the instances can be lost or reordered.

Luckily, there are already algorithms to solve this. Paxos is an abstract algorithm to implement this kind of agreement, while Zab and Raft are more practical protocols. This video gives a good overview about how these algorithms usually look. They all have a similar core.

It would be possible to run the Paxos to agree on each number in the sequence. However, running Paxos each time can be expensive. What Zab and Raft do is that they use a Paxos-like algorithm to elect a leader. The leader then decides what the sequence of events should be, putting them in a log, which the other instances can then follow to maintain the same state as the leader.

Bookkeeper provides the functionality for the second part of the protocol, allowing a leader to write events to a log and have multiple followers tailing the log. However, bookkeeper does not do leader election. You will need a zookeeper or raft instance for that purpose.

## Why not just use ZooKeeper?

There are a number of reasons:

1. Zookeeper's log is only exposed through a tree like interface. It can be hard to shoehorn your application into this.
2. A zookeeper ensemble of multiple machines is limited to one log. You may want one log per resource, which will become expensive very quickly.
3. Adding extra machines to a zookeeper ensemble does not increase capacity nor throughput.

Bookkeeper can be viewed as a means of exposing zookeeper's replicated log to applications in a scalable fashion. However, we still use zookeeper to maintain consistency guarantees.

TL;DR You need to elect a leader instance

## Electing a leader

We'll use zookeeper to elect a leader. A zookeeper instance will have started locally when you started the localbookie application above. To verify it's running, run the following command.

```shell
$ echo stat | nc localhost 2181
Zookeeper version: 3.4.6-1569965, built on 02/20/2014 09:09 GMT
Clients:
 /127.0.0.1:59343[1](queued=0,recved=40,sent=41)
 /127.0.0.1:49354[1](queued=0,recved=11,sent=11)
 /127.0.0.1:49361[0](queued=0,recved=1,sent=0)
 /127.0.0.1:59344[1](queued=0,recved=38,sent=39)
 /127.0.0.1:59345[1](queued=0,recved=38,sent=39)
 /127.0.0.1:59346[1](queued=0,recved=38,sent=39)

Latency min/avg/max: 0/0/23
Received: 167
Sent: 170
Connections: 6
Outstanding: 0
Zxid: 0x11
Mode: standalone
Node count: 16
```

To interact with zookeeper, we'll use the Curator client rather than the stock zookeeper client. Getting things right with the zookeeper client can be tricky, and curator removes a lot of the pointy corners for you. In fact, curator even provides a leader election recipe, so we need to do very little work to get leader election in our application.

```java
public class Dice extends LeaderSelectorListenerAdapter implements Closeable {

    final static String ZOOKEEPER_SERVER = "127.0.0.1:2181";
    final static String ELECTION_PATH = "/dice-elect";

    ...

    Dice() throws InterruptedException {
        curator = CuratorFrameworkFactory.newClient(ZOOKEEPER_SERVER,
                2000, 10000, new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        leaderSelector = new LeaderSelector(curator, ELECTION_PATH, this);
        leaderSelector.autoRequeue();
        leaderSelector.start();
    }
```

In the constructor for Dice, we need to create the curator client. We specify four things when creating the client, the location of the zookeeper service, the session timeout, the connect timeout and the retry policy.

The session timeout is a zookeeper concept. If the zookeeper server doesn't hear anything from the client for this amount of time, any leases which the client holds will be timed out. This is important in leader election. For leader election, the curator client will take a lease on ELECTION_PATH. The first instance to take the lease will become leader and the rest will become followers. However, their claim on the lease will remain in the cue. If the first instance then goes away, due to a crash etc., its session will timeout. Once the session times out, the lease will be released and the next instance in the queue will become the leader. The call to autoRequeue() will make the client queue itself again if it loses the lease for some other reason, such as if it was still alive, but it a garbage collection cycle caused it to lose its session, and thereby its lease. I've set the lease to be quite low so that when we test out leader election, transitions will be quite quick. The optimum length for session timeout depends very much on the use case. The other parameters are the connection timeout, i.e. the amount of time it will spend trying to connect to a zookeeper server before giving up, and the retry policy. The retry policy specifies how the client should respond to transient errors, such as connection loss. Operations that fail with transient errors can be retried, and this argument specifies how often the retries should occur.

Finally, you'll have noticed that Dice now extends LeaderSelectorListenerAdapter and implements Closeable. Closeable is there to close the resource we have initialized in the constructor, the curator client and the leaderSelector. LeaderSelectorListenerAdapter is a callback that the leaderSelector uses to notify the instance that it is now the leader. It is passed as the third argument to the LeaderSelector constructor.

```java
    @Override
    public void takeLeadership(CuratorFramework client)
            throws Exception {
        synchronized (this) {
            leader = true;
            try {
                while (true) {
                    this.wait();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                leader = false;
            }
        }
    }
```

takeLeadership() is the callback called by LeaderSelector when the instance is leader. It should only return when the instance wants to give up leadership. In our case, we never do so we wait on the current object until we're interrupted. To signal to the rest of the program that we are leader we set a volatile boolean called leader to true. This is unset after we are interrupted.

```java
    void playDice() throws InterruptedException {
        while (true) {
            while (leader) {
                Thread.sleep(1000);
                System.out.println("Value = " + (r.nextInt(6) + 1)
                                   + ", isLeader = " + leader);
            }
        }
    }
```

Finally we modify playDice() to only generate random numbers when it is the leader.

Run two instances of the program in two different terminals. You'll see that one becomes leader and prints numbers and the other just sits there.

Now stop the leader using Control-Z. This will pause the process, but it won't kill it. You will be dropped back to the shell in that terminal. After a couple of seconds, the session timeout, you will see that the other instance has become the leader. Zookeeper will guarantee that only one instance is selected as leader at any time.

Now go back to the shell that the original leader was on and wake up the process using fg. You'll see something like the following:

```shell
...
...
Value = 4, isLeader = true
Value = 4, isLeader = true
^Z
[1]+  Stopped                 mvn exec:java -Dexec.mainClass=org.apache.bookkeeper.Dice
$ fg
mvn exec:java -Dexec.mainClass=org.apache.bookkeeper.Dice
Value = 3, isLeader = true
Value = 1, isLeader = false
```