/**
  * Copyright (C) 2016 Hurence (support@hurence.com)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package com.hurence.logisland.stream.spark

import java.io.ByteArrayInputStream
import java.util
import java.util.Collections

import com.hurence.logisland.component.{AllowableValue, PropertyDescriptor, RestComponentFactory}
import com.hurence.logisland.engine.EngineContext
import com.hurence.logisland.record.Record
import com.hurence.logisland.serializer._
import com.hurence.logisland.stream.{AbstractRecordStream, StreamContext}
import com.hurence.logisland.util.kafka.KafkaSink
import com.hurence.logisland.util.spark._
import com.hurence.logisland.validator.StandardValidators
import kafka.admin.AdminUtils
import kafka.utils.ZkUtils
import org.apache.avro.Schema.Parser
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord, OffsetAndMetadata, OffsetCommitCallback}
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.security.JaasUtils
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.groupon.metrics.UserMetricsSystem
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.{CanCommitOffsets, KafkaUtils, OffsetRange}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

object AbstractKafkaRecordStream {

    val DEFAULT_RAW_TOPIC = new AllowableValue("_raw", "default raw topic", "the incoming non structured topic")
    val DEFAULT_RECORDS_TOPIC = new AllowableValue("_records", "default events topic", "the outgoing structured topic")
    val DEFAULT_ERRORS_TOPIC = new AllowableValue("_errors", "default raw topic", "the outgoing structured error topic")
    val DEFAULT_METRICS_TOPIC = new AllowableValue("_metrics", "default metrics topic", "the topic to place processing metrics")

    val INPUT_TOPICS = new PropertyDescriptor.Builder()
        .name("kafka.input.topics")
        .description("Sets the input Kafka topic name")
        .required(true)
        .defaultValue(DEFAULT_RAW_TOPIC.getValue)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build

    val OUTPUT_TOPICS = new PropertyDescriptor.Builder()
        .name("kafka.output.topics")
        .description("Sets the output Kafka topic name")
        .required(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue(DEFAULT_RECORDS_TOPIC.getValue)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build

    val ERROR_TOPICS = new PropertyDescriptor.Builder()
        .name("kafka.error.topics")
        .description("Sets the error topics Kafka topic name")
        .required(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue(DEFAULT_ERRORS_TOPIC.getValue)
        .build

    val INPUT_TOPICS_PARTITIONS = new PropertyDescriptor.Builder()
        .name("kafka.input.topics.partitions")
        .description("if autoCreate is set to true, this will set the number of partition at topic creation time")
        .required(false)
        .addValidator(StandardValidators.INTEGER_VALIDATOR)
        .defaultValue("20")
        .build

    val OUTPUT_TOPICS_PARTITIONS = new PropertyDescriptor.Builder()
        .name("kafka.output.topics.partitions")
        .description("if autoCreate is set to true, this will set the number of partition at topic creation time")
        .required(false)
        .addValidator(StandardValidators.INTEGER_VALIDATOR)
        .defaultValue("20")
        .build

    val AVRO_INPUT_SCHEMA = new PropertyDescriptor.Builder()
        .name("avro.input.schema")
        .description("the avro schema definition")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build

    val AVRO_OUTPUT_SCHEMA = new PropertyDescriptor.Builder()
        .name("avro.output.schema")
        .description("the avro schema definition for the output serialization")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build

    val AVRO_SERIALIZER = new AllowableValue(classOf[AvroSerializer].getName,
        "avro serialization", "serialize events as avro blocs")
    val JSON_SERIALIZER = new AllowableValue(classOf[JsonSerializer].getName,
        "avro serialization", "serialize events as json blocs")
    val KRYO_SERIALIZER = new AllowableValue(classOf[KryoSerializer].getName,
        "kryo serialization", "serialize events as json blocs")
    val BYTESARRAY_SERIALIZER = new AllowableValue(classOf[BytesArraySerializer].getName,
        "byte array serialization", "serialize events as byte arrays")
    val NO_SERIALIZER = new AllowableValue("none", "no serialization", "send events as bytes")


    val INPUT_SERIALIZER = new PropertyDescriptor.Builder()
        .name("kafka.input.topics.serializer")
        .description("")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .allowableValues(KRYO_SERIALIZER, JSON_SERIALIZER, AVRO_SERIALIZER, BYTESARRAY_SERIALIZER, NO_SERIALIZER)
        .defaultValue(KRYO_SERIALIZER.getValue)
        .build

    val OUTPUT_SERIALIZER = new PropertyDescriptor.Builder()
        .name("kafka.output.topics.serializer")
        .description("")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .allowableValues(KRYO_SERIALIZER, JSON_SERIALIZER, AVRO_SERIALIZER, BYTESARRAY_SERIALIZER, NO_SERIALIZER)
        .defaultValue(KRYO_SERIALIZER.getValue)
        .build

    val ERROR_SERIALIZER = new PropertyDescriptor.Builder()
        .name("kafka.error.topics.serializer")
        .description("")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue(JSON_SERIALIZER.getValue)
        .allowableValues(KRYO_SERIALIZER, JSON_SERIALIZER, AVRO_SERIALIZER, BYTESARRAY_SERIALIZER, NO_SERIALIZER)
        .build


    val KAFKA_TOPIC_AUTOCREATE = new PropertyDescriptor.Builder()
        .name("kafka.topic.autoCreate")
        .description("define wether a topic should be created automatically if not already exists")
        .required(false)
        .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
        .defaultValue("true")
        .build

    val KAFKA_TOPIC_DEFAULT_PARTITIONS = new PropertyDescriptor.Builder()
        .name("kafka.topic.default.partitions")
        .description("if autoCreate is set to true, this will set the number of partition at topic creation time")
        .required(false)
        .addValidator(StandardValidators.INTEGER_VALIDATOR)
        .defaultValue("20")
        .build

    val KAFKA_TOPIC_DEFAULT_REPLICATION_FACTOR = new PropertyDescriptor.Builder()
        .name("kafka.topic.default.replicationFactor")
        .description("if autoCreate is set to true, this will set the number of replica for each partition at topic creation time")
        .required(false)
        .addValidator(StandardValidators.INTEGER_VALIDATOR)
        .defaultValue("3")
        .build

    val KAFKA_METADATA_BROKER_LIST = new PropertyDescriptor.Builder()
        .name("kafka.metadata.broker.list")
        .description("a comma separated list of host:port brokers")
        .required(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue("sandbox:9092")
        .build

    val KAFKA_ZOOKEEPER_QUORUM = new PropertyDescriptor.Builder()
        .name("kafka.zookeeper.quorum")
        .description("")
        .required(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue("sandbox:2181")
        .build

    val LATEST_OFFSET = new AllowableValue("latest", "latest", "the offset to the latest offset")
    val EARLIEST_OFFSET = new AllowableValue("earliest", "earliest offset", "the offset to the earliest offset")
    val NONE_OFFSET = new AllowableValue("none", "none offset", "the latest saved  offset")

    val KAFKA_MANUAL_OFFSET_RESET = new PropertyDescriptor.Builder()
        .name("kafka.manual.offset.reset")
        .description("What to do when there is no initial offset in Kafka or if the current offset does not exist " +
            "any more on the server (e.g. because that data has been deleted):\n" +
            "earliest: automatically reset the offset to the earliest offset\n" +
            "latest: automatically reset the offset to the latest offset\n" +
            "none: throw exception to the consumer if no previous offset is found for the consumer's group\n" +
            "anything else: throw exception to the consumer.")
        .required(false)
        .allowableValues(LATEST_OFFSET, EARLIEST_OFFSET, NONE_OFFSET)
        .defaultValue(EARLIEST_OFFSET.getValue)
        .build

    val LOGISLAND_AGENT_HOST = new PropertyDescriptor.Builder()
        .name("logisland.agent.host")
        .description("the stream needs to know how to reach Agent REST api in order to live update its processors")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .defaultValue("sandbox:8081")
        .build

    val LOGISLAND_AGENT_PULL_THROTTLING = new PropertyDescriptor.Builder()
        .name("logisland.agent.pull.throttling")
        .description("wait every x batch to pull agent for new conf")
        .required(false)
        .addValidator(StandardValidators.INTEGER_VALIDATOR)
        .defaultValue("10")
        .build


    val KAFKA_BATCH_SIZE = new PropertyDescriptor.Builder()
        .name("kafka.batch.size")
        .description("measures batch size in total bytes instead of the number of messages. " +
            "It controls how many bytes of data to collect before sending messages to the Kafka broker. " +
            "Set this as high as possible, without exceeding available memory. The default value is 16384.\n\n" +
            "If you increase the size of your buffer, it might never get full." +
            "The Producer sends the information eventually, based on other triggers, such as linger time in milliseconds. " +
            "Although you can impair memory usage by setting the buffer batch size too high, " +
            "this does not impact latency.\n\n" +
            "If your producer is sending all the time, " +
            "you are probably getting the best throughput possible. If the producer is often idle, " +
            "you might not be writing enough data to warrant the current allocation of resources.")
        .required(false)
        .addValidator(StandardValidators.INTEGER_VALIDATOR)
        .defaultValue("16384")
        .build


    val KAFKA_LINGER_MS = new PropertyDescriptor.Builder()
        .name("kafka.linger.ms")
        .description("linger.ms sets the maximum time to buffer data in asynchronous mode. " +
            "For example, a setting of 100 batches 100ms of messages to send at once. " +
            "This improves throughput, but the buffering adds message delivery latency.\n\n" +
            "By default, the producer does not wait. It sends the buffer any time data is available.\n\n" +
            "Instead of sending immediately, you can set linger.ms to 5 and send more messages in one batch." +
            " This would reduce the number of requests sent, but would add up to 5 milliseconds of latency to records " +
            "sent, even if the load on the system does not warrant the delay.\n\n" +
            "The farther away the broker is from the producer, the more overhead required to send messages. " +
            "Increase linger.ms for higher latency and higher throughput in your producer.")
        .required(false)
        .addValidator(StandardValidators.INTEGER_VALIDATOR)
        .defaultValue("5")
        .build

    val KAFKA_ACKS = new PropertyDescriptor.Builder()
        .name("kafka.acks")
        .description("The number of acknowledgments the producer requires the leader to have received before considering a request complete. This controls the "
            + " durability of records that are sent. The following settings are common: "
            + " <ul>"
            + " <li><code>acks=0</code> If set to zero then the producer will not wait for any acknowledgment from the"
            + " server at all. The record will be immediately added to the socket buffer and considered sent. No guarantee can be"
            + " made that the server has received the record in this case, and the <code>retries</code> configuration will not"
            + " take effect (as the client won't generally know of any failures). The offset given back for each record will"
            + " always be set to -1."
            + " <li><code>acks=1</code> This will mean the leader will write the record to its local log but will respond"
            + " without awaiting full acknowledgement from all followers. In this case should the leader fail immediately after"
            + " acknowledging the record but before the followers have replicated it then the record will be lost."
            + " <li><code>acks=all</code> This means the leader will wait for the full set of in-sync replicas to"
            + " acknowledge the record. This guarantees that the record will not be lost as long as at least one in-sync replica"
            + " remains alive. This is the strongest available guarantee.")
        .required(false)
        .defaultValue("all")
        .build


    val WINDOW_DURATION = new PropertyDescriptor.Builder()
        .name("window.duration")
        .description("all the elements in seen in a sliding window of time over. windowDuration = width of the window; must be a multiple of batching interval")
        .addValidator(StandardValidators.LONG_VALIDATOR)
        .required(false)
        .build

    val SLIDE_DURATION = new PropertyDescriptor.Builder()
        .name("slide.duration")
        .description("sliding interval of the window (i.e., the interval after which  the new DStream will generate RDDs); must be a multiple of batching interval")
        .addValidator(StandardValidators.LONG_VALIDATOR)
        .required(false)
        .build

}

abstract class AbstractKafkaRecordStream extends AbstractRecordStream with KafkaRecordStream {

    val NONE_TOPIC: String = "none"
    private val logger = LoggerFactory.getLogger(classOf[AbstractKafkaRecordStream])
    protected var kafkaSink: Broadcast[KafkaSink] = null
    protected var appName: String = ""
    @transient protected var ssc: StreamingContext = null
    protected var streamContext: StreamContext = null
    protected var engineContext: EngineContext = null
    protected var restApiSink: Broadcast[RestJobsApiClientSink] = null
    protected var controllerServiceLookupSink: Broadcast[ControllerServiceLookupSink] = null
    protected var currentJobVersion: Int = 0
    protected var lastCheckCount: Int = 0
    protected var needMetricsReset = false

    override def getSupportedPropertyDescriptors: util.List[PropertyDescriptor] = {
        val descriptors: util.List[PropertyDescriptor] = new util.ArrayList[PropertyDescriptor]
        descriptors.add(AbstractKafkaRecordStream.ERROR_TOPICS)
        descriptors.add(AbstractKafkaRecordStream.INPUT_TOPICS)
        descriptors.add(AbstractKafkaRecordStream.OUTPUT_TOPICS)
        descriptors.add(AbstractKafkaRecordStream.AVRO_INPUT_SCHEMA)
        descriptors.add(AbstractKafkaRecordStream.AVRO_OUTPUT_SCHEMA)
        descriptors.add(AbstractKafkaRecordStream.INPUT_SERIALIZER)
        descriptors.add(AbstractKafkaRecordStream.OUTPUT_SERIALIZER)
        descriptors.add(AbstractKafkaRecordStream.ERROR_SERIALIZER)
        descriptors.add(AbstractKafkaRecordStream.KAFKA_TOPIC_AUTOCREATE)
        descriptors.add(AbstractKafkaRecordStream.KAFKA_TOPIC_DEFAULT_PARTITIONS)
        descriptors.add(AbstractKafkaRecordStream.KAFKA_TOPIC_DEFAULT_REPLICATION_FACTOR)
        descriptors.add(AbstractKafkaRecordStream.KAFKA_METADATA_BROKER_LIST)
        descriptors.add(AbstractKafkaRecordStream.KAFKA_ZOOKEEPER_QUORUM)
        descriptors.add(AbstractKafkaRecordStream.KAFKA_MANUAL_OFFSET_RESET)
        descriptors.add(AbstractKafkaRecordStream.LOGISLAND_AGENT_HOST)
        descriptors.add(AbstractKafkaRecordStream.LOGISLAND_AGENT_PULL_THROTTLING)
        descriptors.add(AbstractKafkaRecordStream.KAFKA_BATCH_SIZE)
        descriptors.add(AbstractKafkaRecordStream.KAFKA_LINGER_MS)
        descriptors.add(AbstractKafkaRecordStream.KAFKA_ACKS)
        descriptors.add(AbstractKafkaRecordStream.WINDOW_DURATION)
        descriptors.add(AbstractKafkaRecordStream.SLIDE_DURATION)
        Collections.unmodifiableList(descriptors)
    }


    override def setup(appName: String, ssc: StreamingContext, streamContext: StreamContext, engineContext: EngineContext) = {
        this.appName = appName
        this.ssc = ssc
        this.streamContext = streamContext
        this.engineContext = engineContext
        SparkUtils.customizeLogLevels
    }

    override def getStreamContext(): StreamingContext = this.ssc

    override def start() = {
        if (ssc == null)
            throw new IllegalStateException("stream not initialized")

        try {

            // Define the Kafka parameters, broker list must be specified
            val inputTopics = streamContext.getPropertyValue(AbstractKafkaRecordStream.INPUT_TOPICS).asString.split(",").toSet
            val outputTopics = streamContext.getPropertyValue(AbstractKafkaRecordStream.OUTPUT_TOPICS).asString.split(",").toSet
            val errorTopics = streamContext.getPropertyValue(AbstractKafkaRecordStream.ERROR_TOPICS).asString.split(",").toSet
            val metricsTopics = AbstractKafkaRecordStream.DEFAULT_METRICS_TOPIC.getValue.split(",").toSet

            val topicAutocreate = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_TOPIC_AUTOCREATE).asBoolean().booleanValue()
            val topicDefaultPartitions = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_TOPIC_DEFAULT_PARTITIONS).asInteger().intValue()
            val topicDefaultReplicationFactor = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_TOPIC_DEFAULT_REPLICATION_FACTOR).asInteger().intValue()
            val brokerList = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_METADATA_BROKER_LIST).asString
            val zkQuorum = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_ZOOKEEPER_QUORUM).asString

            val agentQuorum = streamContext.getPropertyValue(AbstractKafkaRecordStream.LOGISLAND_AGENT_HOST).asString
            val throttling = streamContext.getPropertyValue(AbstractKafkaRecordStream.LOGISLAND_AGENT_PULL_THROTTLING).asInteger()
            val kafkaBatchSize = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_BATCH_SIZE).asString
            val kafkaLingerMs = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_LINGER_MS).asString
            val kafkaAcks = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_ACKS).asString
            val kafkaOffset = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_MANUAL_OFFSET_RESET).asString


            val kafkaSinkParams = Map(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> brokerList,
                ProducerConfig.CLIENT_ID_CONFIG -> appName,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG -> classOf[ByteArraySerializer].getCanonicalName,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG -> classOf[ByteArraySerializer].getName,
                ProducerConfig.ACKS_CONFIG -> kafkaAcks,
                ProducerConfig.RETRIES_CONFIG -> "3",
                ProducerConfig.LINGER_MS_CONFIG -> kafkaLingerMs,
                ProducerConfig.BATCH_SIZE_CONFIG -> kafkaBatchSize,
                ProducerConfig.RETRY_BACKOFF_MS_CONFIG -> "1000",
                ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG -> "1000")

            kafkaSink = ssc.sparkContext.broadcast(KafkaSink(kafkaSinkParams))
            restApiSink = ssc.sparkContext.broadcast(RestJobsApiClientSink(agentQuorum))
            controllerServiceLookupSink = ssc.sparkContext.broadcast(
                ControllerServiceLookupSink(engineContext.getControllerServiceConfigurations)
            )

            // TODO deprecate topic creation here (must be done through the agent)
            if (topicAutocreate) {
                val zkUtils = ZkUtils.apply(zkQuorum, 10000, 10000, JaasUtils.isZkSecurityEnabled)
                createTopicsIfNeeded(zkUtils, inputTopics, topicDefaultPartitions, topicDefaultReplicationFactor)
                createTopicsIfNeeded(zkUtils, outputTopics, topicDefaultPartitions, topicDefaultReplicationFactor)
                createTopicsIfNeeded(zkUtils, errorTopics, topicDefaultPartitions, topicDefaultReplicationFactor)
                createTopicsIfNeeded(zkUtils, metricsTopics, 1, 1)
            }


            val kafkaParams = Map[String, Object](
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> brokerList,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG -> classOf[ByteArrayDeserializer],
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG -> classOf[ByteArrayDeserializer],
                ConsumerConfig.GROUP_ID_CONFIG -> appName,
                ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG -> "50",
                ConsumerConfig.RETRY_BACKOFF_MS_CONFIG -> "100",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> kafkaOffset,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
                ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG -> "30000"
                /*,
                ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG -> "5000"*/
            )


            logger.info(s"starting Kafka direct stream on topics $inputTopics from $kafkaOffset offsets")
            @transient val kafkaStream = KafkaUtils.createDirectStream[Array[Byte], Array[Byte]](
                ssc,
                PreferConsistent,
                Subscribe[Array[Byte], Array[Byte]](inputTopics, kafkaParams)
            )

            // store current configuration version
            currentJobVersion = restApiSink.value.getJobApiClient.getJobVersion(appName)

            // do the parallel processing

            val stream = if (streamContext.getPropertyValue(AbstractKafkaRecordStream.WINDOW_DURATION).isSet) {
                if (streamContext.getPropertyValue(AbstractKafkaRecordStream.SLIDE_DURATION).isSet)
                    kafkaStream.window(
                        Seconds(streamContext.getPropertyValue(AbstractKafkaRecordStream.WINDOW_DURATION).asLong()),
                        Seconds(streamContext.getPropertyValue(AbstractKafkaRecordStream.SLIDE_DURATION).asLong())
                    )
                else
                    kafkaStream.window(Seconds(streamContext.getPropertyValue(AbstractKafkaRecordStream.WINDOW_DURATION).asLong()))

            } else kafkaStream


            stream.foreachRDD(rdd => {


                if (!rdd.isEmpty()) {

                    /**
                      * check if conf needs to be refreshed
                      */
                    if (lastCheckCount > throttling) {
                        lastCheckCount = 0
                        val version = restApiSink.value.getJobApiClient.getJobVersion(appName)
                        if (currentJobVersion != version) {
                            logger.info("Job version change detected from {} to {}, proceeding to update",
                                currentJobVersion,
                                version)

                            val componentFactory = new RestComponentFactory(agentQuorum)
                            val updatedEngineContext = componentFactory.getEngineContext(appName)
                            if (updatedEngineContext.isPresent) {

                                // find the corresponding stream
                                val it = updatedEngineContext.get().getStreamContexts.iterator()
                                while (it.hasNext) {
                                    val updatedStreamingContext = it.next()

                                    // if we found a streamContext with the same name from the factory
                                    if (updatedStreamingContext.getName == this.streamContext.getName) {
                                        logger.info("new conf for stream {}", updatedStreamingContext.getName)
                                        this.streamContext = updatedStreamingContext
                                    }
                                }
                            }
                            currentJobVersion = version
                        }
                    }

                    lastCheckCount += 1


                    val offsetRanges = process(rdd)
                    // some time later, after outputs have completed
                    if (offsetRanges.nonEmpty) {
                       // kafkaStream.asInstanceOf[CanCommitOffsets].commitAsync(offsetRanges.get)


                        kafkaStream.asInstanceOf[CanCommitOffsets].commitAsync(offsetRanges.get, new OffsetCommitCallback() {
                            def onComplete(m: java.util.Map[TopicPartition, OffsetAndMetadata], e: Exception) {
                                if (null != e) {
                                    logger.error("error commiting offsets", e)
                                }
                            }
                        })


                        needMetricsReset = true
                    }
                    else if (needMetricsReset) {
                        try {

                            for (partitionId <- 0 to rdd.getNumPartitions) {
                                val pipelineMetricPrefix = streamContext.getIdentifier + "." +
                                    "partition" + partitionId + "."
                                val pipelineTimerContext = UserMetricsSystem.timer(pipelineMetricPrefix + "Pipeline.processing_time_ms").time()

                                streamContext.getProcessContexts.foreach(processorContext => {
                                    UserMetricsSystem.timer(pipelineMetricPrefix + processorContext.getName + ".processing_time_ms")
                                        .time()
                                        .stop()

                                    ProcessorMetrics.resetMetrics(pipelineMetricPrefix + processorContext.getName + ".")
                                })
                                pipelineTimerContext.stop()
                            }
                        } catch {
                            case ex: Throwable =>
                                logger.error(s"exception : ${ex.toString}")
                                None
                        } finally {
                            needMetricsReset = false
                        }
                    }
                }

            })
        } catch {
            case ex: Throwable =>
                ex.printStackTrace()
                logger.error("something bad happened, please check Kafka or Zookeeper health : {}", ex)
        }
    }


    /**
      * to be overriden by subclasses
      *
      * @param rdd
      */
    def process(rdd: RDD[ConsumerRecord[Array[Byte], Array[Byte]]]): Option[Array[OffsetRange]]


    /**
      * build a serializer
      *
      * @param inSerializerClass the serializer type
      * @param schemaContent     an Avro schema
      * @return the serializer
      */
    def getSerializer(inSerializerClass: String, schemaContent: String): RecordSerializer = {
        // TODO move this in a utility class
        inSerializerClass match {
            case c if c == AbstractKafkaRecordStream.AVRO_SERIALIZER.getValue =>
                val parser = new Parser
                val inSchema = parser.parse(schemaContent)
                new AvroSerializer(inSchema)
            case c if c == AbstractKafkaRecordStream.JSON_SERIALIZER.getValue => new JsonSerializer()
            case c if c == AbstractKafkaRecordStream.BYTESARRAY_SERIALIZER.getValue => new BytesArraySerializer()
            case _ => new KryoSerializer(true)
        }
    }

    /**
      *
      * @param partition
      * @param serializer
      * @return
      */
    def deserializeRecords(partition: Iterator[ConsumerRecord[Array[Byte], Array[Byte]]], serializer: RecordSerializer): List[Record] = {
        partition.flatMap(rawEvent => {

            // TODO handle key also
            try {
                val bais = new ByteArrayInputStream(rawEvent.value())
                val deserialized = serializer.deserialize(bais)
                bais.close()

                Some(deserialized)
            } catch {
                case t: Throwable =>
                    logger.error(s"exception while deserializing events ${t.getMessage}")
                    None
            }

        }).toList
    }


    /**
      * Topic creation
      *
      * @param zkUtils
      * @param topics
      * @param topicDefaultPartitions
      * @param topicDefaultReplicationFactor
      */
    def createTopicsIfNeeded(zkUtils: ZkUtils,
                             topics: Set[String],
                             topicDefaultPartitions: Int,
                             topicDefaultReplicationFactor: Int): Unit = {

        topics.foreach(topic => {

            if (!topic.equals(NONE_TOPIC) && !AdminUtils.topicExists(zkUtils, topic)) {
                AdminUtils.createTopic(zkUtils, topic, topicDefaultPartitions, topicDefaultReplicationFactor)
                Thread.sleep(1000)
                logger.info(s"created topic $topic with" +
                    s" $topicDefaultPartitions partitions and" +
                    s" $topicDefaultReplicationFactor replicas")
            }
        })
    }
}


