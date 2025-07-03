import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.StreamingQuery
import org.apache.spark.sql.ForeachWriter
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}

object KafkaEventHubProcessor {
  
  // Configuration
  val eventHubConnectionString = "your-eventhub-connection-string"
  val eventHubEndpoint = "your-eventhub-endpoint:9093"
  val consumerGroup = "your-consumer-group"
  
  // Spark Session
  val spark = SparkSession.builder()
    .appName("KafkaEventHubProcessor")
    .config("spark.streaming.stopGracefullyOnShutdown", "true")
    .getOrCreate()
  
  import spark.implicits._
  
  // Shutdown Monitor Class
  class ShutdownMonitor {
    private val shutdownRequested = new AtomicBoolean(false)
    
    def startMonitoring(): Future[StreamingQuery] = Future {
      println("Starting shutdown monitor...")
      
      val shutdownStream = spark.readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", eventHubEndpoint)
        .option("subscribe", "shutdown_topic")
        .option("kafka.security.protocol", "SASL_SSL")
        .option("kafka.sasl.mechanism", "PLAIN")
        .option("kafka.sasl.jaas.config", s"org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$$ConnectionString\" password=\"$eventHubConnectionString\";")
        .option("startingOffsets", "latest")
        .load()
      
      val query = shutdownStream
        .selectExpr("CAST(value AS STRING) as message")
        .writeStream
        .outputMode("append")
        .foreach(new ForeachWriter[org.apache.spark.sql.Row] {
          def open(partitionId: Long, version: Long): Boolean = {
            println(s"Opening partition $partitionId for shutdown monitoring")
            true
          }
          
          def process(row: org.apache.spark.sql.Row): Unit = {
            val message = row.getAs[String]("message")
            println(s"Received shutdown message: $message")
            
            if (message.toLowerCase.contains("shutdown sales")) {
              println("Shutdown signal detected! Setting shutdown flag...")
              shutdownRequested.set(true)
            }
          }
          
          def close(errorOrNull: Throwable): Unit = {
            if (errorOrNull != null) {
              println(s"Error in shutdown monitor: ${errorOrNull.getMessage}")
            }
          }
        })
        .start()
      
      query
    }
    
    def isShutdownRequested: Boolean = shutdownRequested.get()
    
    def reset(): Unit = shutdownRequested.set(false)
  }
  
  // Main Event Hub Processor Class
  class EventHubProcessor {
    private val shutdownMonitor = new ShutdownMonitor()
    private var activeQueries: List[StreamingQuery] = List.empty
    private var shutdownQuery: Option[StreamingQuery] = None
    
    def processEventHubData(): Unit = {
      try {
        println("Starting Event Hub data processing...")
        
        // Start shutdown monitoring
        val shutdownFuture = shutdownMonitor.startMonitoring()
        shutdownFuture.onComplete {
          case Success(query) => 
            shutdownQuery = Some(query)
            println("Shutdown monitor started successfully")
          case Failure(ex) => 
            println(s"Failed to start shutdown monitor: ${ex.getMessage}")
        }
        
        // Wait a bit for shutdown monitor to initialize
        Thread.sleep(2000)
        
        // Process bangalore.apple topic
        println("Starting apple data processing...")
        val appleQuery = createStreamingQuery("bangalore.apple", processAppleData)
        
        // Process bangalore.curd topic  
        println("Starting curd data processing...")
        val curdQuery = createStreamingQuery("bangalore.curd", processCurdData)
        
        activeQueries = List(appleQuery, curdQuery)
        
        // Monitor for shutdown while processing
        monitorAndProcess()
        
      } catch {
        case ex: Exception =>
          println(s"Error in main processing: ${ex.getMessage}")
          ex.printStackTrace()
          gracefulShutdown()
      }
    }
    
    private def createStreamingQuery(topic: String, processor: String => Unit): StreamingQuery = {
      val stream = spark.readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", eventHubEndpoint)
        .option("subscribe", topic)
        .option("kafka.security.protocol", "SASL_SSL")
        .option("kafka.sasl.mechanism", "PLAIN")
        .option("kafka.sasl.jaas.config", s"org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$$ConnectionString\" password=\"$eventHubConnectionString\";")
        .option("startingOffsets", "latest")
        .option("maxOffsetsPerTrigger", "1000")
        .load()
      
      stream.selectExpr("CAST(value AS STRING) as message", "timestamp", "topic", "partition", "offset")
        .writeStream
        .outputMode("append")
        .foreach(new ForeachWriter[org.apache.spark.sql.Row] {
          def open(partitionId: Long, version: Long): Boolean = {
            println(s"Opening partition $partitionId for topic $topic")
            true
          }
          
          def process(row: org.apache.spark.sql.Row): Unit = {
            val message = row.getAs[String]("message")
            val timestamp = row.getAs[java.sql.Timestamp]("timestamp")
            val partition = row.getAs[Int]("partition")
            val offset = row.getAs[Long]("offset")
            
            println(s"Processing from $topic [partition=$partition, offset=$offset] at $timestamp")
            processor(message)
          }
          
          def close(errorOrNull: Throwable): Unit = {
            if (errorOrNull != null) {
              println(s"Error in $topic processing: ${errorOrNull.getMessage}")
            }
          }
        })
        .start()
    }
    
