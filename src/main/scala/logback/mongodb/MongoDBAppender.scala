package logback.mongodb

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import com.mongodb.DBCollection
import com.mongodb.DBObject
import com.mongodb.MongoClient
import com.osinka.subset.DBO
import com.osinka.subset.DBObjectBuffer
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

  override def append(event: ILoggingEvent) {
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
      "timestamp" -> event.getTimeStamp,
      "thread" -> event.getThreadName,
      "caller" -> caller,
      "mdc" -> mdc
    )

    val logObject: DBObject = logBuffer()
  }
}
