organization in ThisBuild := "jkugiya"
version in ThisBuild := "1.0-SNAPSHOT"

// the Scala version that will be used for cross-compiled libraries
scalaVersion in ThisBuild := "2.13.6"

val macwire = "com.softwaremill.macwire" %% "macros" % "2.3.3" % "provided"
val scalaTest = "org.scalatest" %% "scalatest" % "3.1.1" % Test
val slick = "com.typesafe.slick" %% "slick" % "3.3.3"
val circeVersion = "0.14.1"
val circe = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-generic-extras"
).map(_ % circeVersion)
val catsCore = "org.typelevel" %% "cats-core" % "2.3.0"
val logback = "com.lightbend.lagom" %% "lagom-logback" % "1.6.5"
val akkaVersion = "2.6.15"
val akka = Seq(
  "akka-cluster-sharding-typed",
  "akka-distributed-data",
  "akka-cluster-tools",
  "akka-persistence",
  "akka-cluster-sharding",
  "akka-discovery",
  "akka-persistence-query",
  "akka-coordination",
  "akka-persistence-typed",
  "akka-remote",
  "akka-cluster",
  "akka-cluster-typed",
  "akka-actor-testkit-typed",
  "akka-testkit",
  "akka-serialization-jackson",
  "akka-pki",
  "akka-stream-typed"
).map(lib => "com.typesafe.akka" %% lib % akkaVersion)

lazy val `money-transfer` = (project in file("."))
  .aggregate(`money-transfer-api`, `money-transfer-impl`)

lazy val `money-transfer-api` = (project in file("money-transfer-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    ) ++ circe ++ akka
  )

lazy val `money-transfer-impl` = (project in file("money-transfer-impl"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslPersistenceJdbc,
      lagomScaladslKafkaBroker,
      lagomScaladslTestKit,
      catsCore,
      logback,
      macwire,
      "mysql" % "mysql-connector-java" % "8.0.25",
      scalaTest
    )
  )
  .settings(lagomForkedTestSettings)
  .dependsOn(`money-transfer-api`)

ThisBuild / lagomCassandraEnabled := false

ThisBuild / lagomKafkaEnabled := false
ThisBuild / lagomKafkaPort := 9092
ThisBuild / lagomKafkaZookeeperPort := 2181
