name := "baseband"
organization := "edu.berkeley.cs"
version := "0.0.1"

scalaVersion := "2.13.10"

scalacOptions := Seq("-deprecation", "-unchecked", "-Xsource:2.13", "-language:reflectiveCalls")
libraryDependencies ++= Seq("edu.berkeley.cs" %% "chisel3" % "3.5.5",
                            "edu.berkeley.cs" %% "rocketchip" % "1.2.+",
                            "edu.berkeley.cs" %% "chiseltest" % "0.5.5",
                            // "edu.berkeley.cs" %% "testchipip" % "1.0-SNAPSHOT",
                            "org.scalatest" %% "scalatest" % "3.2.+" % "test",
                            "edu.berkeley.cs" %% "dsptools" % "1.5.5",
                            "org.scalanlp" %% "breeze-viz" % "1.1")

resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases"),
  Resolver.mavenLocal)
