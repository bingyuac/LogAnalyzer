name := "DDoS-Log-Analyzer"

version := "0.1"

scalaVersion := "2.10.6"

javacOptions ++= Seq("-source" , "1.8", "-target", "1.8")



//Spark Libraries

libraryDependencies ++= Seq(
  "org.apache.spark" % "spark-core_2.10" % "1.6.2" % "provided",
  "org.apache.spark" % "spark-streaming_2.10" % "1.6.2",
  "org.apache.spark" % "spark-streaming-kafka_2.10" % "1.6.2"
)

resolvers ++= Seq(
  "Akka Repository" at "http://repo.akka.io/releases/"
)

assemblyMergeStrategy in assembly := {
  // javax is chock full of duplicates
  case PathList("javax", "servlet", xs @ _*)         => MergeStrategy.first

  // Primary class merge - also the most dangerous item
  case x if x.endsWith(".class")                     => MergeStrategy.first

  // probably junk
  case x if x.endsWith(".xml")                       => MergeStrategy.first
  case x if x.endsWith(".txt")                       => MergeStrategy.first
  case x if x.endsWith(".properties")                => MergeStrategy.first
  case x if x.endsWith(".jnilib")                    => MergeStrategy.first

  // definitely junk
  case "unwanted.txt"                                => MergeStrategy.discard
  case x if x.startsWith("META-INF")                 => MergeStrategy.discard
  case x if x.endsWith(".html")                      => MergeStrategy.discard
  case x if x.endsWith(".js")                        => MergeStrategy.discard
  case x if x.endsWith(".dll")                       => MergeStrategy.discard
  case x if x.endsWith("VERSION")                    => MergeStrategy.discard

  // not sure what (if anything) this is doing
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

// Shows a nice chart of all the dependencies and their versions
// Includes transitive dependencies (the dependencies of dependencies)
lazy val versionReport = TaskKey[String]("version-report")
versionReport <<= (externalDependencyClasspath in Compile, streams) map {
  (cp: Seq[Attributed[File]], streams) =>
    val report = cp.map {
      attributed =>
        attributed.get(Keys.moduleID.key) match {
          case Some(moduleId) => "%40s %20s %10s %10s %10s".format(
            moduleId.organization,
            moduleId.name,
            moduleId.revision,
            moduleId.configurations.getOrElse(""),
            attributed.data.getAbsolutePath
          )
          case None           =>
            // unmanaged JAR, just
            attributed.data.getAbsolutePath
        }
    }.mkString("\n")
    streams.log.info(report)
    report
}

// Compile Dependencies into a folder named all-jar-dependencies
lazy val collectDependencies = TaskKey[Unit]("collect-dependencies")
collectDependencies <<= (externalDependencyClasspath in Compile, streams).map((cp: Seq[Attributed[File]], streams) => {
  import sys.process._

  Process("mkdir ./all-jar-dependencies")!

  cp.map(attributed => {
    "cp %s ./all-jar-dependencies/".format(attributed.data.getAbsolutePath)
  }).foreach(cmd => {
    println(cmd)
    Process(cmd)!
  })
  // streams.log.info(report)
  //report
})