# Distributed File System

A fault-tolerant distributed file storage system built in Java, developed as coursework for **COMP2207 – Distributed Systems and Networks** at the University of Southampton. The system implements a Controller-Dstore architecture with file replication, concurrent client handling, and crash recovery.

## Architecture

```
┌────────┐  ┌────────┐  ┌────────┐
│Client 1 │  │Client 2 │  │Client N │
└───┬─────┘  └───┬─────┘  └───┬─────┘
    │            │            │
    └────────────┼────────────┘
                 │
          ┌──────▼──────┐
          │  Controller  │
          │  (Index +    │
          │   Routing)   │
          └──────┬───────┘
                 │
    ┌────────────┼────────────┐
    │            │            │
┌───▼───┐  ┌────▼───┐  ┌────▼───┐
│Dstore 1│  │Dstore 2│  │Dstore 3│
│  :4001 │  │  :4002 │  │  :4003 │
└────────┘  └────────┘  └────────┘
```

- **Controller** — Central coordinator that maintains a file index, routes client requests, and tracks Dstore availability
- **Dstore** — Storage nodes that hold actual file data, supporting replication across multiple nodes
- **Client** — Issues STORE, LOAD, LIST, and REMOVE operations against the Controller

## Features

- **File replication** — Files are stored across R Dstore nodes for redundancy
- **Concurrent operations** — Multi-threaded design handles simultaneous client requests using `ConcurrentHashMap` and thread-per-connection model
- **Fault tolerance** — System continues operating when individual Dstores crash or disconnect
- **Acknowledgement protocol** — Controller waits for store/remove ACKs from all participating Dstores before confirming operations to clients
- **Configurable parameters** — Replication factor (R), timeout duration, and rebalance period are all configurable at startup

## Protocol

| Command       | Direction          | Description                                    |
|---------------|--------------------|------------------------------------------------|
| `JOIN`        | Dstore → Controller | Dstore registers itself with the Controller   |
| `STORE`       | Client → Controller | Request to store a file                       |
| `STORE_TO`    | Controller → Client | Controller assigns target Dstores             |
| `STORE_ACK`   | Dstore → Controller | Confirms file stored successfully             |
| `STORE_COMPLETE` | Controller → Client | All replicas stored                        |
| `LOAD`        | Client → Controller | Request to load a file                        |
| `LOAD_FROM`   | Controller → Client | Directs client to a Dstore                   |
| `REMOVE`      | Client → Controller | Request to remove a file                      |
| `REMOVE_ACK`  | Dstore → Controller | Confirms file removed                        |
| `REMOVE_COMPLETE` | Controller → Client | All replicas removed                      |
| `LIST`        | Client → Controller | Lists all stored files                        |

## Tech Stack

- **Language:** Java
- **Concurrency:** `java.util.concurrent` (ConcurrentHashMap, ExecutorService, AtomicInteger)
- **Networking:** Java Sockets (TCP)
- **I/O:** `java.nio.file` for file operations

## Usage

### Compile

```bash
cd src
javac *.java
```

### Start Controller

```bash
java Controller <cport> <R> <timeout> <rebalance_period>
```

- `cport` — Controller port number
- `R` — Replication factor (number of Dstores each file is stored on)
- `timeout` — Timeout in milliseconds for ACK responses
- `rebalance_period` — Period in seconds for rebalance operations

### Start Dstore(s)

```bash
java Dstore <port> <cport> <timeout> <file_folder>
```

- `port` — Port this Dstore listens on
- `cport` — Controller port to connect to
- `timeout` — Timeout in milliseconds
- `file_folder` — Local directory for storing files

### Example

```bash
# Terminal 1: Start Controller (port 4000, replication factor 3, 2s timeout, 10s rebalance)
java Controller 4000 3 2000 10

# Terminals 2-4: Start 3 Dstores
java Dstore 4001 4000 2000 ./store1
java Dstore 4002 4000 2000 ./store2
java Dstore 4003 4000 2000 ./store3
```

## Coursework Context

Built for **COMP2207 – Distributed Systems and Networks** at the University of Southampton. The system was tested under real-world failure conditions including Dstore crashes during operations and concurrent client requests to validate fault tolerance and consistency guarantees.
