# 🚀 Spark Structured Streaming with Kafka (via spark-shell)

---

## 📆 Prerequisites

* Kafka server running on `localhost:9092`
* Topic created (e.g. `test-topic`)
* Spark installed (>= 3.x)

---

## 🔗 Step 1: Launch `spark-shell` with Kafka package

```bash
spark-shell \
  --packages org.apache.spark:spark-sql-kafka-0-10_2.13:3.5.1
```

**Note:**

* Use appropriate version matching your Spark version (e.g., 3.5.1 for Spark 3.5.x).
* Use Scala 2.13 version if you're on Spark compiled with Scala 2.13.

---

## 🔄 Step 2: Read Stream from Kafka

```scala
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

val spark = SparkSession.builder.getOrCreate()
spark.sparkContext.setLogLevel("WARN")

val kafkaDF = spark.readStream
  .format("kafka")
  .option("kafka.bootstrap.servers", "localhost:9092")
  .option("subscribe", "test-topic")
  .option("startingOffsets", "earliest")
  .load()

val stringDF = kafkaDF.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
```

---

## 💡 Step 3: Perform Transformations

You can transform `stringDF` as needed. Example:

```scala
val words = stringDF
  .select(explode(split(col("value"), " ")).as("word"))
  .groupBy("word")
  .count()
```

---

## 📃 Step 4: Write Stream to Console

```scala
val query = words.writeStream
  .outputMode("complete")
  .format("console")
  .option("checkpointLocation", "/tmp/kafka-spark-checkpoint")
  .start()

query.awaitTermination()
```

---

## ✅ Output

You should now see streaming word counts from Kafka messages printed to the console.

**Important:**

* Spark structured streaming processes **only new data** arriving **after** `.start()`.
* Make sure a Kafka producer sends messages after the stream begins.

---

## 🧪 Manual Test with Kafka Console Producer

Open a new terminal and run:

```bash
kafka-console-producer --topic test-topic --bootstrap-server localhost:9092
```

Type messages:

```
hello spark
hello kafka spark
```

You should now see word counts appear in the `spark-shell` output.

---
