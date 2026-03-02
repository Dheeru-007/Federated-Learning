# Federated ML — Java-Based Distributed System
## Privacy-Preserving Healthcare Analytics

Based on: **Ren, Li & Wu (2024)** — Privacy-Preserving Data Analysis Using Federated Learning
DOI: 10.69987/AIMLR.2024.50104

---

## Build

```bash
cd federated-learning-java
mvn package -q
```
This produces `target/federated-learning-java.jar`

---

## Run — Distributed (Separate Terminals)

### Terminal 1 — Start the Server
```bash
java -cp target/federated-learning-java.jar com.fl.runner.ServerRunner 9090 3 20
#                                                                        ^    ^  ^
#                                                                      port  min  max
#                                                                           clients rounds
```
Server waits until 3 hospital clients connect, then training begins automatically.

---

### Terminal 2 — Hospital 1
```bash
java -cp target/federated-learning-java.jar com.fl.runner.ClientRunner 1 localhost 9090
#                                                                       ^     ^       ^
#                                                                  hospitalId host   port
```

### Terminal 3 — Hospital 4
```bash
java -cp target/federated-learning-java.jar com.fl.runner.ClientRunner 4 localhost 9090
```

### Terminal 4 — Hospital 7
```bash
java -cp target/federated-learning-java.jar com.fl.runner.ClientRunner 7 localhost 9090
```

Training starts as soon as all 3 clients connect. Each client independently trains and sends updates.

---

## Run on Different Machines (Real Distributed)

```
Machine A — Server:     ServerRunner 9090 3 20
Machine B — Hospital 1: ClientRunner 1 <serverIP> 9090
Machine C — Hospital 4: ClientRunner 4 <serverIP> 9090
Machine D — Hospital 7: ClientRunner 7 <serverIP> 9090
```

---

## What You See Per Terminal

### Server Terminal:
```
[Server] Listening on port 9090...
[Server] Waiting for 3 clients...
[Server] ✓ Registered: Hospital-1  (total: 1)
[Server] ✓ Registered: Hospital-4  (total: 2)
[Server] ✓ Registered: Hospital-7  (total: 3)
[Server] 3 clients connected. Starting training!

══ ROUND 1/20 ══
[Server] Global model sent. Waiting for updates...
[Server] ← Update from Hospital-1  round=1  size=8,432 bytes
[Server] ← Update from Hospital-4  round=1  size=8,432 bytes
[Server] ← Update from Hospital-7  round=1  size=8,432 bytes
[Server] ✓ Round 1 — Accuracy: 74.32%  Loss: 0.5821
...
[Server] ✓ Round 15 — Accuracy: 91.84%  Loss: 0.2743
[Server] Model converged at round 16!
[Server] TRAINING COMPLETE
```

### Client Terminal (Hospital-1):
```
[Hospital-1] Connected!
[Hospital-1] Registered. Welcome! Registered clients: 1
[Hospital-1] Waiting for training rounds...

[Hospital-1] ══ Round 1 ══
[Hospital-1] Training locally on 5,432 private samples (7 epochs)...
[Hospital-1] Local accuracy: 72.18%  (1823ms)
[Hospital-1] DP applied — ε_round=0.0041  ε_total=0.0041
[Hospital-1] → Sent encrypted update (8,432 bytes)
[Hospital-1] ← Server result: accuracy=74.32%  loss=0.5821
...
[Hospital-1] FEDERATED TRAINING COMPLETE
[Hospital-1] Final model accuracy (local test): 91.12%
```

---

## Hospital IDs and Dataset Sizes

| Hospital ID | Type         | Records  |
|-------------|--------------|----------|
| 0           | Urban        | ~7,500 (used as server validation) |
| 1           | Urban        | ~6,500 |
| 2           | Urban        | ~6,000 |
| 3           | Suburban     | ~5,500 |
| 4           | Suburban     | ~5,000 |
| 5           | Suburban     | ~5,000 |
| 6           | Rural        | ~4,500 |
| 7           | Rural        | ~4,000 |
| 8           | Rural        | ~3,500 |
| 9           | Specialty    | ~2,500 |

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    NETWORK BOUNDARY                         │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              FL AGGREGATION SERVER                   │  │
│  │  - Listens on TCP port 9090                          │  │
│  │  - RSA-2048 key pair                                 │  │
│  │  - FedAvg aggregation + Byzantine filter             │  │
│  │  - Global model evaluation each round                │  │
│  └─────────────────────┬────────────────────────────────┘  │
│                         │ AES-256-GCM encrypted             │
│              ┌──────────┼──────────┐                        │
│              │          │          │                        │
│    ┌─────────┴──┐ ┌─────┴──────┐ ┌┴───────────┐           │
│    │ Hospital-1 │ │ Hospital-4 │ │ Hospital-7 │           │
│    │            │ │            │ │            │           │
│    │ Own data   │ │ Own data   │ │ Own data   │           │
│    │ Local train│ │ Local train│ │ Local train│           │
│    │ DP noise   │ │ DP noise   │ │ DP noise   │           │
│    │ Encrypt    │ │ Encrypt    │ │ Encrypt    │           │
│    └────────────┘ └────────────┘ └────────────┘           │
│                                                             │
│     Raw patient data NEVER crosses this boundary            │
└─────────────────────────────────────────────────────────────┘
```

---

## Privacy Parameters (Ren et al., 2024 — Table 2)

| Mechanism             | Δ     | σ     | ε    | δ    |
|-----------------------|-------|-------|------|------|
| Gradient Perturbation | 0.1   | 0.05  | 0.5  | 1e-5 |
| Aggregation           | 0.02  | 0.01  | 0.3  | 1e-6 |
| Evaluation            | 0.001 | 0.008 | 0.2  | 1e-7 |

---

## What This Adds Beyond the Base Paper

| Feature               | Base Paper | This System       |
|-----------------------|------------|-------------------|
| Language              | Python     | Java 11           |
| Distribution          | Simulation | Real TCP sockets  |
| Cryptography          | None       | AES-256-GCM + RSA |
| Digital signatures    | None       | SHA256withRSA     |
| Separate terminals    | No         | Yes               |
| Cross-machine support | No         | Yes               |
| Cloud dependency      | Yes        | None              |