    private def monitorAndProcess(): Unit = {
      println("Starting main monitoring loop...")
      
      while (!shutdownMonitor.isShutdownRequested && activeQueries.exists(_.isActive)) {
        // Check status of all queries
        val activeCount = activeQueries.count(_.isActive)
        val inactiveQueries = activeQueries.filterNot(_.isActive)
        
        if (inactiveQueries.nonEmpty) {
          println(s"Found ${inactiveQueries.size} inactive queries")
          inactiveQueries.foreach { query =>
            if (query.exception.isDefined) {
              println(s"Query failed with exception: ${query.exception.get}")
            }
          }
        }
        
        if (activeCount == 0) {
          println("All queries have stopped")
          break
        }
        
        // Sleep for a second before checking again
        Thread.sleep(1000)
      }
      
      if (shutdownMonitor.isShutdownRequested) {
        println("Shutdown signal received. Initiating graceful shutdown...")
        gracefulShutdown()
      } else {
        println("All queries stopped naturally")
      }
    }
    
    private def gracefulShutdown(): Unit = {
      println("Starting graceful shutdown process...")
      
      try {
        // Stop all active data processing queries
        activeQueries.foreach { query =>
          if (query.isActive) {
            println(s"Stopping query: ${query.name}")
            query.stop()
          }
        }
        
        // Wait for all queries to terminate
        activeQueries.foreach { query =>
          if (query.isActive) {
            println(s"Waiting for query ${query.name} to terminate...")
            query.awaitTermination(30000) // Wait up to 30 seconds
          }
        }
        
        // Stop shutdown monitor
        shutdownQuery.foreach { query =>
          if (query.isActive) {
            println("Stopping shutdown monitor...")
            query.stop()
            query.awaitTermination(10000) // Wait up to 10 seconds
          }
        }
        
        println("All streaming queries stopped successfully")
        
      } catch {
        case ex: Exception =>
          println(s"Error during graceful shutdown: ${ex.getMessage}")
          ex.printStackTrace()
      } finally {
        // Always stop Spark session
        println("Stopping Spark session...")
        spark.stop()
        println("Graceful shutdown completed")
      }
    }
  }
  
  // Data Processing Functions
  def processAppleData(data: String): Unit = {
    try {
      // Your apple data processing logic here
      println(s"Processing apple data: $data")
      
      // Example: Parse JSON, validate, transform, save to database
      // val appleData = parseAppleJson(data)
      // validateAppleData(appleData)
      // saveToDatabase(appleData)
      
    } catch {
      case ex: Exception =>
        println(s"Error processing apple data: ${ex.getMessage}")
        // Handle error - maybe send to dead letter queue
    }
  }
  
  def processCurdData(data: String): Unit = {
    try {
      // Your curd data processing logic here
      println(s"Processing curd data: $data")
      
      // Example: Parse JSON, validate, transform, save to database
      // val curdData = parseCurdJson(data)
      // validateCurdData(curdData)
      // saveToDatabase(curdData)
      
    } catch {
      case ex: Exception =>
        println(s"Error processing curd data: ${ex.getMessage}")
        // Handle error - maybe send to dead letter queue
    }
  }
  
  // Main Application Entry Point
  def main(args: Array[String]): Unit = {
    println("Starting Kafka Event Hub Processor...")
    
    // Add shutdown hook for graceful shutdown on SIGTERM
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        println("Received shutdown signal from OS")
        spark.stop()
      }
    })
    
    try {
      val processor = new EventHubProcessor()
      processor.processEventHubData()
    } catch {
      case ex: Exception =>
        println(s"Application failed: ${ex.getMessage}")
        ex.printStackTrace()
    } finally {
      println("Application terminated")
    }
  }
}

// Additional utility classes and configurations
object KafkaConfig {
  def getEventHubConfig(connectionString: String, endpoint: String): Map[String, String] = {
    Map(
      "kafka.bootstrap.servers" -> endpoint,
      "kafka.security.protocol" -> "SASL_SSL",
      "kafka.sasl.mechanism" -> "PLAIN",
      "kafka.sasl.jaas.config" -> s"org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$$ConnectionString\" password=\"$connectionString\";",
      "kafka.request.timeout.ms" -> "60000",
      "kafka.session.timeout.ms" -> "30000"
    )
  }
}

// Example case classes for data structures
case class AppleData(
  id: String,
  variety: String,
  quantity: Int,
  price: Double,
  timestamp: Long
)

case class CurdData(
  id: String,
  brand: String,
  volume: Double,
  price: Double,
  timestamp: Long
)

// Example JSON parsing utilities
object JsonParser {
  import scala.util.parsing.json._
  
  def parseAppleJson(json: String): Option[AppleData] = {
    try {
      JSON.parseFull(json) match {
        case Some(map: Map[String, Any]) =>
          Some(AppleData(
            id = map.get("id").map(_.toString).getOrElse(""),
            variety = map.get("variety").map(_.toString).getOrElse(""),
            quantity = map.get("quantity").map(_.toString.toInt).getOrElse(0),
            price = map.get("price").map(_.toString.toDouble).getOrElse(0.0),
            timestamp = map.get("timestamp").map(_.toString.toLong).getOrElse(System.currentTimeMillis())
          ))
        case _ => None
      }
    } catch {
      case ex: Exception =>
        println(s"Error parsing apple JSON: ${ex.getMessage}")
        None
    }
  }
  
  def parseCurdJson(json: String): Option[CurdData] = {
    try {
      JSON.parseFull(json) match {
        case Some(map: Map[String, Any]) =>
          Some(CurdData(
            id = map.get("id").map(_.toString).getOrElse(""),
            brand = map.get("brand").map(_.toString).getOrElse(""),
            volume = map.get("volume").map(_.toString.toDouble).getOrElse(0.0),
            price = map.get("price").map(_.toString.toDouble).getOrElse(0.0),
            timestamp = map.get("timestamp").map(_.toString.toLong).getOrElse(System.currentTimeMillis())
          ))
        case _ => None
      }
    } catch {
      case ex: Exception =>
        println(s"Error parsing curd JSON: ${ex.getMessage}")
        None
    }
  }
}
