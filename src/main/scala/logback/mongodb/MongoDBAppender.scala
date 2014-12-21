package logback.mongodb

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase

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


  override def append(event: ILoggingEvent) {
  }
}
