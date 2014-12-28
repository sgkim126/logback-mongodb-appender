// The MIT License (MIT)
//
// Copyright (c) 2014, Seulgi Kim dev@seulgi.kim
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package logback.mongodb

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import com.mongodb.BasicDBObject
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

  private var _expireAfterSeconds: Int = 60 * 60 * 24 // 24 hours
  def setExpireAfterSeconds(expireAfterSeconds: Int) {
    _expireAfterSeconds = expireAfterSeconds
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

  private def mongo: DBCollection =
    _mongo.getOrElse {
      withLock { _ =>
        _mongo.getOrElse {
          val client = new MongoClient(_address, _port)
          val db = client.getDB(_db)
          val collection = db.getCollection(_collection)

          val isIndexOnTimestamp: Function[AnyRef, Boolean] = {
            case index: DBObject =>
              index.get("key") match {
                case key: DBObject =>
                  Option(key.get("timestamp")).isDefined
                case _ =>
                  false
              }
            case _ =>
              false
          }

          if (collection.getIndexInfo.toArray.exists(isIndexOnTimestamp)) {
            val newIndex =
              new BasicDBObject("keyPattern", new BasicDBObject("timestamp", 1))
                .append("expireAfterSeconds", _expireAfterSeconds)
            db.command(new BasicDBObject("collMod", _collection).append("index", newIndex))
          } else {
            collection.createIndex(new BasicDBObject("timestamp", 1), new BasicDBObject("expireAfterSeconds", _expireAfterSeconds))
          }

          _mongo = Some(collection)
          collection
        }
      }(mongoLock)
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
