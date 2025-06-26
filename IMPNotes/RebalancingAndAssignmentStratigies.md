# Kafka Consumer Assignment Strategies & Rebalancing Guide

## What are Kafka Assignment Strategies?

When multiple consumers are part of the same **consumer group**, Kafka needs to assign partitions of a topic to these consumers. The logic that determines which consumer gets which partitions is called the **assignment (or partition assignment) strategy**.

## Built-in Assignment Strategies

Kafka provides four main built-in strategies:

### 1. RangeAssignor (Default)
- **Class Name**: `org.apache.kafka.clients.consumer.RangeAssignor`
- **Behavior**: Assigns consecutive partitions to consumers, sorted by topic
- **Best for**: Simple scenarios with single topics

### 2. RoundRobinAssignor
- **Class Name**: `org.apache.kafka.clients.consumer.RoundRobinAssignor`
- **Behavior**: Evenly distributes partitions across consumers **regardless of topic**
- **Best for**: Multiple topics scenarios

### 3. StickyAssignor
- **Class Name**: `org.apache.kafka.clients.consumer.StickyAssignor`
- **Behavior**: Tries to minimize partition movement between rebalances while maintaining balance
- **Best for**: Stability during rebalances (legacy)

### 4. CooperativeStickyAssignor (Recommended)
- **Class Name**: `org.apache.kafka.clients.consumer.CooperativeStickyAssignor`
- **Behavior**: Same as Sticky but allows incremental rebalancing
- **Best for**: Modern Kafka deployments with smooth scaling

## Configuration

### Properties File
```properties
partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor
```

### Java Code
```java
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
          List.of(CooperativeStickyAssignor.class.getName()));
```

## Assignment Strategy Examples

### Scenario Setup
- **2 Topics**: `topicA` and `topicB`, each with 4 partitions (P0, P1, P2, P3)
- **4 Consumers**: C1, C2, C3, C4 in the same consumer group
- **Total Partitions**: 8

### RoundRobinAssignor Assignment
The "regardless of topic" behavior means partitions are treated as one big list:

| Partition | Assigned To |
|-----------|-------------|
| topicA-P0 | C1 |
| topicA-P1 | C2 |
| topicA-P2 | C3 |
| topicA-P3 | C4 |
| topicB-P0 | C1 |
| topicB-P1 | C2 |
| topicB-P2 | C3 |
| topicB-P3 | C4 |

**Result**: Each consumer gets 2 partitions, possibly from different topics.

### RangeAssignor Assignment
Groups partitions by topic first, then assigns consecutive partitions:

**TopicA**: 
- C1: P0, P1
- C2: P2, P3

**TopicB**:
- C3: P0, P1  
- C4: P2, P3

**Result**: Some consumers may only consume from one topic.

## Understanding StickyAssignor

### Goals
1. **Even distribution** of partitions (like RoundRobin)
2. **Minimize partition movement** during rebalances

### Example: Consumer Leaves
**Initial Assignment**:
- C1: P0, P3
- C2: P1, P4  
- C3: P2, P5

**When C3 leaves**:

**With RoundRobin** (total reshuffle):
- C1: P0, P2
- C2: P1, P3, P4, P5

