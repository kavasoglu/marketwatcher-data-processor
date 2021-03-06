import java.util.{Date, UUID}

import com.datastax.driver.core.utils.UUIDs
import com.datastax.spark.connector.SomeColumns
import com.datastax.spark.connector.streaming._
import org.apache.spark._
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.{ReceiverInputDStream, DStream}
import org.apache.spark.streaming.kafka.KafkaUtils

object TwitterProcessor {

  var batch_interval_in_seconds = 10
  var window_in_minutes = 60
  var slide_in_seconds = 10
  var cassandraHost = null

  def main(args: Array[String]) {
    val Array(kafkaZooKeeper, group, topics, numThreads, cassandraHost) = Array(sys.env("KAFKA_ZOO_KEEPER"), sys.env("GROUP"), sys.env("TOPICS"), sys.env("NUM_THREADS"), sys.env("CASSANDRA_NODES"))
    val ssc: StreamingContext = new StreamingContext(createSparkConf().set("spark.cassandra.connection.host", cassandraHost), Seconds(batch_interval_in_seconds))
    ssc.checkpoint("checkpoint")

    val topicMap = topics.split(",").map((_, numThreads.toInt)).toMap

    val lines = KafkaUtils.createStream(ssc, kafkaZooKeeper, group, topicMap).map(_._1)
    val countStream = getCountStreamOfTweets(lines)

    saveToDb(countStream)

    ssc.start()
    ssc.awaitTermination()
  }

  def createSparkConf(): SparkConf = {
    new SparkConf().setMaster("local[*]").setAppName("data-processor: ")
  }

  def getCountStreamOfTweets(tweets: DStream[(String)]): DStream[(String, Long)] = {
    tweets.countByValueAndWindow(Minutes(window_in_minutes), Seconds(slide_in_seconds))
  }

  def saveToDb(countStream: DStream[(String, Long)]) = {
    val toDb = countStream.map { case (alertId: String, count: Long) => (UUIDs.timeBased(), UUID.fromString(alertId), count, new Date())}
    toDb.saveToCassandra("trends", "trend", SomeColumns("id", "alert_id", "count", "process_date"))
  }
}

