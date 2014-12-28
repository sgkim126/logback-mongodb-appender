logback-mongodb-appender
======
## Example
```xml
<appender name="MONGO" class="logback.mongodb.MongoDBAppender">
  <address>127.0.0.1</address>
  <port>27017</prt>
  <db>logback</db>
  <collection>log</collection>
  <expireAfterSeconds>86400</expireAfterSeconds>
</appender>
```
======
## Futher works
* Support MongoClient for sharded cluster
