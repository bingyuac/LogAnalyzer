package com.davidnewberger

import kafka.serializer.StringDecoder
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.{SparkConf, SparkContext}


object LogAnalyzer extends Serializable {


  // Schema for Apache access logs
  case class ApacheAccessLog(ipAddress: String,
                             clientIdentd: String,
                             userId: String,
                             dateTime: String,
                             method: String,
                             endpoint: String,
                             protocol: String,
                             responseCode: String,
                             contentSize: String,
                             referer: String,
                             userAgent: String)

  // Function to parse log line into ApacheAccessLog case class
  // Looks for a specific pattern in this case the Apache Combined Log Format
  val PATTERN = """^(\S+) (\S+) (\S+) \[([\w:/]+\s[+\-]\d{4})\] "(\S+) (\S+) (\S+)" (\d{3}) (\S+) "(\S+)" "([^"]*)"""".r

  def parseLogLine(log: String): ApacheAccessLog = {
    val res = PATTERN.findFirstMatchIn(log)
    // Checks to see if the val res is empty
    if (res.isEmpty) {
      throw new RuntimeException("Cannot parse log line: " + log)
    }
    // Value m is matched to 1 of 11 different groups which correspond to pattern
    val m = res.get
    ApacheAccessLog(m.group(1),
      m.group(2),
      m.group(3),
      m.group(4),
      m.group(5),
      m.group(6),
      m.group(7),
      m.group(8),
      m.group(9),
      m.group(10),
      m.group(11))
  }
  // How big a batch is
  val BATCH_INTERVAL = Duration(5 * 1000)
  // How big of a window of time to capture (must be a multiple of the batch interval)
  // Value is currently in milliseconds
  val WINDOW_LENGTH = Duration(30 * 1000)
  // How often to update the statistics (also must be a multiple of the batch interval
  // Value is also in milliseconds
  val SLIDE_INTERVAL = Duration(10 * 1000)


  def main(args: Array[String]) {

    // Runs locally with as many threads a cores on the machine
    val master = "local[*]"
    val sparkConf = new SparkConf().setMaster(master).setAppName("Log Analyzer")
    val sc = new SparkContext(sparkConf)
    // Sets up spark streaming and gives it the amount of time to run each batch
    val ssc = new StreamingContext(sc, BATCH_INTERVAL)
    // The Kafka Broker we are connecting to
    val brokers = "localhost:9092"
    // Where to start if there is no offset in Zookeeper or it's out of range
    val offsetReset = "smallest"
    // The topic to subscribe to for the data to process
    val topics = Set("weblog")
    // Setting the parameters needed to talk with Kafka
    val kafkaParams = Map[String, String](
      "metadata.broker.list" -> brokers,
      "zookeeper.connect" -> "localhost:2181",
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG ->
        "org.apache.common.serialization.StringDeserializer",
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG ->
        "org.apache.common.serialization.StringDeserializer",
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> offsetReset,
      //If this is true will periodically commit offset of message to Zookeeper. Would then be used when the process fails
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false"
    )

    // TODO: Make checkpointing work!

    /**
      * Checkpointing needs to be enabled to use updateStateByKey
      * This would usually point to something like HDFS or S3 to ensure reliable fault-tolerance
      */
    ssc.checkpoint("/tmp/checkpoint")

    /**
      * Will query Kafka peridocially for the latest offsets in each topic and partition
      */
    val logLines = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](ssc, kafkaParams, topics)
    /**
      * This creates a DStream of Access Log lines
      */
    val logDStream = logLines.map(_._2).map(parseLogLine).cache()
    /**
      * Creates a windowed rdd by splitting the logDStream into time based rdd's
      */
    val windowedLogLines = logDStream.window(WINDOW_LENGTH, SLIDE_INTERVAL)

    /**
      * Gets the ipAddressCount, then updateStateByKey to accumulate
      * the ip address count for all time
      * finally it filters it to only those seen in 30 second window
      */
    val ipAddressDStream = logDStream
      .transform(ipAddressCount)
      .updateStateByKey(runningSum)
      .transform(filterIPAddress)
    ipAddressDStream.foreachRDD(rdd => {
      if (!rdd.isEmpty())
          rdd
            .repartition(1)
            .saveAsTextFile("/tmp/DDoSDetector/SuspectIPs")

    })

    ssc.start()
    ssc.awaitTermination()
  }

  // TODO: Handle Producer offline for extended period of time

  /**
    * Stores and computes the running sum of the IP addresses
    */
  def runningSum = (values: Seq[Long], state: Option[Long]) => {
    val currentCount = values.foldLeft(0L)(_ + _)
    val previousCount = state.getOrElse(0L)
    Some(currentCount + previousCount)
  }

  /**
    * We count the number of times an IP Address is present in the stream for a given window
    */
  def ipAddressCount = (accessLogRDD: RDD[ApacheAccessLog]) => {
    accessLogRDD.map(log => (log.ipAddress, 1L)).reduceByKey(_ + _)
  }

  /**
    * We filter the results down to IP Addresses which appear more than 30 times
    * in a given window
    */
  def filterIPAddress = (ipAddressCount: RDD[(String, Long)]) => {
    ipAddressCount.filter(_._2 > 30).map(_._1)
  }
}