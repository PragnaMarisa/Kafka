import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.streaming.{StreamingQuery, Trigger}
import org.apache.spark.sql.functions._
import org.apache.kafka.clients.consumer.ConsumerConfig
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}

object KafkaShutdownHandler {
  
  // Atomic boolean to track shutdown state
  private val shutdownRequested = new AtomicBoolean(false)
  
  // List to keep track of active streaming queries
  private var activeQueries: List[StreamingQuery] = List()
  
  def main(args: Array[String]): Unit = {
    
    // Initialize Spark Session
    val spark = SparkSession.builder()
      .appName("KafkaShutdownHandler")
      .config("spark.sql.streaming.checkpointLocation", "/tmp/checkpoint")
      .getOrCreate()
    
    import spark.implicits._
    
    // Kafka configuration
    val kafkaBootstrapServers = "localhost:9092" // Replace with your Kafka servers
    val eventhubConnectionString = "your-eventhub-connection-string" // Replace with EventHub connection
    
    try {
      // Start shutdown topic listener in separate thread
      implicit val ec: ExecutionContext = ExecutionContext.global
      Future {
        startShutdownListener(spark, kafkaBootstrapServers)
      }
      
      // Start data processing streams
      startDataProcessingStreams(spark, kafkaBootstrapServers, eventhubConnectionString)
      
      // Keep main thread alive and monitor for shutdown
      while (!shutdownRequested.get()) {
        Thread.sleep(5000)
        println("Application running... Active queries: " + activeQueries.size)
      }
      
      // Graceful shutdown
      println("Shutdown signal received. Stopping all streaming queries...")
      gracefulShutdown()
      
    } catch {
      case ex: Exception =>
        println(s"Error in main application: ${ex.getMessage}")
        ex.printStackTrace()
    } finally {
      spark.stop()
    }
  }
  
  /**
   * Starts the shutdown topic listener
   */
  def startShutdownListener(spark: SparkSession, kafkaServers: String): Unit = {
    import spark.implicits._
    
    val shutdownStream = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaServers)
      .option("subscribe", "shutdown_topic")
      .option("startingOffsets", "latest")
      .load()
      .select(col("value").cast("string").as("message"))
      .filter(col("message").contains("shutdown sales"))
    
    val shutdownQuery = shutdownStream
      .writeStream
      .outputMode("append")
      .trigger(Trigger.ProcessingTime("2 seconds"))
      .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
        if (!batchDF.isEmpty) {
          println(s"Shutdown signal received at batch $batchId")
          shutdownRequested.set(true)
        }
      }
      .start()
    
    // Add to active queries list
    synchronized {
      activeQueries = shutdownQuery :: activeQueries
    }
    
    println("Shutdown listener started successfully")
  }
  
  /**
   * Starts data processing streams for bangalore.apple and bangalore.curd topics
   */
  def startDataProcessingStreams(spark: SparkSession, kafkaServers: String, eventhubConnectionString: String): Unit = {
    import spark.implicits._
    
    // Stream 1: bangalore.apple topic
    val appleStream = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaServers)
      .option("subscribe", "bangalore.apple")
      .option("startingOffsets", "earliest")
      .load()
      .select(
        col("key").cast("string"),
        col("value").cast("string"),
        col("timestamp"),
        col("partition"),
        col("offset")
      )
    
    val appleQuery = appleStream
      .writeStream
      .outputMode("append")
      .format("console")
      .option("truncate", false)
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
        if (!shutdownRequested.get()) {
          println(s"Processing Apple data batch $batchId")
          // Your apple data processing logic here
          processAppleData(batchDF)
        }
      }
      .start()
    
    // Stream 2: bangalore.curd topic
    val curdStream = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaServers)
      .option("subscribe", "bangalore.curd")
      .option("startingOffsets", "earliest")
      .load()
      .select(
        col("key").cast("string"),
        col("value").cast("string"),
        col("timestamp"),
        col("partition"),
        col("offset")
      )
    
    val curdQuery = curdStream
      .writeStream
      .outputMode("append")
      .format("console")
      .option("truncate", false)
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
        if (!shutdownRequested.get()) {
          println(s"Processing Curd data batch $batchId")
          // Your curd data processing logic here
          processCurdData(batchDF)
        }
      }
      .start()
    
    // Add queries to active list
    synchronized {
      activeQueries = appleQuery :: curdQuery :: activeQueries
    }
    
    println("Data processing streams started successfully")
  }
  
  /**
   * Process apple data - customize this method based on your business logic
   */
  def processAppleData(df: DataFrame): Unit = {
    // Example processing - replace with your actual logic
    df.show(10, false)
    println("Apple data processed successfully")
  }
  
  /**
   * Process curd data - customize this method based on your business logic
   */
  def processCurdData(df: DataFrame): Unit = {
    // Example processing - replace with your actual logic
    df.show(10, false)
    println("Curd data processed successfully")
  }
  
  /**
   * Gracefully shutdown all streaming queries
   */
  def gracefulShutdown(): Unit = {
    println("Initiating graceful shutdown...")
    
    synchronized {
      activeQueries.foreach { query =>
        try {
          if (query.isActive) {
            println(s"Stopping query: ${query.name}")
            query.stop()
            query.awaitTermination(30000) // Wait up to 30 seconds
          }
        } catch {
          case ex: Exception =>
            println(s"Error stopping query ${query.name}: ${ex.getMessage}")
        }
      }
    }
    
    println("All queries stopped successfully")
  }
}
