# 🚀 Spark Structured Streaming with Kafka via SBT

---

## 📦 Prerequisites

- Apache Kafka installed and running on `localhost:9092`
- Kafka topic created (e.g., `test-topic`)
- Apache Spark 3.x installed
- SBT installed (v1.5+)
- Java 8 to 17 (⚠️ avoid Java 21 due to `IllegalAccessError`)

---

## 🛠️ Project Structure

```
kafka-spark-app/
├── build.sbt
├── project/
│   └── build.properties
└── src/
    └── main/
        └── scala/
            └── KafkaWordCount.scala
```

---

## 📄 `build.sbt`

```scala
ThisBuild / scalaVersion := "2.13.12"

ThisBuild / fork := true

ThisBuild / javaOptions ++= Seq(
  "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED"
)

lazy val root = (project in file("."))
  .settings(
    name := "kafka-spark-app",
    version := "0.1.0",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % "3.5.1",
      "org.apache.spark" %% "spark-sql" % "3.5.1",
      "org.apache.spark" %% "spark-sql-kafka-0-10" % "3.5.1"
    )
  )

```

---

## 📄 `project/build.properties`

```properties
sbt.version=1.9.7
```

---

## 📄 `src/main/scala/KafkaWordCount.scala`

```scala
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object KafkaWordCount {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder
      .appName("Kafka Spark WordCount")
      .master("local[*]")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "localhost:9092")
      .option("subscribe", "test-topic")
      .option("startingOffsets", "earliest")
      .load()

    val stringDF = kafkaDF.selectExpr("CAST(value AS STRING)")

    val words = stringDF
      .select(explode(split(col("value"), " ")).as("word"))
      .groupBy("word")
      .count()

    val query = words.writeStream
      .outputMode("complete")
      .format("console")
      .option("checkpointLocation", "/tmp/kafka-spark-checkpoint")
      .start()

    query.awaitTermination()
  }
}
```

---

## ⚙️ Compile and Run with SBT

### ✅ To compile the project:
```bash
sbt compile
```

### ▶️ To run the application:
```bash
sbt run
```

---

## 🧪 Kafka Quick Commands (Optional)

```bash
# Start Kafka server
kafka-server-start /opt/homebrew/etc/kafka/server.properties

# Create topic
kafka-topics --create \
  --topic test-topic \
  --bootstrap-server localhost:9092 \
  --partitions 1 \
  --replication-factor 1

# Start Kafka producer
kafka-console-producer --topic test-topic --bootstrap-server localhost:9092

# Start Kafka consumer (debug only)
kafka-console-consumer --topic test-topic --bootstrap-server localhost:9092 --from-beginning
```

---

## ❌ Stopping Kafka

```bash
# If started manually
CTRL+C

# If started using brew
brew services stop kafka
```

---

## ✅ Output

You will see streaming word counts printed to the console based on messages in Kafka topic `test-topic`.

---

🎉 Done! You now have Spark Structured Streaming integrated with Kafka using pure SBT.
