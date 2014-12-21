package logback.mongodb

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase

class MongoDBAppender extends UnsynchronizedAppenderBase[ILoggingEvent]  {
  override def append(event: ILoggingEvent) {
  }
}
