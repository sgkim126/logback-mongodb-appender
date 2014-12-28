package logback.mongodb

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import com.mongodb.DBCollection
import com.mongodb.DBObject
import com.mongodb.MongoClient
import com.mongodb.WriteConcern
import com.osinka.subset.DBO
import com.osinka.subset.DBObjectBuffer
import java.util.Date
import scala.concurrent.Lock

class MongoDBAppender extends UnsynchronizedAppenderBase[ILoggingEvent]  {
  private var _db: String = "logback"
  def setDb(name: String) {
    _db = name
    withLock { _ =>
      _mongo = None
    }(mongoLock)
  }

  private var _collection: String = "log"
  def setCollection(name: String) {
    _collection = name
    withLock { _ =>
      _mongo = None
    }(mongoLock)
  }

  private var _address: String = "localhost"
  def setAddress(address: String) {
    _address = address
    withLock { _ =>
      _mongo = None
    }(mongoLock)
  }

  private var _port: Int = 27017
  def setPort(port: Int) {
    _port = port
    withLock { _ =>
      _mongo = None
    }(mongoLock)
  }

  def withLock[T](function: Unit => T)(lock: Lock): T = {
    lock.acquire()
    try {
      function()
    } finally {
      lock.release()
    }
  }

  private var _mongo: Option[DBCollection] = None
  private val mongoLock: Lock = new Lock

  private def mongo: DBCollection = {
    if (_mongo.isEmpty) {
      withLock { _ =>
        if (_mongo.isEmpty) {
          val client = new MongoClient(_address, _port)
          val db = client.getDB(_db)
          _mongo = Some(db.getCollection(_collection))
        }
      }(mongoLock)
    }
    _mongo.get
  }

  private var _queueSize: Int = 30
  def setQueueSize(queueSize: Int) {
    withLock { _ =>
      _queueSize = queueSize
    }(queueLock)
  }

  private var queue: Seq[DBObject] = Seq.empty[DBObject]
  private val queueLock: Lock = new Lock
  private def consumeQueue() {
    if (queue.nonEmpty) {
      withLock { _ =>
        queue = queue.foldLeft(Seq.empty[DBObject]) { (queue, log) =>
          try {
            save(log)
            queue
          } catch {
            case _: Throwable =>
              queue :+ log
          }
        }
      }(queueLock)
    }
  }
  private def enqueue(log: DBObject) {
    withLock { _ =>
      queue = (queue :+ log).takeRight(_queueSize)
    }(queueLock)
  }

  override def append(event: ILoggingEvent) {
    consumeQueue()

    val caller: Seq[DBObjectBuffer] = event.getCallerData.map { st =>
      DBO("class" -> st.getClassName,
        "file" -> st.getFileName,
        "line" -> st.getLineNumber,
        "method" -> st.getMethodName
      )
    }

    import scala.collection.convert.wrapAsScala.mapAsScalaMap

    val mdc: Seq[DBObjectBuffer] = event.getMDCPropertyMap.map {
      case (key: String, value: String) =>
        DBO("key" -> key, "value" -> value)
    }.toSeq

    val logBuffer: DBObjectBuffer = DBO("level" -> event.getLevel.toString,
      "name" -> event.getLoggerName,
      "message" -> event.getMessage,
      "timestamp" -> new Date(event.getTimeStamp),
      "thread" -> event.getThreadName,
      "caller" -> caller,
      "mdc" -> mdc
    )

    val logObject: DBObject = logBuffer()

    try {
      save(logObject)
    } catch {
      case _: Throwable =>
        enqueue(logObject)
    }
  }

  private def save(log: DBObject) {
    mongo.save(log, WriteConcern.UNACKNOWLEDGED)
  }
}
