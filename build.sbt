name := "logback-mongodb-appender"

version := "0.9.1"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.1.2",
  "com.osinka.subset" %% "subset" % "2.1.4"
)

org.scalastyle.sbt.ScalastylePlugin.Settings