**With StickyAssignor** (minimal movement):
- C1: P0, P3 (unchanged)
- C2: P1, P2, P4, P5 (only gets C3's partitions)

## Rebalancing Behavior Comparison

| Feature | RangeAssignor | RoundRobinAssignor | StickyAssignor | CooperativeStickyAssignor |
|---------|---------------|-------------------|----------------|---------------------------|
| Partition balancing | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes |
| Minimizes reassignment | ❌ No | ❌ No | ✅ Yes | ✅ Yes |
| Rebalancing behavior | Stop-the-world | Stop-the-world | Stop-the-world | Incremental |
| Downtime during rebalance | ❌ Yes | ❌ Yes | ❌ Yes | ✅ Minimal |
| Revoke all on rebalance | ✅ Yes | ✅ Yes | ✅ Yes | ❌ Partial only |
| Production recommended | 🚫 Basic use | 🚫 Basic use | 🚫 Legacy | ✅ Yes |

## The Rebalancing Process: Complete Lifecycle

### What Triggers a Rebalance?
- A new consumer joins the group
- An existing consumer leaves or crashes
- Subscription or topic metadata changes

### Step-by-Step Rebalancing Process

#### 1. Rebalance Triggered
- Kafka Group Coordinator detects the change
- Initiates rebalancing process

#### 2. Consumers Enter Rebalance Phase
- All consumers in the group:
  - Revoke their current partitions
  - Enter "rebalancing" state  
  - **Pause message consumption** (stop-the-world moment begins)

#### 3. Join Group Protocol
- Each consumer sends `JoinGroupRequest` to Kafka Group Coordinator
- One consumer is chosen as the **group leader**
- All consumers share:
  - Their subscribed topics
  - Current metadata

#### 4. Leader Computes Partition Assignments
- The group leader:
  - Applies the configured partition assignment strategy
  - Decides which consumer gets which partitions
  - Sends `SyncGroupRequest` to the coordinator

#### 5. Coordinator Applies Assignment
- The coordinator:
  - Sends back assignments to each consumer
  - Consumers update their partition ownership

#### 6. Consumers Resume Work
- Consumers:
  - Start fetching messages from newly assigned partitions
  - Resume processing
  - **Rebalance complete** ✅

### Rebalancing Participants

| Step | What Happens | Who's Involved |
|------|-------------|----------------|
| 1️⃣ | Trigger | Kafka broker (group coordinator) |
| 2️⃣ | Revoke | All consumers in the group |
| 3️⃣ | JoinGroup | All consumers |
| 4️⃣ | Assign | Group leader |
| 5️⃣ | Sync & Confirm | Kafka broker |
| 6️⃣ | Resume | All consumers |

## Key Problems with Traditional Rebalancing

### Stop-the-World Issue
In traditional strategies (Range, RoundRobin, Sticky):
- Kafka revokes **all partitions** from **all consumers**
- Even if only one consumer joins/leaves
- Causes processing delays and potential data lag

### Performance Impact
- ⛔ Processing downtime
- ⛔ High latency spikes  
- ⛔ Potential reprocessing of messages
- ⛔ Consumer lag increases

## CooperativeStickyAssignor: The Modern Solution

### Key Advantages
- **Incremental rebalancing**: Only necessary partitions are revoked
- **Minimal downtime**: Other partitions stay assigned and active
- **Smooth scaling**: New consumers take only what's needed
- **Better performance**: Maintains throughput during rebalances

### When to Use CooperativeStickyAssignor
✅ **Use when you**:
- Care about low-latency stream processing
- Want minimal disruption when scaling consumers up/down
- Are using Kafka 2.4 or later
- Need production-grade stability

## Strategy Selection Guide

| Use Case | Recommended Strategy |
|----------|---------------------|
| Simple, single topic | RangeAssignor |
| Multiple topics | RoundRobinAssignor |
| Need stability during rebalances (legacy) | StickyAssignor |
| Modern production deployment | **CooperativeStickyAssignor** |
| High-throughput, low-latency requirements | **CooperativeStickyAssignor** |
| Frequent scaling operations | **CooperativeStickyAssignor** |

## Best Practices

1. **Use CooperativeStickyAssignor** for new applications
2. **Monitor rebalance frequency** and duration
3. **Configure appropriate session timeouts** to avoid unnecessary rebalances
4. **Plan consumer scaling** to minimize rebalance impact
5. **Test assignment strategies** under your specific workload conditions

---

*This guide covers the essential concepts of Kafka consumer assignment strategies and rebalancing. Understanding these concepts is crucial for building robust, scalable Kafka applications.*
